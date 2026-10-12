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
