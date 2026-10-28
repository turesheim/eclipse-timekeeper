package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.query;
import static net.resheim.timekeeper.server.JsonValues.MAPPER;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;

/** Runtime generation and maintenance gate. Offline activation owns the exclusive side. */
@ApplicationScoped
class InstanceRestoreFence {
    static final long LOCK=PostgresSchema.MAINTENANCE_LOCK;
    private static final UUID CONTROL=new UUID(0,0);
    private static final ObjectReader READER=MAPPER.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private volatile boolean initialized;
    private String generation;
    private record State(String generation,boolean pending) { }

    /** Called after canonical schema bootstrap, before any account/service initialization. */
    synchronized void initialize(Connection connection) throws SQLException {
        try(var held=shared(connection)) {
            var state=read(connection);if(state.pending())throw unavailable("INSTANCE_RESTORE_PENDING");
            if(initialized&&!Objects.equals(generation,state.generation()))throw unavailable("INSTANCE_RESTART_REQUIRED");
            attest(connection,state.generation());generation=state.generation();initialized=true;
        }
    }
    private static void attest(Connection connection,String generation) throws SQLException {
        try(var statement=query(connection,"SELECT payload FROM tk_instance WHERE id=?",CONTROL);var rows=statement.executeQuery()) {
            if(!rows.next())throw unavailable("INSTANCE_FENCE_UNAVAILABLE");var control=READER.readTree(rows.getString(1));
            if(!control.path("restore").path("phase").asText().equals("COMPLETE"))return;
            var config=org.eclipse.microprofile.config.ConfigProvider.getConfig();String root=config.getOptionalValue("timekeeper.instance-restore.storage",String.class).orElse("NONE");
            if(root.equals("NONE")||!config.getOptionalValue("timekeeper.instance-restore.generation",String.class).orElse("").equals(generation))throw unavailable("INSTANCE_RESTORE_MATERIAL_REQUIRED");
            var material=InstanceRestoreMaterial.active(java.nio.file.Path.of(root));
            if(!material.generation().toString().equals(generation)||!material.operation().toString().equals(control.path("restore").path("operationId").asText())||!material.digest().equals(control.path("restore").path("materialSha256").asText()))throw unavailable("INSTANCE_RESTORE_MATERIAL_REQUIRED");
        }catch(ApiFailure failure){throw failure;}catch(Exception failure){throw unavailable("INSTANCE_RESTORE_MATERIAL_REQUIRED");}
    }
    /** Acquire before beginning the body transaction, including repeatable-read snapshots. */
    Guard enter(Connection connection) throws SQLException {
        if(!initialized)throw unavailable("INSTANCE_FENCE_UNAVAILABLE");
        var held=shared(connection);boolean success=false;
        try {
            var state=read(connection);
            if(!Objects.equals(generation,state.generation()))throw unavailable("INSTANCE_RESTART_REQUIRED");
            if(state.pending())throw unavailable("INSTANCE_RESTORE_PENDING");
            success=true;return held;
        }finally{if(!success)held.close();}
    }
    private static State read(Connection connection) throws SQLException {
        try(var statement=query(connection,"SELECT payload FROM tk_instance WHERE id=? AND kind='CONTROL' AND CHAR_LENGTH(payload)<=65536",CONTROL);var rows=statement.executeQuery()) {
            if(!rows.next())throw unavailable("INSTANCE_FENCE_UNAVAILABLE");JsonNode value=READER.readTree(rows.getString(1));
            if(!value.isObject())throw unavailable("INSTANCE_FENCE_UNAVAILABLE");String generation=null;
            if(value.has("restoreGeneration")) {
                var node=value.get("restoreGeneration");if(!node.isTextual())throw unavailable("INSTANCE_FENCE_UNAVAILABLE");generation=node.asText();
                try{if(!UUID.fromString(generation).toString().equals(generation))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw unavailable("INSTANCE_FENCE_UNAVAILABLE");}
            }
            if(value.has("restorePending")&&!value.path("restorePending").isBoolean())throw unavailable("INSTANCE_FENCE_UNAVAILABLE");
            return new State(generation,value.path("restorePending").asBoolean());
        }catch(ApiFailure failure){throw failure;}
        catch(java.io.IOException|RuntimeException invalid){throw unavailable("INSTANCE_FENCE_UNAVAILABLE");}
    }
    private static Guard shared(Connection connection) throws SQLException {
        if(!connection.getAutoCommit())throw new SQLException("Acquire restore fence before starting a transaction");
        boolean postgres=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        if(postgres)try(var statement=connection.createStatement()){
            statement.setQueryTimeout(5);statement.execute("SELECT pg_advisory_lock_shared("+LOCK+")");
        }catch(SQLException failure){
            // A cancellation can race a successful session-lock acquisition.
            // Discard the physical session so no lock can return to the pool.
            discard(connection);throw unavailable("57014".equals(failure.getSQLState())?"INSTANCE_MAINTENANCE_ACTIVE":"INSTANCE_FENCE_UNAVAILABLE");
        }
        return new Guard(connection,postgres);
    }
    /** Fresh offline JDBC connection only; refuses other connected database clients. */
    static Exclusive exclusive(Connection connection) throws SQLException {
        if(!connection.getAutoCommit())throw new SQLException("Offline restore requires a fresh connection");
        boolean postgres=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"),held=false;
        try {
            if(postgres)try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pg_try_advisory_lock("+LOCK+")")) {
                if(!rows.next()||!rows.getBoolean(1))throw unavailable("INSTANCE_OTHER_DATABASE_CLIENTS");held=true;
            }else{try(var statement=connection.createStatement()){statement.execute("SET EXCLUSIVE 1");}held=true;}
            String sql=postgres?"SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()":"SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID<>SESSION_ID()";
            try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)) {
                if(!rows.next()||rows.getLong(1)!=0)throw unavailable("INSTANCE_OTHER_DATABASE_CLIENTS");
            }
            // The offline helper must acknowledge durable commits even when the
            // deployment normally uses asynchronous/default H2 writes.
            try(var statement=connection.createStatement()){statement.execute(postgres?"SET synchronous_commit=on":"SET WRITE_DELAY 0");}
            return new Exclusive(connection,postgres);
        }catch(SQLException|RuntimeException failure){if(held)new Exclusive(connection,postgres).close();throw failure;}
    }
    static final class Exclusive implements AutoCloseable {
        private final Connection connection;private final boolean postgres;private boolean closed;
        private Exclusive(Connection connection,boolean postgres){this.connection=connection;this.postgres=postgres;}
        void require(Connection selected) throws SQLException {if(closed||connection!=selected||connection.isClosed())throw new SQLException("Offline restore requires its active exclusive connection");}
        @Override public void close() throws SQLException {
            if(closed)return;closed=true;
            try {
                // SET EXCLUSIVE commits an open transaction. Never let release
                // accidentally publish an unfinished restore.
                if(!connection.getAutoCommit()){connection.rollback();connection.setAutoCommit(true);}
                if(postgres)try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pg_advisory_unlock("+LOCK+")")){if(!rows.next()||!rows.getBoolean(1))throw new SQLException();}
                else try(var statement=connection.createStatement()){statement.execute("SET EXCLUSIVE 0");}
            }catch(SQLException failure){discard(connection);throw new SQLException("Offline restore fence release failed","08006");}
        }
    }
    static final class Guard implements AutoCloseable {
        private final Connection connection;private final boolean postgres;private boolean closed;
        private Guard(Connection connection,boolean postgres){this.connection=connection;this.postgres=postgres;}
        @Override public void close() throws SQLException {
            if(closed)return;closed=true;
            if(postgres)try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pg_advisory_unlock_shared("+LOCK+")")) {
                if(!rows.next()||!rows.getBoolean(1))throw new SQLException("Restore fence lock was lost");
            }catch(SQLException failure){discard(connection);throw new SQLException("Restore fence release failed","08006");}
        }
    }
    private static void discard(Connection connection){
        try{connection.abort(Runnable::run);}catch(Exception ignored){/* Close also handles JDBC implementations without abort. */}
        finally{try{connection.close();}catch(SQLException ignored){/* A broken physical session must not be reused. */}}
    }
    static boolean expected(ApiFailure failure){return Set.of("INSTANCE_RESTORE_PENDING","INSTANCE_RESTART_REQUIRED","INSTANCE_MAINTENANCE_ACTIVE").contains(failure.code);}
    private static ApiFailure unavailable(String code){return new ApiFailure(503,code);}
}
