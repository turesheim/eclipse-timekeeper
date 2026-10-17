package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

class PasskeySchemaUpgradeTest {
    private void prior(Connection c) throws Exception {
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/db/schema-v1.sql"),StandardCharsets.UTF_8)) {RunScript.execute(c,reader);}
        ServerSchema.applyAuth(c);ServerSchema.applyRecovery(c);
        try(var s=c.createStatement()) {
            s.executeUpdate("UPDATE tk_schema SET schema_version=3,state='READY'");
            s.executeUpdate("INSERT INTO tk_owner VALUES('retained','Europe/Oslo',7,19,'cursor')");
            s.executeUpdate("INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES('retained','retained','unchanged hash','Retained','ENABLED',TRUE,5)");
            s.executeUpdate("INSERT INTO tk_auth_session VALUES('old-session','retained','csrf','READ_WRITE','2026-10-17T00:00:00Z','2026-10-18T00:00:00Z','2026-10-17T00:00:00Z',FALSE,NULL)");
            s.executeUpdate("INSERT INTO tk_api_token(digest,id,owner_id,name,permissions,revoked,source,created_at,version) VALUES('retained-token',RANDOM_UUID(),'retained','Token','READ_WRITE',FALSE,'MANAGED','2026-10-17T00:00:00Z',0)");
            s.executeUpdate("INSERT INTO tk_recovery_address VALUES('retained','PRIMARY','verified@example.invalid',NULL,3)");
            s.executeUpdate("UPDATE tk_recovery_control SET origin='https://timekeeper.example',epoch=7");
        }
    }
    @Test void v3UpgradePreservesAccountsWorkAndImportAuthorityButEndsSessionsWithoutOriginProvenance() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:passkey_upgrade_"+UUID.randomUUID());var s=c.createStatement()) {
            prior(c);UUID id=UUID.randomUUID();String payload="{\"privateImportReceipt\":\"preserve\"}";
            try(var insert=c.prepareStatement("INSERT INTO tk_resource VALUES('retained','IMPORT_JOB',?,8,?)")) {insert.setObject(1,id);insert.setString(2,payload);insert.executeUpdate();}
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(c));
            ServerSchema.initializeOrValidate(c,true);ServerSchema.initializeOrValidate(c);ServerSchema.validate(c);
            try(var rows=s.executeQuery("SELECT username,password_hash,version FROM tk_account")) {assertTrue(rows.next());assertEquals("retained",rows.getString(1));assertEquals("unchanged hash",rows.getString(2));assertEquals(5,rows.getLong(3));}
            try(var rows=s.executeQuery("SELECT id,version,payload FROM tk_resource")) {assertTrue(rows.next());assertEquals(id,rows.getObject(1,UUID.class));assertEquals(8,rows.getLong(2));assertEquals(payload,rows.getString(3));}
            try(var rows=s.executeQuery("SELECT count(*) FROM tk_auth_session")) {rows.next();assertEquals(0,rows.getInt(1));}
            try(var rows=s.executeQuery("SELECT revoked FROM tk_api_token")) {rows.next();assertFalse(rows.getBoolean(1));}
            try(var rows=s.executeQuery("SELECT verified_address,version FROM tk_recovery_address")) {rows.next();assertEquals("verified@example.invalid",rows.getString(1));assertEquals(3,rows.getLong(2));}
            try(var rows=s.executeQuery("SELECT origin,epoch FROM tk_recovery_control")) {rows.next();assertEquals("https://timekeeper.example",rows.getString(1));assertEquals(7,rows.getLong(2));}
        }
    }
    @Test void damagedPriorOrInterruptedPasskeySchemaIsNotSilentlyRepaired() throws Exception {
        for(String damage:List.of("UPDATE tk_schema SET state='MIGRATING'","ALTER TABLE tk_recovery_address DROP COLUMN version")) {
            try(var c=DriverManager.getConnection("jdbc:h2:mem:passkey_invalid_"+UUID.randomUUID());var s=c.createStatement()) {
                prior(c);s.execute(damage);assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(c,true));
                try(var rows=c.getMetaData().getTables(null,"PUBLIC","TK_PASSKEY_USER",null)) {assertFalse(rows.next());}
            }
        }
        try(var c=DriverManager.getConnection("jdbc:h2:mem:passkey_partial_"+UUID.randomUUID());var s=c.createStatement()) {
            prior(c);s.executeUpdate("UPDATE tk_schema SET state='MIGRATING'");ServerSchema.applyPasskeys(c);
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(c,true));
            try(var rows=s.executeQuery("SELECT schema_version,state FROM tk_schema")) {rows.next();assertEquals(3,rows.getInt(1));assertEquals("MIGRATING",rows.getString(2));}
        }
    }
}
