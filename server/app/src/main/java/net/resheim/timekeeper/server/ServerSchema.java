package net.resheim.timekeeper.server;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

import org.h2.tools.RunScript;

/** Validate the complete canonical server schema before accepting existing storage. */
public final class ServerSchema {
    private ServerSchema() { }
    private static final Set<String> LEGACY_TABLES=Set.of("TK_SCHEMA","TK_OWNER","TK_RESOURCE","TK_RELATION","TK_EXTERNAL_REFERENCE","TK_ACTIVE","TK_RECEIPT","TK_CHANGE");
    public static final int CURRENT=2;
    private static final Set<String> TABLES;
    static {var tables=new HashSet<>(LEGACY_TABLES);tables.addAll(Set.of("TK_ACCOUNT","TK_AUTH_CONTROL","TK_AUTH_CHALLENGE","TK_AUTH_SESSION","TK_API_TOKEN","TK_AUTH_THROTTLE"));TABLES=Set.copyOf(tables);}
    private static class Reference {
        static final List<String> SHAPE=referenceShape();
        private static List<String> referenceShape() {
            try(var connection=DriverManager.getConnection("jdbc:h2:mem:schema_reference_"+UUID.randomUUID())) {
                create(connection);applyAuth(connection);return shape(connection,TABLES);
            } catch(Exception e) {throw new ExceptionInInitializerError(e);}
        }
    }
    private static void create(Connection connection) throws Exception {
        try(var input=ServerSchema.class.getResourceAsStream("/db/schema-v1.sql")) {
            if(input==null) throw new SQLException("Missing server schema resource");
            RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));
        }
    }
    static void applyAuth(Connection connection) throws Exception {
        try(var input=ServerSchema.class.getResourceAsStream("/db/auth-v2.sql")) {
            if(input==null) throw new SQLException("Missing authentication schema resource");
            RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));
        }
    }
    private static List<String> legacyShape() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:legacy_reference_"+UUID.randomUUID())) {create(connection);return shape(connection,LEGACY_TABLES);}
    }
    public static void initializeOrValidate(Connection connection) throws Exception {
        if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            PostgresSchema.initializeOrValidate(connection);
            return;
        }
        if(tables(connection).isEmpty()) {
            create(connection);applyAuth(connection);
            validateShape(connection);
            // H2 commits DDL. Interrupted initialization remains explicitly INITIALIZING.
            try(var statement=connection.createStatement()) {statement.executeUpdate("UPDATE tk_schema SET schema_version=2,state='READY' WHERE id=1");}
        }
        if(tables(connection).equals(LEGACY_TABLES)) {
            if(!shape(connection,LEGACY_TABLES).equals(legacyShape())) throw new SQLException("Unsupported legacy server schema");
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
                if(!rows.next() || rows.getInt(1)!=1 || rows.getInt(2)!=1 || !"READY".equals(rows.getString(3)) || rows.next()) throw new SQLException("Unsupported or incomplete legacy schema");
            }
            // H2 DDL commits: interruption remains MIGRATING and is never accepted as READY.
            try(var statement=connection.createStatement()) {statement.executeUpdate("UPDATE tk_schema SET state='MIGRATING' WHERE id=1");}
            applyAuth(connection);validateShape(connection);
            try(var statement=connection.createStatement()) {statement.executeUpdate("UPDATE tk_schema SET schema_version=2,state='READY' WHERE id=1");}
        }
        validate(connection);
    }
    private static Set<String> tables(Connection connection) throws SQLException {
        Set<String> result=new TreeSet<>();
        try(var schemas=connection.getMetaData().getSchemas()) {
            while(schemas.next()) {String schema=schemas.getString("TABLE_SCHEM");if(!Set.of("PUBLIC","INFORMATION_SCHEMA").contains(schema)) result.add(schema+".$SCHEMA");}
        }
        for(var named:Map.of("SEQUENCES","SEQUENCE_SCHEMA","ROUTINES","ROUTINE_SCHEMA","DOMAINS","DOMAIN_SCHEMA").entrySet()) {
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA."+named.getKey()+" WHERE "+named.getValue()+"<>'INFORMATION_SCHEMA'")) {
                rows.next();if(rows.getLong(1)!=0) result.add("$"+named.getKey());
            }
        }
        try(var rows=connection.getMetaData().getTables(null,null,"%",null)) {
            while(rows.next()) {
                String schema=rows.getString("TABLE_SCHEM");if("INFORMATION_SCHEMA".equals(schema)) continue;
                result.add(("PUBLIC".equals(schema) ? "" : schema+".")+rows.getString("TABLE_NAME"));
            }
        }
        return result;
    }
    public static void validate(Connection connection) throws SQLException {
        if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            PostgresSchema.validate(connection);
            return;
        }
        validateShape(connection);
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
            if(!rows.next() || rows.getInt(1)!=1 || rows.getInt(2)!=CURRENT || !"READY".equals(rows.getString(3)) || rows.next()) throw new SQLException("Unsupported or incomplete server schema version");
        }
    }
    private static void validateShape(Connection connection) throws SQLException {
        if(!tables(connection).equals(TABLES) || !shape(connection,TABLES).equals(Reference.SHAPE)) throw new SQLException("Unsupported or incomplete server schema");
    }
    private static List<String> shape(Connection connection,Set<String> expectedTables) throws SQLException {
        List<String> result=new ArrayList<>();var metadata=connection.getMetaData();
        for(String table:new TreeSet<>(expectedTables)) {
            try(var rows=metadata.getColumns(null,"PUBLIC",table,"%")) {
                while(rows.next()) result.add(table+" COLUMN "+rows.getString("COLUMN_NAME")+" "+rows.getString("TYPE_NAME")+" "+rows.getInt("COLUMN_SIZE")+" "+rows.getInt("DECIMAL_DIGITS")+" "+rows.getInt("NULLABLE")+" "+rows.getString("COLUMN_DEF"));
            }
            try(var rows=metadata.getPrimaryKeys(null,"PUBLIC",table)) {
                while(rows.next()) result.add(table+" PK "+rows.getInt("KEY_SEQ")+" "+rows.getString("COLUMN_NAME"));
            }
            Map<String,SortedMap<Integer,String>> keys=new HashMap<>();
            try(var rows=metadata.getImportedKeys(null,"PUBLIC",table)) {
                while(rows.next()) keys.computeIfAbsent(rows.getString("FK_NAME"),ignored -> new TreeMap<>()).put(rows.getInt("KEY_SEQ"),
                        rows.getString("FKCOLUMN_NAME")+"->"+rows.getString("PKTABLE_SCHEM")+"."+rows.getString("PKTABLE_NAME")+"."+rows.getString("PKCOLUMN_NAME")+" "+rows.getInt("UPDATE_RULE")+" "+rows.getInt("DELETE_RULE")+" "+rows.getInt("DEFERRABILITY"));
            }
            keys.values().forEach(key -> result.add(table+" FK "+key));
            Map<String,SortedMap<Integer,String>> indexes=new HashMap<>();
            try(var rows=metadata.getIndexInfo(null,"PUBLIC",table,false,false)) {
                while(rows.next()) {
                    if(rows.getShort("TYPE")==java.sql.DatabaseMetaData.tableIndexStatistic) continue;
                    indexes.computeIfAbsent(rows.getString("INDEX_NAME"),ignored -> new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"),rows.getString("COLUMN_NAME")+" "+rows.getBoolean("NON_UNIQUE")+" "+rows.getString("ASC_OR_DESC"));
                }
            }
            indexes.values().forEach(index -> result.add(table+" INDEX "+index));
        }
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT t.table_name,c.check_clause FROM information_schema.table_constraints t JOIN information_schema.check_constraints c ON t.constraint_catalog=c.constraint_catalog AND t.constraint_schema=c.constraint_schema AND t.constraint_name=c.constraint_name WHERE t.table_schema='PUBLIC' AND t.constraint_type='CHECK'")) {
            while(rows.next()) result.add(rows.getString(1)+" CHECK "+rows.getString(2));
        }
        Collections.sort(result);return List.copyOf(result);
    }
}
