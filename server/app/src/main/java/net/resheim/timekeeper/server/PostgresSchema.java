package net.resheim.timekeeper.server;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Transactional, serialized bootstrap and canonical validation of PostgreSQL storage. */
public final class PostgresSchema {
    static final long MAINTENANCE_LOCK=92751638246L;
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
        // A ready-schema validation is a reader. Taking the exclusive upgrade
        // lock here would queue live requests behind a replica starting while
        // a long-running snapshot holds its shared restore fence.
        if(validateReadyUnderSharedLock(connection))return;
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement()) {
                // Two server replicas may start against the same new database at once.
                statement.execute("SELECT pg_advisory_xact_lock("+MAINTENANCE_LOCK+")");
                if (shape(connection).isEmpty()) {
                    statement.execute(resource("schema-v1-postgresql.sql"));statement.execute(resource("auth-v2.sql"));statement.execute(resource("recovery-v3.sql"));statement.execute(resource("passkeys-v4.sql"));statement.execute(resource("settings-v5.sql"));statement.execute(resource("locale-v6.sql"));statement.execute(resource("instance-v9.sql"));
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
                try(var statement=connection.createStatement()) {statement.execute(resource("passkeys-v4.sql"));statement.executeUpdate("UPDATE tk_schema SET schema_version=4 WHERE id=1");}
            }
            if(shape(connection).equals(resource("schema-v4-postgresql.shape").lines().toList())) {
                if(!allowUpgrade) throw new SQLException("Schema upgrade requires explicit operator authorization after stopping older server replicas");
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
                    if(!rows.next() || rows.getInt(1)!=1 || rows.getInt(2)!=4 || !"READY".equals(rows.getString(3)) || rows.next()) throw new SQLException("Unsupported passkey PostgreSQL schema");
                }
                try(var statement=connection.createStatement()) {statement.execute(resource("settings-v5.sql"));statement.executeUpdate("UPDATE tk_schema SET schema_version=5 WHERE id=1");}
            }
            if(shape(connection).equals(resource("schema-v5-postgresql.shape").lines().toList())) {
                if(!allowUpgrade) throw new SQLException("Schema upgrade requires explicit operator authorization after stopping older server replicas");
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
                    if(!rows.next() || rows.getInt(1)!=1 || rows.getInt(2)!=5 || !"READY".equals(rows.getString(3)) || rows.next()) throw new SQLException("Unsupported settings PostgreSQL schema");
                }
                try(var statement=connection.createStatement()) {statement.execute(resource("locale-v6.sql"));statement.executeUpdate("UPDATE tk_schema SET schema_version=6 WHERE id=1");}
            }
            if(shape(connection).equals(resource("schema-v8-postgresql.shape").lines().toList())) {
                if(!allowUpgrade)throw new SQLException("Schema upgrade requires explicit operator authorization after stopping older server replicas");
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
                    if(!rows.next()||rows.getInt(1)!=1||rows.getInt(2)<6||rows.getInt(2)>8||!"READY".equals(rows.getString(3))||rows.next())throw new SQLException("Unsupported prior instance schema");
                }
                try(var statement=connection.createStatement()) {statement.execute(resource("instance-v9.sql"));statement.executeUpdate("UPDATE tk_schema SET schema_version="+ServerSchema.CURRENT+" WHERE id=1");}
            }
            if(shape(connection).equals(resource("schema-v10-postgresql.shape").lines().toList())){
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT schema_version,state FROM public.tk_schema WHERE id=1")){
                    if(rows.next()&&rows.getInt(1)==9&&"READY".equals(rows.getString(2))){
                        if(!allowUpgrade)throw new SQLException("Notes schema upgrade requires explicit operator authorization after stopping older server replicas");
                        try(var update=connection.createStatement()){update.executeUpdate("UPDATE tk_schema SET schema_version=10 WHERE id=1 AND schema_version=9 AND state='READY'");}
                    }
                }
            }
            validate(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        } finally { connection.setAutoCommit(true); }
    }

    private static boolean validateReadyUnderSharedLock(Connection connection) throws SQLException {
        boolean held=false;
        try {
            try(var statement=connection.createStatement()){statement.execute("SELECT pg_advisory_lock_shared("+MAINTENANCE_LOCK+")");held=true;}
            boolean exists;
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT to_regclass('public.tk_schema') IS NOT NULL")){exists=rows.next()&&rows.getBoolean(1);}
            if(!exists)return false;
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT schema_version,state FROM public.tk_schema WHERE id=1")) {
                if(!rows.next()||rows.getInt(1)!=ServerSchema.CURRENT||!"READY".equals(rows.getString(2)))return false;
            }
            validate(connection);return true;
        }finally{
            if(held)try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pg_advisory_unlock_shared("+MAINTENANCE_LOCK+")")) {
                if(!rows.next()||!rows.getBoolean(1))throw new SQLException("Schema read fence was lost");
            }
        }
    }
    public static void validate(Connection connection) throws SQLException {
        validateShape(connection);
        ServerSchema.validateInstanceControl(connection);
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
            if (!rows.next() || rows.getInt(1) != 1 || rows.getInt(2) != ServerSchema.CURRENT
                    || !"READY".equals(rows.getString(3)) || rows.next())
                throw new SQLException("Unsupported or incomplete PostgreSQL server schema version");
        }
    }

    private static void validateShape(Connection connection) throws SQLException {
        if (!shape(connection).equals(resource("schema-v10-postgresql.shape").lines().toList()))
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
