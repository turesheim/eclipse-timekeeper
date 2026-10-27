package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerSchemaTest {
    @TempDir Path directory;
    String url() {return "jdbc:h2:"+directory.resolve("server");}
    @ParameterizedTest @ValueSource(strings={"jdbc:h2:mem:","jdbc:h2:file:","jdbc:other:db", "jdbc:h2:mem:db;INIT=CREATE TABLE dangerous(id INT)", "jdbc:h2:mem:db;WRITE_DELAY=500", "jdbc:h2:mem:db;ACCESS_MODE_DATA=r",
        "jdbc:postgresql://db:5432/", "jdbc:postgresql://db:5432/server?user=postgres", "jdbc:postgresql://db:5432/server;INIT=SQL"})
    void rejectsUnsafeStartupSettingsBeforeOpeningStorage(String url) {
        assertThrows(IllegalArgumentException.class,() -> DatabaseConfiguration.validate(url));
    }
    @Test void initializesOnlyEmptyStorageAndReopensReadySchema() throws Exception {
        try(var connection=DriverManager.getConnection(url())) {
            ServerSchema.initializeOrValidate(connection);var before=snapshot(connection);ServerSchema.initializeOrValidate(connection);assertEquals(before,snapshot(connection));
        }
        try(var connection=DriverManager.getConnection(url()+";IFEXISTS=TRUE")) {ServerSchema.validate(connection);}
    }
    @Test void logicalLabelModelUpgradeRequiresOfflineAuthorization() throws Exception {
        try(var connection=DriverManager.getConnection(url())){
            ServerSchema.initializeOrValidate(connection);
            try(var statement=connection.createStatement()){statement.executeUpdate("UPDATE tk_schema SET schema_version=6 WHERE id=1");}
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
            ServerSchema.initializeOrValidate(connection,true);ServerSchema.validate(connection);
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT schema_version FROM tk_schema WHERE id=1")){rows.next();assertEquals(7,rows.getInt(1));}
        }
    }
    @ParameterizedTest @ValueSource(strings={
        "UPDATE tk_schema SET state='INITIALIZING'", "UPDATE tk_schema SET schema_version=99",
        "DELETE FROM tk_schema", "DROP TABLE tk_receipt", "DROP TABLE tk_relation",
        "ALTER TABLE tk_owner ALTER COLUMN zone_id VARCHAR(1)",
        "CREATE TABLE unexpected(id INT)", "CREATE VIEW extra_view AS SELECT 1 id",
        "CREATE SCHEMA extra;CREATE TABLE extra.hidden(id INT)", "CREATE SCHEMA empty_schema",
        "CREATE SEQUENCE extra_sequence", "CREATE DOMAIN extra_domain AS VARCHAR"
    }) void refusesDamagedAndUnsupportedSchemasWithoutWriting(String damage) throws Exception {
        try(var connection=DriverManager.getConnection(url())) {
            ServerSchema.initializeOrValidate(connection);
            try(var statement=connection.createStatement()) {statement.execute(damage);}
            var before=snapshot(connection);assertThrows(SQLException.class,() -> ServerSchema.initializeOrValidate(connection));assertEquals(before,snapshot(connection));
        }
    }
    @Test void refusesUnversionedEmbeddedStorageWithoutChangingIt() throws Exception {
        try(var connection=DriverManager.getConnection(url());var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE PROJECT(id UUID PRIMARY KEY,name VARCHAR)");statement.execute("INSERT INTO PROJECT VALUES(RANDOM_UUID(),'Preserve')");
            var before=snapshot(connection);assertThrows(SQLException.class,() -> ServerSchema.initializeOrValidate(connection));assertEquals(before,snapshot(connection));
        }
    }
    @Test void refusesMissingOwnershipForeignKey() throws Exception {
        try(var connection=DriverManager.getConnection(url());var statement=connection.createStatement()) {
            ServerSchema.initializeOrValidate(connection);
            String constraint;
            try(var rows=statement.executeQuery("SELECT constraint_name FROM information_schema.table_constraints WHERE table_name='TK_RELATION' AND constraint_type='FOREIGN KEY' FETCH FIRST 1 ROW ONLY")) {assertTrue(rows.next());constraint=rows.getString(1);}
            statement.execute("ALTER TABLE tk_relation DROP CONSTRAINT \""+constraint+"\"");
            var before=snapshot(connection);assertThrows(SQLException.class,() -> ServerSchema.initializeOrValidate(connection));assertEquals(before,snapshot(connection));
        }
    }
    private List<String> snapshot(Connection connection) throws SQLException {
        List<String> result=new ArrayList<>();try(var statement=connection.createStatement();var rows=statement.executeQuery("SCRIPT NOPASSWORDS")) {while(rows.next()) result.add(rows.getString(1));}return result;
    }
}
