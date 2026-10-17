package net.resheim.timekeeper.server;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Transactional, serialized bootstrap and canonical validation of PostgreSQL storage. */
public final class PostgresSchema {
    private PostgresSchema() { }

    private static String resource(String name) throws SQLException {
        try (var input = PostgresSchema.class.getResourceAsStream("/db/" + name)) {
            if (input == null) throw new SQLException("Missing PostgreSQL schema resource");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new SQLException("Cannot read PostgreSQL schema resource", e); }
    }

    public static void initializeOrValidate(Connection connection) throws SQLException {initializeOrValidate(connection,false);}
    public static void initializeOrValidate(Connection connection,boolean allowUpgrade) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Schema bootstrap requires its own transaction");
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement()) {
                // Two server replicas may start against the same new database at once.
                statement.execute("SELECT pg_advisory_xact_lock(92751638246)");
                if (shape(connection).isEmpty()) {
                    statement.execute(resource("schema-v1-postgresql.sql"));statement.execute(resource("auth-v2.sql"));statement.execute(resource("recovery-v3.sql"));statement.execute(resource("passkeys-v4.sql"));
                    validateShape(connection);
                    statement.executeUpdate("UPDATE tk_schema SET schema_version="+ServerSchema.CURRENT+",state='READY' WHERE id=1");
                }
            }
            if(shape(connection).equals(resource("schema-v1-postgresql.shape").lines().toList())) {
                if(!allowUpgrade) throw new SQLException("Schema upgrade requires explicit operator authorization after stopping older server replicas");
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
                    if(!rows.next() || rows.getInt(1)!=1 || rows.getInt(2)!=1 || !"READY".equals(rows.getString(3)) || rows.next()) throw new SQLException("Unsupported legacy PostgreSQL schema");
                }
                try(var statement=connection.createStatement()) {statement.execute(resource("auth-v2.sql"));statement.executeUpdate("UPDATE tk_schema SET schema_version=2 WHERE id=1");}
            }
            if(shape(connection).equals(resource("schema-v2-postgresql.shape").lines().toList())) {
                if(!allowUpgrade) throw new SQLException("Schema upgrade requires explicit operator authorization after stopping older server replicas");
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
                    if(!rows.next() || rows.getInt(1)!=1 || rows.getInt(2)!=2 || !"READY".equals(rows.getString(3)) || rows.next()) throw new SQLException("Unsupported authentication PostgreSQL schema");
                }
                try(var statement=connection.createStatement()) {
                    statement.execute(resource("recovery-v3.sql"));
                    statement.executeUpdate("UPDATE tk_schema SET schema_version=3 WHERE id=1");
                }
            }
            if(shape(connection).equals(resource("schema-v3-postgresql.shape").lines().toList())) {
                if(!allowUpgrade) throw new SQLException("Schema upgrade requires explicit operator authorization after stopping older server replicas");
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
                    if(!rows.next() || rows.getInt(1)!=1 || rows.getInt(2)!=3 || !"READY".equals(rows.getString(3)) || rows.next()) throw new SQLException("Unsupported recovery PostgreSQL schema");
                }
                try(var statement=connection.createStatement()) {statement.execute(resource("passkeys-v4.sql"));statement.executeUpdate("UPDATE tk_schema SET schema_version="+ServerSchema.CURRENT+" WHERE id=1");}
            }
            validate(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        } finally { connection.setAutoCommit(true); }
    }

    public static void validate(Connection connection) throws SQLException {
        validateShape(connection);
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
            if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != ServerSchema.CURRENT
                    || !"READY".equals(rows.getString(3)) || rows.next())
                throw new SQLException("Unsupported or incomplete PostgreSQL server schema version");
        }
    }

    private static void validateShape(Connection connection) throws SQLException {
        if (!shape(connection).equals(resource("schema-v4-postgresql.shape").lines().toList()))
            throw new SQLException("Unsupported or incomplete PostgreSQL server schema");
    }

    static List<String> shape(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(resource("postgresql-shape.sql"))) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return List.copyOf(result);
    }
}
