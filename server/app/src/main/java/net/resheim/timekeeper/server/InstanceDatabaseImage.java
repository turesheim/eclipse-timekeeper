package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Complete logical database image, including private authentication authority. */
final class InstanceDatabaseImage {
    private InstanceDatabaseImage() { }
    private static final String FORMAT="timekeeper-instance-database-v1";
    // Dependency order is intentional: restore never disables foreign keys.
    // Explicit columns make a newly introduced persisted field fail coverage
    // instead of silently disappearing from disaster-recovery archives.
    private static final List<Table> TABLES=List.of(
        table("tk_schema","id schema_version state"),
        table("tk_instance","id kind version operation_id actor_owner occurred_at payload"),
        table("tk_owner","id zone_id version revision cursor_secret"),
        table("tk_auth_control","id bootstrap_completed"),
        table("tk_recovery_control","id origin epoch"),
        table("tk_service_settings","id version active_version saved effective saved_smtp_password effective_smtp_password"),
        table("tk_resource","owner_id entity_type id version payload"),
        table("tk_account","owner_id username password_hash display_name state administrator initialized version"),
        table("tk_locale","owner_id locale"),
        table("tk_api_token","digest id owner_id name permissions expires_at revoked source created_at last_used_at version legacy_digest"),
        table("tk_passkey_user","owner_id user_handle"),
        table("tk_passkey_credential","id owner_id credential_digest credential_id rp_id public_key signature_count backup_eligible backed_up transports name created_at last_used_at revoked version"),
        table("tk_auth_session","digest owner_id csrf permissions created_at expires_at last_seen_at persistent parent_digest origin_epoch browser_digest passkey_id"),
        table("tk_auth_challenge","digest purpose owner_id expires_at consumed"),
        table("tk_auth_throttle","id attempts window_start"),
        table("tk_recovery_address","owner_id slot verified_address pending_address version"),
        table("tk_recovery_link","digest owner_id slot address purpose origin_epoch created_at expires_at consumed"),
        table("tk_passkey_ceremony","digest browser_digest purpose owner_id session_digest payload expires_at consumed"),
        table("tk_relation","owner_id from_type from_id kind to_type to_id"),
        table("tk_external_reference","owner_id provider_id repository_id external_id entity_type task_id"),
        table("tk_active","owner_id entity_type activity_id"),
        table("tk_receipt","owner_id command_id fingerprint status body etag location created_at"),
        table("tk_change","owner_id sequence entity_type entity_id change_type occurred_at")
    );
    private record Table(String name,List<String> columns) {
        String projection(){return String.join(",",columns);}
    }
    private static Table table(String name,String columns){return new Table(name,List.of(columns.split(" ")));}
    private static final JsonFactory JSON=JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(128*1024*1024).build()).build();

    /** Caller supplies one repeatable-read transaction covering every table. */
    static void write(Connection snapshot,OutputStream output) throws SQLException,IOException {
        if(snapshot.getAutoCommit()||!Set.of(Connection.TRANSACTION_REPEATABLE_READ,Connection.TRANSACTION_SERIALIZABLE).contains(snapshot.getTransactionIsolation()))
            throw new SQLException("Instance snapshot requires repeatable read");
        ServerSchema.validate(snapshot);verifyCoverage(snapshot);
        try(var json=JSON.createGenerator(output).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
            json.writeStartObject();json.writeStringField("format",FORMAT);json.writeNumberField("schema",ServerSchema.CURRENT);json.writeArrayFieldStart("tables");
            for(var table:TABLES) {
                json.writeStartObject();json.writeStringField("name",table.name());json.writeArrayFieldStart("columns");
                for(String column:table.columns())json.writeString(column);json.writeEndArray();json.writeArrayFieldStart("rows");
                String projection=table.projection()+(postgres(snapshot)&&table.name().equals("tk_external_reference")?",reference_key":"");
                try(var statement=snapshot.prepareStatement("SELECT "+projection+" FROM "+table.name()+" ORDER BY "+primaryKey(snapshot,table))) {
                    // A single resource/receipt may contain a large attachment.
                    // Fetching a batch of these can exceed the whole JVM heap.
                    statement.setFetchSize(1);
                    try(var rows=statement.executeQuery()) {
                        var metadata=rows.getMetaData();
                        while(rows.next()) {
                            if(postgres(snapshot)&&table.name().equals("tk_external_reference")&&!referenceKey(rows.getString(2),rows.getString(3),rows.getString(4)).equals(rows.getString(7)))throw new SQLException("Invalid external-reference index");
                            json.writeStartArray();
                            for(int column=1;column<=table.columns().size();column++) {
                                if(Set.of(Types.BOOLEAN,Types.BIT).contains(metadata.getColumnType(column))) {
                                    boolean value=rows.getBoolean(column);if(rows.wasNull())json.writeNull();else json.writeString(Boolean.toString(value));
                                }else if(metadata.getColumnType(column)==Types.TIMESTAMP_WITH_TIMEZONE) {
                                    var value=rows.getObject(column,OffsetDateTime.class);if(value==null)json.writeNull();else json.writeString(value.toInstant().toString());
                                }else try(var reader=rows.getCharacterStream(column)){if(reader==null)json.writeNull();else json.writeString(reader,-1);}
                            }
                            json.writeEndArray();
                        }
                    }
                }
                json.writeEndArray();json.writeEndObject();
            }
            json.writeEndArray();json.writeEndObject();
        }
    }

    /** Check every persisted table and column, including backend-derived indexes. */
    static void verifyCoverage(Connection connection) throws SQLException {
        String schema=postgres(connection)?"public":"PUBLIC";var found=new HashSet<String>();
        try(var tables=connection.getMetaData().getTables(null,schema,"%",new String[]{"TABLE","BASE TABLE"})) {
            while(tables.next())found.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        if(!found.equals(new HashSet<>(TABLES.stream().map(Table::name).toList())))throw new SQLException("Instance backup table coverage is incomplete");
        for(var table:TABLES) {
            var expected=new HashSet<>(table.columns());
            if(postgres(connection)&&table.name().equals("tk_external_reference"))expected.add("reference_key");
            var actual=new HashSet<String>();
            try(var columns=connection.getMetaData().getColumns(null,schema,postgres(connection)?table.name():table.name().toUpperCase(Locale.ROOT),"%")) {
                while(columns.next())actual.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            if(!actual.equals(expected))throw new SQLException("Instance backup column coverage is incomplete");
        }
    }

    /** No mutation: reject malformed images and unsupported schema versions. */
    static Map<String,Long> validate(Path image) throws IOException {
        return read(image,(table,row)->{});
    }

    /** Validate types, uniqueness, foreign keys and checks in disposable storage. */
    static Map<String,Long> preflight(Path image,Path privateDirectory) throws IOException {
        validate(image);Path scratch=Files.createTempDirectory(privateDirectory,".database-check-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            String path=scratch.resolve("validation").toAbsolutePath().toString();if(path.indexOf(';')>=0)throw new IOException();
            try(var connection=DriverManager.getConnection("jdbc:h2:"+path)) {
                ServerSchema.initializeOrValidate(connection);connection.setAutoCommit(false);
                clear(connection);var counts=read(image,(table,row)->insert(connection,table,row));
                ServerSchema.validate(connection);connection.rollback();return counts;
            }
        }catch(Exception failure){throw new IOException("INVALID_INSTANCE_DATABASE");}
        finally {
            try(var files=Files.walk(scratch)) {for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
            catch(IOException failure){throw new IOException("INSTANCE_BACKUP_CLEANUP_FAILED");}
        }
    }

    /**
     * Internal offline restore primitive. The service must establish exclusive
     * maintenance and target readiness first. Never commits the caller's
     * transaction; any failure also rolls back this method's own savepoint.
     */
    static void replace(Connection target,Path image) throws SQLException,IOException {
        if(target.getAutoCommit())throw new SQLException("Instance restore requires a transaction");
        ServerSchema.validate(target);verifyCoverage(target);preflight(image,image.toAbsolutePath().getParent());
        var savepoint=target.setSavepoint();
        try {
            clear(target);
            read(image,(table,row)->insert(target,table,row));
            ServerSchema.validate(target);target.releaseSavepoint(savepoint);
        }catch(SQLException|IOException|RuntimeException failure) {
            try {target.rollback(savepoint);}catch(SQLException rollback){throw new SQLException("Instance restore rollback failed");}
            throw new IOException("INVALID_INSTANCE_DATABASE");
        }
    }
    private static void clear(Connection connection) throws SQLException {
        for(var table:TABLES.reversed())try(var statement=connection.createStatement()){statement.executeUpdate("DELETE FROM "+table.name());}
    }

    @FunctionalInterface private interface Row {void accept(Table table,List<String> values) throws SQLException;}
    private static Map<String,Long> read(Path image,Row consumer) throws IOException {
        var counts=new LinkedHashMap<String,Long>();
        try(var input=Files.newInputStream(image);var json=JSON.createParser(input)) {
            token(json,JsonToken.START_OBJECT);field(json,"format");token(json,JsonToken.VALUE_STRING);if(!FORMAT.equals(json.getText()))throw new IOException();
            field(json,"schema");token(json,JsonToken.VALUE_NUMBER_INT);if(json.getIntValue()!=ServerSchema.CURRENT)throw new IOException();
            field(json,"tables");token(json,JsonToken.START_ARRAY);
            for(var table:TABLES) {
                token(json,JsonToken.START_OBJECT);field(json,"name");token(json,JsonToken.VALUE_STRING);if(!table.name().equals(json.getText()))throw new IOException();
                field(json,"columns");token(json,JsonToken.START_ARRAY);
                for(String column:table.columns()){token(json,JsonToken.VALUE_STRING);if(!column.equals(json.getText()))throw new IOException();}
                token(json,JsonToken.END_ARRAY);field(json,"rows");token(json,JsonToken.START_ARRAY);long count=0;
                while(json.nextToken()!=JsonToken.END_ARRAY) {
                    if(json.currentToken()!=JsonToken.START_ARRAY)throw new IOException();var row=new ArrayList<String>();
                    for(int index=0;index<table.columns().size();index++) {
                        JsonToken value=json.nextToken();if(value!=JsonToken.VALUE_NULL&&value!=JsonToken.VALUE_STRING)throw new IOException();
                        row.add(value==JsonToken.VALUE_NULL?null:json.getText());
                    }
                    token(json,JsonToken.END_ARRAY);
                    if(table.name().equals("tk_schema")&&(!row.equals(List.of("1",Integer.toString(ServerSchema.CURRENT),"READY"))||count!=0))throw new IOException();
                    consumer.accept(table,row);count++;
                }
                if(Set.of("tk_schema","tk_auth_control","tk_recovery_control","tk_service_settings").contains(table.name())&&count!=1)throw new IOException();
                counts.put(table.name(),count);token(json,JsonToken.END_OBJECT);
            }
            token(json,JsonToken.END_ARRAY);token(json,JsonToken.END_OBJECT);if(json.nextToken()!=null)throw new IOException();
            return Collections.unmodifiableMap(counts);
        }catch(IOException|SQLException|RuntimeException failure){throw new IOException("INVALID_INSTANCE_DATABASE");}
    }
    private static void token(JsonParser json,JsonToken expected) throws IOException {if(json.nextToken()!=expected)throw new IOException();}
    private static void field(JsonParser json,String name) throws IOException {token(json,JsonToken.FIELD_NAME);if(!name.equals(json.currentName()))throw new IOException();}
    private static void insert(Connection target,Table table,List<String> row) throws SQLException {
        boolean derived=postgres(target)&&table.name().equals("tk_external_reference");
        String columns=table.projection()+(derived?",reference_key":"");
        try(var shape=target.prepareStatement("SELECT "+columns+" FROM "+table.name()+" WHERE 1=0");var empty=shape.executeQuery()) {
            var metadata=empty.getMetaData();String placeholders=String.join(",",Collections.nCopies(metadata.getColumnCount(),"?"));
            try(var statement=target.prepareStatement("INSERT INTO "+table.name()+"("+columns+") VALUES("+placeholders+")")) {
                for(int index=0;index<row.size();index++)bind(statement,index+1,row.get(index),metadata);
                if(derived)statement.setString(row.size()+1,referenceKey(row.get(1),row.get(2),row.get(3)));
                statement.executeUpdate();
            }
        }
    }
    private static void bind(PreparedStatement statement,int index,String value,ResultSetMetaData metadata) throws SQLException {
        if(value==null){statement.setNull(index,metadata.getColumnType(index));return;}
        switch(metadata.getColumnType(index)) {
            case Types.BOOLEAN,Types.BIT -> {if(!value.equals("true")&&!value.equals("false"))throw new SQLException("Invalid boolean");statement.setBoolean(index,Boolean.parseBoolean(value));}
            case Types.INTEGER -> statement.setInt(index,Integer.parseInt(value));
            case Types.BIGINT -> statement.setLong(index,Long.parseLong(value));
            case Types.TIMESTAMP_WITH_TIMEZONE -> statement.setObject(index,OffsetDateTime.parse(value));
            default -> {if(metadata.getColumnTypeName(index).equalsIgnoreCase("uuid"))statement.setObject(index,UUID.fromString(value));else statement.setString(index,value);}
        }
    }
    private static String primaryKey(Connection connection,Table table) throws SQLException {
        // PostgreSQL indexes a derived hash to bound index width. The portable
        // identity is the complete provider/repository/external tuple.
        if(table.name().equals("tk_external_reference"))return "owner_id,provider_id,repository_id,external_id";
        var columns=new TreeMap<Integer,String>();
        try(var keys=connection.getMetaData().getPrimaryKeys(null,postgres(connection)?"public":"PUBLIC",postgres(connection)?table.name():table.name().toUpperCase(Locale.ROOT))) {
            while(keys.next())columns.put(keys.getInt("KEY_SEQ"),keys.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if(columns.isEmpty()||!table.columns().containsAll(columns.values()))throw new SQLException("Unsupported backup primary key");
        return String.join(",",columns.values());
    }
    private static boolean postgres(Connection connection) throws SQLException {return connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");}
    private static String referenceKey(String provider,String repository,String external){return hash(array().add(provider).add(repository).add(external).toString());}
}
