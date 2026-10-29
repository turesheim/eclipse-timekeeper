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
    public static final int CURRENT=9;
    private static final Set<String> AUTH_TABLES;
    private static final Set<String> RECOVERY_TABLES;
    private static final Set<String> PASSKEY_TABLES;
    private static final Set<String> SETTINGS_TABLES;
    private static final Set<String> LOCALE_TABLES;
    private static final Set<String> TABLES;
    static {
        var tables=new HashSet<>(LEGACY_TABLES);
        tables.addAll(Set.of("TK_ACCOUNT","TK_AUTH_CONTROL","TK_AUTH_CHALLENGE","TK_AUTH_SESSION","TK_API_TOKEN","TK_AUTH_THROTTLE"));
        AUTH_TABLES=Set.copyOf(tables);
        tables.addAll(Set.of("TK_RECOVERY_CONTROL","TK_RECOVERY_ADDRESS","TK_RECOVERY_LINK"));
        RECOVERY_TABLES=Set.copyOf(tables);
        tables.addAll(Set.of("TK_PASSKEY_USER","TK_PASSKEY_CREDENTIAL","TK_PASSKEY_CEREMONY"));
        PASSKEY_TABLES=Set.copyOf(tables);
        tables.add("TK_SERVICE_SETTINGS");
        SETTINGS_TABLES=Set.copyOf(tables);
        tables.add("TK_LOCALE");
        LOCALE_TABLES=Set.copyOf(tables);
        tables.add("TK_INSTANCE");
        TABLES=Set.copyOf(tables);
    }
    private static class Reference {
        static final List<String> SHAPE=referenceShape();
        private static List<String> referenceShape() {
            try(var connection=DriverManager.getConnection("jdbc:h2:mem:schema_reference_"+UUID.randomUUID())) {
                create(connection);applyAuth(connection);applyRecovery(connection);applyPasskeys(connection);applySettings(connection);applyLocale(connection);applyInstance(connection);return shape(connection,TABLES);
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
    static void applyRecovery(Connection connection) throws Exception {
        try(var input=ServerSchema.class.getResourceAsStream("/db/recovery-v3.sql")) {
            if(input==null) throw new SQLException("Missing recovery schema resource");
            RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));
        }
    }
    static void applyPasskeys(Connection connection) throws Exception {
        try(var input=ServerSchema.class.getResourceAsStream("/db/passkeys-v4.sql")) {
            if(input==null) throw new SQLException("Missing passkey schema resource");
            RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));
        }
    }
    static void applySettings(Connection connection) throws Exception {
        try(var input=ServerSchema.class.getResourceAsStream("/db/settings-v5.sql")) {
            if(input==null) throw new SQLException("Missing service settings schema resource");
            RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));
        }
    }
    static void applyLocale(Connection connection) throws Exception {
        try(var input=ServerSchema.class.getResourceAsStream("/db/locale-v6.sql")) {
            if(input==null) throw new SQLException("Missing locale schema resource");
            RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));
        }
    }
    static void applyInstance(Connection connection) throws Exception {
        try(var input=ServerSchema.class.getResourceAsStream("/db/instance-v9.sql")) {
            if(input==null)throw new SQLException("Missing instance-operation schema resource");
            RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));
        }
    }
    private static List<String> priorShape(int version,Set<String> tables) throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:prior_reference_"+UUID.randomUUID())) {
            create(connection);if(version>=2) applyAuth(connection);if(version>=3) applyRecovery(connection);if(version>=4) applyPasskeys(connection);if(version>=5) applySettings(connection);if(version>=6) applyLocale(connection);return shape(connection,tables);
        }
    }
    public static void initializeOrValidate(Connection connection) throws Exception {initializeOrValidate(connection,false);}
    public static void initializeOrValidate(Connection connection,boolean allowUpgrade) throws Exception {
        if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            PostgresSchema.initializeOrValidate(connection,allowUpgrade);
            return;
        }
        var existing=tables(connection);
        if(existing.isEmpty()) {
            create(connection);applyAuth(connection);applyRecovery(connection);applyPasskeys(connection);applySettings(connection);applyLocale(connection);applyInstance(connection);validateShape(connection);
            try(var statement=connection.createStatement()) {statement.executeUpdate("UPDATE tk_schema SET schema_version="+CURRENT+",state='READY' WHERE id=1");}
        } else if(!existing.equals(TABLES)) {
            int version=existing.equals(LEGACY_TABLES) ? 1 : existing.equals(AUTH_TABLES) ? 2 : existing.equals(RECOVERY_TABLES) ? 3 : existing.equals(PASSKEY_TABLES) ? 4 : existing.equals(SETTINGS_TABLES) ? 5 : existing.equals(LOCALE_TABLES) ? 6 : 0;
            if(version==0) throw new SQLException("Unsupported or incomplete server schema");
            if(!allowUpgrade) throw new SQLException("Schema upgrade requires explicit operator authorization after stopping older server replicas");
            if(!shape(connection,existing).equals(priorShape(version,existing))) throw new SQLException("Unsupported prior server schema");
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
                if(!rows.next() || rows.getInt(1)!=1 || (version==6 ? rows.getInt(2)<6||rows.getInt(2)>8 : rows.getInt(2)!=version) || !("READY".equals(rows.getString(3))||version==6&&"MIGRATING".equals(rows.getString(3))) || rows.next()) throw new SQLException("Unsupported or incomplete prior schema");
            }
            // H2 commits DDL. An interruption remains explicitly MIGRATING.
            try(var statement=connection.createStatement()) {statement.executeUpdate("UPDATE tk_schema SET state='MIGRATING' WHERE id=1");}
            if(version<2) applyAuth(connection);if(version<3) applyRecovery(connection);if(version<4) applyPasskeys(connection);if(version<5) applySettings(connection);if(version<6) applyLocale(connection);applyInstance(connection);validateShape(connection);
            try(var statement=connection.createStatement()) {statement.executeUpdate("UPDATE tk_schema SET schema_version="+CURRENT+",state='READY' WHERE id=1");}
        }
        validateShape(connection);
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT schema_version,state FROM tk_schema WHERE id=1")) {
            if(rows.next()&&rows.getInt(1)<CURRENT&&"MIGRATING".equals(rows.getString(2))) {
                if(!allowUpgrade)throw new SQLException("Schema upgrade requires explicit operator authorization");
                // CREATE TABLE commits in H2. A crash after DDL but before its
                // seed INSERT can resume only from the exact new table shape.
                if(rows.getInt(1)<1||rows.getInt(1)>8)throw new SQLException("Unsupported interrupted schema upgrade");
                try(var update=connection.createStatement()) {
                    update.executeUpdate("INSERT INTO tk_instance SELECT '00000000-0000-0000-0000-000000000000','CONTROL',1,NULL,NULL,'1970-01-01T00:00:00Z','{}' WHERE NOT EXISTS(SELECT 1 FROM tk_instance WHERE kind='CONTROL')");
                    update.executeUpdate("UPDATE tk_schema SET schema_version="+CURRENT+",state='READY' WHERE id=1");
                }
            }
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
        validateInstanceControl(connection);
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,schema_version,state FROM tk_schema")) {
            if(!rows.next() || rows.getInt(1)!=1 || rows.getInt(2)!=CURRENT || !"READY".equals(rows.getString(3)) || rows.next()) throw new SQLException("Unsupported or incomplete server schema version");
        }
    }
    static void validateInstanceControl(Connection connection) throws SQLException {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,version FROM tk_instance WHERE kind='CONTROL'")) {
            if(!rows.next()||!rows.getString(1).equals("00000000-0000-0000-0000-000000000000")||rows.getLong(2)<1||rows.next())throw new SQLException("Missing instance-operation control");
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
