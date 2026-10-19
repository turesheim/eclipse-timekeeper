package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

class SettingsSchemaUpgradeTest {
    private void prior(Connection c) throws Exception {
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/db/schema-v1.sql"),StandardCharsets.UTF_8)) {RunScript.execute(c,reader);}
        ServerSchema.applyAuth(c);ServerSchema.applyRecovery(c);ServerSchema.applyPasskeys(c);
        try(var s=c.createStatement()) {
            s.executeUpdate("UPDATE tk_schema SET schema_version=4,state='READY'");
            s.executeUpdate("INSERT INTO tk_owner VALUES('retained','Europe/Oslo',7,19,'cursor')");
            s.executeUpdate("INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES('retained','retained','unchanged hash','Retained','ENABLED',TRUE,5)");
            s.executeUpdate("UPDATE tk_recovery_control SET origin='https://timekeeper.example',epoch=7");
            s.executeUpdate("INSERT INTO tk_passkey_user VALUES('retained','opaque-user-handle')");
            s.executeUpdate("INSERT INTO tk_passkey_credential VALUES('00000000-0000-4000-8000-000000000001','retained','digest','credential','timekeeper.example','public-key',0,FALSE,FALSE,'[]','Retained passkey','2026-10-17T00:00:00Z',NULL,FALSE,2)");
            s.executeUpdate("INSERT INTO tk_auth_session VALUES('retained-session','retained','csrf','READ_WRITE','2026-10-17T00:00:00Z','2026-10-18T00:00:00Z','2026-10-17T00:00:00Z',FALSE,NULL,7,'browser-digest','00000000-0000-4000-8000-000000000001')");
        }
    }
    @Test void canonicalV4UpgradePreservesAuthenticationAndWorkAndCreatesAnUnseededSharedSettingsSlot() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:settings_upgrade_"+UUID.randomUUID());var s=c.createStatement()) {
            prior(c);String payload="{\"privateImportReceipt\":\"preserve\"}";UUID id=UUID.randomUUID();
            try(var insert=c.prepareStatement("INSERT INTO tk_resource VALUES('retained','IMPORT_JOB',?,8,?)")) {insert.setObject(1,id);insert.setString(2,payload);insert.executeUpdate();}
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(c));
            ServerSchema.initializeOrValidate(c,true);ServerSchema.initializeOrValidate(c);ServerSchema.validate(c);
            try(var rows=s.executeQuery("SELECT username,password_hash,version FROM tk_account")) {assertTrue(rows.next());assertEquals("retained",rows.getString(1));assertEquals("unchanged hash",rows.getString(2));assertEquals(5,rows.getLong(3));}
            try(var rows=s.executeQuery("SELECT payload FROM tk_resource WHERE id='"+id+"'")) {assertTrue(rows.next());assertEquals(payload,rows.getString(1));}
            try(var rows=s.executeQuery("SELECT origin_epoch,passkey_id FROM tk_auth_session WHERE digest='retained-session'")) {assertTrue(rows.next());assertEquals(7,rows.getLong(1));assertEquals(UUID.fromString("00000000-0000-4000-8000-000000000001"),rows.getObject(2,UUID.class));}
            try(var rows=s.executeQuery("SELECT credential_id,public_key,version,revoked FROM tk_passkey_credential")) {assertTrue(rows.next());assertEquals("credential",rows.getString(1));assertEquals("public-key",rows.getString(2));assertEquals(2,rows.getLong(3));assertFalse(rows.getBoolean(4));}
            try(var rows=s.executeQuery("SELECT count(*) FROM tk_service_settings")) {rows.next();assertEquals(0,rows.getInt(1));}
            try(var rows=s.executeQuery("SELECT schema_version,state FROM tk_schema")) {rows.next();assertEquals(ServerSchema.CURRENT,rows.getInt(1));assertEquals("READY",rows.getString(2));}
        }
    }
    @Test void malformedOrInterruptedV4NeverGetsASettingsTable() throws Exception {
        for(String corruption:new String[]{"ALTER TABLE tk_auth_session ADD COLUMN unexpected INT","UPDATE tk_schema SET state='MIGRATING'"})
            try(var c=DriverManager.getConnection("jdbc:h2:mem:settings_bad_"+UUID.randomUUID());var s=c.createStatement()) {
                prior(c);s.execute(corruption);assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(c,true));
                try(var rows=c.getMetaData().getTables(null,"PUBLIC","TK_SERVICE_SETTINGS",null)) {assertFalse(rows.next());}
            }
    }
}
