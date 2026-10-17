package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.io.*;
import java.util.*;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

class AccountSchemaUpgradeTest {
    @Test void canonicalV1UpgradePreservesOwnedIdsPayloadsAndRevisionAndIsIdempotent() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:auth_upgrade_"+UUID.randomUUID());var statement=connection.createStatement()) {
            try(var input=new InputStreamReader(getClass().getResourceAsStream("/db/schema-v1.sql"),java.nio.charset.StandardCharsets.UTF_8)) {RunScript.execute(connection,input);}
            statement.executeUpdate("UPDATE tk_schema SET state='READY'");
            statement.executeUpdate("INSERT INTO tk_owner VALUES('legacy','Europe/Oslo',7,19,'aabb')");
            UUID id=UUID.randomUUID();String payload="{\"start\":\"2025-10-20T08:00:00.123456789Z\",\"ownerId\":\"legacy\"}";
            try(var insert=connection.prepareStatement("INSERT INTO tk_resource VALUES('legacy','WORK_SESSION',?,8,?)")) {insert.setObject(1,id);insert.setString(2,payload);insert.executeUpdate();}
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
            ServerSchema.initializeOrValidate(connection,true);ServerSchema.validate(connection);ServerSchema.initializeOrValidate(connection);
            try(var rows=statement.executeQuery("SELECT id,version,payload FROM tk_resource")) {assertTrue(rows.next());assertEquals(id,rows.getObject(1,UUID.class));assertEquals(8,rows.getLong(2));assertEquals(payload,rows.getString(3));assertFalse(rows.next());}
            try(var rows=statement.executeQuery("SELECT schema_version,state FROM tk_schema")) {rows.next();assertEquals(ServerSchema.CURRENT,rows.getInt(1));assertEquals("READY",rows.getString(2));}
            try(var rows=statement.executeQuery("SELECT version,revision FROM tk_owner WHERE id='legacy'")) {rows.next();assertEquals(7,rows.getLong(1));assertEquals(19,rows.getLong(2));}
        }
    }
    @Test void canonicalV2UpgradeRetainsAccountCredentialsAndFailsClosedOnInterruptedOrDamagedStorage() throws Exception {
        for(String damage:List.of("", "UPDATE tk_schema SET state='MIGRATING'", "ALTER TABLE tk_account DROP COLUMN initialized")) {
            try(var connection=DriverManager.getConnection("jdbc:h2:mem:recovery_upgrade_"+UUID.randomUUID());var statement=connection.createStatement()) {
                try(var input=new InputStreamReader(getClass().getResourceAsStream("/db/schema-v1.sql"),java.nio.charset.StandardCharsets.UTF_8)) {RunScript.execute(connection,input);}
                ServerSchema.applyAuth(connection);
                statement.executeUpdate("UPDATE tk_schema SET schema_version=2,state='READY'");
                statement.executeUpdate("INSERT INTO tk_owner VALUES('retained','Europe/Oslo',7,19,'aabb')");
                statement.executeUpdate("INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES('retained','retained','unchanged hash','Retained','ENABLED',TRUE,5)");
                if(!damage.isEmpty()) statement.execute(damage);
                assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
                if(!damage.isEmpty()) {
                    assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection,true));
                    try(var rows=connection.getMetaData().getTables(null,"PUBLIC","TK_RECOVERY_LINK",null)) {assertFalse(rows.next());}
                    continue;
                }
                ServerSchema.initializeOrValidate(connection,true);ServerSchema.initializeOrValidate(connection);ServerSchema.validate(connection);
                try(var rows=statement.executeQuery("SELECT password_hash,state,administrator,version FROM tk_account WHERE owner_id='retained'")) {
                    assertTrue(rows.next());assertEquals("unchanged hash",rows.getString(1));assertEquals("ENABLED",rows.getString(2));assertTrue(rows.getBoolean(3));assertEquals(5,rows.getLong(4));
                }
                try(var rows=statement.executeQuery("SELECT count(*) FROM tk_recovery_address")) {rows.next();assertEquals(0,rows.getInt(1));}
                try(var rows=statement.executeQuery("SELECT origin,epoch FROM tk_recovery_control WHERE id=1")) {rows.next();assertNull(rows.getString(1));assertEquals(0,rows.getLong(2));}
            }
        }
    }
    @Test void interruptedOrNoncanonicalLegacyStorageIsNeverRepairedSilently() throws Exception {
        for(String damage:List.of("UPDATE tk_schema SET state='MIGRATING'","ALTER TABLE tk_owner DROP COLUMN cursor_secret"))
            try(var connection=DriverManager.getConnection("jdbc:h2:mem:auth_invalid_"+UUID.randomUUID());var statement=connection.createStatement()) {
                try(var input=new InputStreamReader(getClass().getResourceAsStream("/db/schema-v1.sql"),java.nio.charset.StandardCharsets.UTF_8)) {RunScript.execute(connection,input);}
                statement.executeUpdate("UPDATE tk_schema SET state='READY'");statement.execute(damage);
                assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
                try(var rows=connection.getMetaData().getTables(null,"PUBLIC","TK_ACCOUNT",null)) {assertFalse(rows.next());}
            }
    }
}
