package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Explicit integration suite: -Dtest=PostgresSchemaIT with an isolated PostgreSQL 18 server. */
class PostgresSchemaIT {
    private String database;
    private Connection admin() throws SQLException {
        return DriverManager.getConnection(System.getenv("TIMEKEEPER_TEST_PG_URL"),
                System.getenv("TIMEKEEPER_TEST_PG_USER"), System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
    }
    private Connection connection() throws SQLException {
        String url = System.getenv("TIMEKEEPER_TEST_PG_URL");
        return DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+database,
                System.getenv("TIMEKEEPER_TEST_PG_USER"), System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
    }
    @BeforeEach void createDatabase() throws Exception {
        database = "tk_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection=admin(); var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
    }
    @AfterEach void dropDatabase() throws Exception {
        try (var connection=admin(); var statement=connection.createStatement()) {
            statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }
    @Test void concurrentBootstrapAndReopenPreserveTheCanonicalSchema() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {try(var connection=connection()) {ServerSchema.initializeOrValidate(connection);}return true;});
            var second = pool.submit(() -> {try(var connection=connection()) {ServerSchema.initializeOrValidate(connection);}return true;});
            assertTrue(first.get()); assertTrue(second.get());
        }
        try (var connection=connection()) {
            var before=PostgresSchema.shape(connection);
            ServerSchema.initializeOrValidate(connection);
            assertEquals(before,PostgresSchema.shape(connection));
            ServerSchema.validate(connection);
        }
    }
    @Test void notesLogicalUpgradeRequiresAuthorizationAndLeavesPrivateRowsUntouched() throws Exception {
        try(var connection=connection();var statement=connection.createStatement()){
            ServerSchema.initializeOrValidate(connection);statement.executeUpdate("UPDATE tk_schema SET schema_version=9 WHERE id=1");
            statement.executeUpdate("INSERT INTO tk_owner VALUES('notes-owner','UTC',0,0,'aabb')");UUID id=UUID.randomUUID();
            statement.executeUpdate("INSERT INTO tk_resource VALUES('notes-owner','DAY_NOTE','"+id+"',1,'{\"body\":\"private source\"}')");
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
            try(var rows=statement.executeQuery("SELECT schema_version FROM tk_schema WHERE id=1")){assertTrue(rows.next());assertEquals(9,rows.getInt(1));}
            ServerSchema.initializeOrValidate(connection,true);ServerSchema.validate(connection);
            try(var rows=statement.executeQuery("SELECT payload FROM tk_resource WHERE owner_id='notes-owner' AND id='"+id+"'")){assertTrue(rows.next());assertEquals("{\"body\":\"private source\"}",rows.getString(1));}
        }
    }
    @Test void upgradesCanonicalV1WithoutChangingPrivateData() throws Exception {
        try(var connection=connection();var statement=connection.createStatement()) {
            try(var input=getClass().getResourceAsStream("/db/schema-v1-postgresql.sql")) {statement.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            statement.executeUpdate("UPDATE tk_schema SET state='READY'");
            statement.executeUpdate("INSERT INTO tk_owner VALUES('existing-owner','Europe/Oslo',7,19,'aabb')");
            var id=UUID.randomUUID();String payload="{\"start\":\"2025-10-20T08:00:00.123456789Z\",\"ownerId\":\"existing-owner\"}";
            try(var insert=connection.prepareStatement("INSERT INTO tk_resource VALUES('existing-owner','WORK_SESSION',?,8,?)")) {insert.setObject(1,id);insert.setString(2,payload);insert.executeUpdate();}
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
            ServerSchema.initializeOrValidate(connection,true);ServerSchema.validate(connection);ServerSchema.initializeOrValidate(connection);
            try(var rows=statement.executeQuery("SELECT id,version,payload FROM tk_resource")) {assertTrue(rows.next());assertEquals(id,rows.getObject(1,UUID.class));assertEquals(8,rows.getLong(2));assertEquals(payload,rows.getString(3));assertFalse(rows.next());}
            try(var rows=statement.executeQuery("SELECT schema_version,state FROM tk_schema")) {rows.next();assertEquals(ServerSchema.CURRENT,rows.getInt(1));assertEquals("READY",rows.getString(2));}
            try(var rows=statement.executeQuery("SELECT version,revision FROM tk_owner")) {rows.next();assertEquals(7,rows.getLong(1));assertEquals(19,rows.getLong(2));}
        }
    }
    @Test void upgradesCanonicalV2RetainsAccountsAndEndsSessionsWithoutOriginProvenance() throws Exception {
        try(var connection=connection();var statement=connection.createStatement()) {
            for(String resource:java.util.List.of("schema-v1-postgresql.sql","auth-v2.sql")) {
                try(var input=getClass().getResourceAsStream("/db/"+resource)) {statement.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            }
            statement.executeUpdate("UPDATE tk_schema SET schema_version=2,state='READY'");
            statement.executeUpdate("INSERT INTO tk_owner VALUES('retained','Europe/Oslo',7,19,'aabb')");
            statement.executeUpdate("INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES('retained','retained','password hash','Retained','ENABLED',TRUE,5)");
            statement.executeUpdate("INSERT INTO tk_auth_session VALUES('session hash','retained','csrf','READ_WRITE','2026-01-01T00:00:00Z','2027-01-01T00:00:00Z','2026-01-01T00:00:00Z',TRUE,NULL)");
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
            ServerSchema.initializeOrValidate(connection,true);ServerSchema.initializeOrValidate(connection);ServerSchema.validate(connection);
            try(var rows=statement.executeQuery("SELECT password_hash,version FROM tk_account WHERE owner_id='retained'")) {assertTrue(rows.next());assertEquals("password hash",rows.getString(1));assertEquals(5,rows.getLong(2));}
            try(var rows=statement.executeQuery("SELECT count(*) FROM tk_auth_session")) {assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        }
    }
    @Test void upgradesCanonicalV3RetainsRecoveryOriginAndAddressesAndRequiresExplicitOptIn() throws Exception {
        try(var connection=connection();var statement=connection.createStatement()) {
            for(String resource:java.util.List.of("schema-v1-postgresql.sql","auth-v2.sql","recovery-v3.sql")) {
                try(var input=getClass().getResourceAsStream("/db/"+resource)) {statement.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            }
            statement.executeUpdate("UPDATE tk_schema SET schema_version=3,state='READY'");
            statement.executeUpdate("INSERT INTO tk_owner VALUES('retained','Europe/Oslo',7,19,'aabb')");
            statement.executeUpdate("INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES('retained','retained','password hash','Retained','ENABLED',TRUE,5)");
            statement.executeUpdate("INSERT INTO tk_recovery_address VALUES('retained','BACKUP','retained@example.invalid','pending@example.invalid',9)");
            statement.executeUpdate("UPDATE tk_recovery_control SET origin='https://retained.example.invalid:8443',epoch=7");
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
            ServerSchema.initializeOrValidate(connection,true);ServerSchema.initializeOrValidate(connection);ServerSchema.validate(connection);
            try(var rows=statement.executeQuery("SELECT verified_address,pending_address,version FROM tk_recovery_address")) {assertTrue(rows.next());assertEquals("retained@example.invalid",rows.getString(1));assertEquals("pending@example.invalid",rows.getString(2));assertEquals(9,rows.getLong(3));}
            try(var rows=statement.executeQuery("SELECT origin,epoch FROM tk_recovery_control")) {assertTrue(rows.next());assertEquals("https://retained.example.invalid:8443",rows.getString(1));assertEquals(7,rows.getLong(2));}
        }
    }
    @Test void upgradesCanonicalV5WithBrowserDefaultAndRetainsStoredLocaleOnReopen() throws Exception {
        try(var connection=connection();var statement=connection.createStatement()) {
            for(String resource:java.util.List.of("schema-v1-postgresql.sql","auth-v2.sql","recovery-v3.sql","passkeys-v4.sql","settings-v5.sql")) {
                try(var input=getClass().getResourceAsStream("/db/"+resource)) {statement.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            }
            statement.executeUpdate("UPDATE tk_schema SET schema_version=5,state='READY'");
            statement.executeUpdate("INSERT INTO tk_owner VALUES('retained','Europe/Oslo',7,19,'aabb')");
            var before=PostgresSchema.shape(connection);
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
            assertEquals(before,PostgresSchema.shape(connection));
            ServerSchema.initializeOrValidate(connection,true);ServerSchema.validate(connection);
            try(var rows=statement.executeQuery("SELECT count(*) FROM tk_locale")) {rows.next();assertEquals(0,rows.getInt(1));}
            try(var rows=statement.executeQuery("SELECT zone_id,version,revision FROM tk_owner WHERE id='retained'")) {
                assertTrue(rows.next());assertEquals("Europe/Oslo",rows.getString(1));assertEquals(7,rows.getLong(2));assertEquals(19,rows.getLong(3));
            }
            statement.executeUpdate("INSERT INTO tk_locale VALUES('retained','nb-NO')");
        }
        try(var connection=connection();var statement=connection.createStatement()) {
            ServerSchema.initializeOrValidate(connection);
            try(var rows=statement.executeQuery("SELECT locale FROM tk_locale WHERE owner_id='retained'")) {assertTrue(rows.next());assertEquals("nb-NO",rows.getString(1));}
        }
    }
    @Test void checkConstraintsRemainCanonicalAfterLogicalDumpRestoreDeparsing() throws Exception {
        try(var connection=connection();var statement=connection.createStatement()) {
            ServerSchema.initializeOrValidate(connection);var before=PostgresSchema.shape(connection);
            var checks=new java.util.ArrayList<String[]>();
            try(var rows=statement.executeQuery("SELECT c.relname,k.conname,pg_get_constraintdef(k.oid) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND k.contype='c'")) {
                while(rows.next()) checks.add(new String[]{rows.getString(1),rows.getString(2),rows.getString(3)});
            }
            for(var check:checks) {
                assertTrue(check[0].matches("[a-z_]+") && check[1].matches("[a-z_0-9]+"));
                statement.execute("ALTER TABLE "+check[0]+" DROP CONSTRAINT "+check[1]);
                statement.execute("ALTER TABLE "+check[0]+" ADD CONSTRAINT "+check[1]+" "+check[2]);
            }
            assertEquals(before,PostgresSchema.shape(connection));ServerSchema.validate(connection);
        }
    }
    @ParameterizedTest @ValueSource(strings={
        "UPDATE tk_schema SET state='INITIALIZING'", "UPDATE tk_schema SET schema_version=99",
        "DELETE FROM tk_schema", "DROP TABLE tk_receipt", "DROP TABLE tk_relation",
        "ALTER TABLE tk_owner ALTER COLUMN zone_id TYPE VARCHAR(1)",
        "CREATE TABLE unexpected(id INT)", "CREATE VIEW extra_view AS SELECT 1 id",
        "CREATE SCHEMA extra;CREATE TABLE extra.hidden(id INT)", "CREATE SCHEMA empty_schema",
        "CREATE SEQUENCE extra_sequence", "CREATE DOMAIN extra_domain AS VARCHAR",
        "ALTER TABLE tk_relation DROP CONSTRAINT tk_relation_owner_id_from_type_from_id_fkey"
    }) void refusesDamagedSchemasWithoutRepair(String damage) throws Exception {
        try (var connection=connection(); var statement=connection.createStatement()) {
            ServerSchema.initializeOrValidate(connection);
            statement.execute(damage);
            var before=PostgresSchema.shape(connection);
            assertThrows(SQLException.class,() -> ServerSchema.initializeOrValidate(connection));
            assertEquals(before,PostgresSchema.shape(connection));
        }
    }
    @Test void refusesAnUnversionedDatabaseWithoutAddingTables() throws Exception {
        try (var connection=connection(); var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE project(id UUID PRIMARY KEY, name TEXT)");
            var before=PostgresSchema.shape(connection);
            assertThrows(SQLException.class,() -> ServerSchema.initializeOrValidate(connection));
            assertEquals(before,PostgresSchema.shape(connection));
        }
    }
}
