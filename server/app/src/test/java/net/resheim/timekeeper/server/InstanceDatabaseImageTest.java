package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.execute;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceDatabaseImageTest {
    @TempDir Path directory;
    private static final String TASK="11111111-1111-1111-1111-111111111111",ACTIVITY="22222222-2222-2222-2222-222222222222",TOKEN="33333333-3333-3333-3333-333333333333",PASSKEY="44444444-4444-4444-4444-444444444444";
    private static final String NOW="2026-10-28T04:15:16.123456789Z";
    static Connection database() throws Exception {
        var connection=DriverManager.getConnection("jdbc:h2:mem:instance_"+UUID.randomUUID());ServerSchema.initializeOrValidate(connection);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);connection.setAutoCommit(false);return connection;
    }
    private Path snapshot(Connection connection,String name) throws Exception {
        Path image=directory.resolve(name);try(var output=Files.newOutputStream(image)){InstanceDatabaseImage.write(connection,output);}return image;
    }
    static void seed(Connection c) throws SQLException {
        var operation=InstanceOperations.begin(c,"former-administrator",InstanceOperations.Type.BACKUP,java.time.Instant.parse(NOW));
        InstanceOperations.advance(c,operation,InstanceOperations.State.PREPARING,InstanceOperations.State.READY,java.time.Instant.parse(NOW));
        boolean postgres=c.getMetaData().getDatabaseProductName().equals("PostgreSQL");Object timestamp=postgres?NOW:OffsetDateTime.parse(NOW);
        for(String owner:List.of("a","b")) {
            execute(c,"INSERT INTO tk_owner VALUES(?,'Europe/Oslo',7,9,?)",owner,"cursor-secret-"+owner);
            execute(c,"INSERT INTO tk_account VALUES(?,?,? ,?,'ENABLED',TRUE,TRUE,11)",owner,"user-"+owner,"private-password-hash-"+owner,"Owner "+owner);
        }
        execute(c,"UPDATE tk_auth_control SET bootstrap_completed=TRUE");
        execute(c,"UPDATE tk_recovery_control SET origin='https://source.example',epoch=3");
        execute(c,"INSERT INTO tk_service_settings VALUES(1,4,3,?,?,?,?)","{\"saved\":true}","{\"effective\":true}","private SMTP password","previous SMTP password");
        execute(c,"INSERT INTO tk_resource VALUES('a','TASK',?,17,?)",UUID.fromString(TASK),"{\"title\":\"Oppgave 😀\",\"binary\":\"AAECA//+\",\"body\":\"# Exact  \\r\\n\"}");
        execute(c,"INSERT INTO tk_resource VALUES('b','ACTIVITY',?,19,?)",UUID.fromString(ACTIVITY),"{\"private\":\"other owner's journal\"}");
        execute(c,"INSERT INTO tk_locale VALUES('a','nb-NO')");
        execute(c,"INSERT INTO tk_api_token VALUES(?,?,'a','Private API token','READ_WRITE',NULL,FALSE,'MANAGED',?,NULL,5,NULL)","a".repeat(64),UUID.fromString(TOKEN),NOW);
        execute(c,"INSERT INTO tk_passkey_user VALUES('a','private-user-handle')");
        execute(c,"INSERT INTO tk_passkey_credential VALUES(?,'a',?,'private-credential-id','source.example','private-public-key',17,TRUE,FALSE,'[\"internal\"]','Mac',?,NULL,FALSE,6)",UUID.fromString(PASSKEY),"c".repeat(64),NOW);
        execute(c,"INSERT INTO tk_auth_session VALUES(?,'a',?,'READ_WRITE',?,?,?,TRUE,?,3,?,?)","b".repeat(64),"s".repeat(64),NOW,"2030-01-01T00:00:00Z",NOW,"a".repeat(64),"d".repeat(64),UUID.fromString(PASSKEY));
        execute(c,"INSERT INTO tk_auth_challenge VALUES(?,'RECOVERY','b',?,TRUE)","e".repeat(64),NOW);
        execute(c,"INSERT INTO tk_auth_throttle VALUES('private-rate-limit',2,?)",NOW);
        execute(c,"INSERT INTO tk_recovery_address VALUES('a','PRIMARY','private@example.com',NULL,8)");
        execute(c,"INSERT INTO tk_recovery_link VALUES(?,'a','PRIMARY','private@example.com','VERIFY',3,?,?,FALSE)","f".repeat(64),NOW,"2030-01-01T00:00:00Z");
        execute(c,"INSERT INTO tk_passkey_ceremony VALUES(?,?,'PROOF','a',?,'{}',?,FALSE)","1".repeat(64),"d".repeat(64),"b".repeat(64),NOW);
        execute(c,"INSERT INTO tk_relation VALUES('a','TASK',?,'RELATED','TASK',?)",UUID.fromString(TASK),UUID.fromString(TASK));
        if(postgres)execute(c,"INSERT INTO tk_external_reference VALUES('a','provider','repository/æ','external 😀','TASK',?,?)",UUID.fromString(TASK),hash(array().add("provider").add("repository/æ").add("external 😀").toString()));
        else execute(c,"INSERT INTO tk_external_reference VALUES('a','provider','repository/æ','external 😀','TASK',?)",UUID.fromString(TASK));
        execute(c,"INSERT INTO tk_active VALUES('b','ACTIVITY',?)",UUID.fromString(ACTIVITY));
        execute(c,"INSERT INTO tk_receipt VALUES('a',?,?,201,?,NULL,'/private/location',?)",UUID.randomUUID(),"0".repeat(64),"{\"private\":true}",timestamp);
        execute(c,"INSERT INTO tk_change VALUES('a',9,'TASK',?,'CREATED',?)",TASK,timestamp);cCommit(c);
    }
    private static void cCommit(Connection c){try{c.commit();}catch(SQLException failure){throw new AssertionError(failure);}}

    @Test void realPostgresqlAndH2ExchangeCompleteImagesWithoutChangingCredentialsOrPrecision() throws Exception {
        String url=System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_URL");if(url==null)url=System.getenv("TIMEKEEPER_TEST_PG_URL");org.junit.jupiter.api.Assumptions.assumeTrue(url!=null,"Requires the disposable PostgreSQL fixture");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres"))throw new IllegalArgumentException("Only a loopback disposable fixture is supported");
        String password=System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_PASSWORD");if(password==null)password=System.getenv("TIMEKEEPER_TEST_PG_PASSWORD");String name="timekeeper_image_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(url,"postgres",password)) {
            admin.createStatement().execute("CREATE DATABASE "+name);
            try(var pg=DriverManager.getConnection(url.substring(0,url.length()-8)+name,"postgres",password);var h2=database()) {
                ServerSchema.initializeOrValidate(pg);pg.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);pg.setAutoCommit(false);
                seed(h2);Path original=snapshot(h2,"h2.json");InstanceDatabaseImage.replace(pg,original);pg.commit();
                Path fromPg=snapshot(pg,"postgres.json");assertEquals(-1,Files.mismatch(original,fromPg));
                InstanceDatabaseImage.replace(h2,fromPg);h2.commit();assertEquals(-1,Files.mismatch(original,snapshot(h2,"h2-again.json")));
                String large=object().put("data","x".repeat(3_000_000)).toString();
                for(int index=0;index<25;index++)execute(pg,"INSERT INTO tk_resource VALUES('a','JOURNAL_ASSET',?,1,?)",UUID.randomUUID(),large);pg.commit();
                String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
                Path log=directory.resolve("memory-probe.log");var command=new ProcessBuilder(javaExecutable,"-Xmx64m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),InstanceDatabaseImageMemoryProbe.class.getName());
                command.environment().put("TIMEKEEPER_INSTANCE_PROBE_URL",url.substring(0,url.length()-8)+name);command.environment().remove("JAVA_TOOL_OPTIONS");command.environment().remove("JDK_JAVA_OPTIONS");command.redirectErrorStream(true).redirectOutput(log.toFile());
                var process=command.start();if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("Constrained export JVM did not finish");}
                assertEquals(0,process.exitValue(),Files.readString(log));assertTrue(Files.readString(log).contains("INSTANCE_MEMORY_PROBE_OK"));
            }finally {admin.createStatement().execute("DROP DATABASE "+name+" WITH (FORCE)");}
        }
    }

    @Test void allTablesIncludingCredentialsOtherOwnersAndNanosecondsSurviveWithOriginalIdentities() throws Exception {
        try(var source=database();var target=database()) {
            seed(source);Path image=snapshot(source,"source.json");var counts=InstanceDatabaseImage.validate(image);
            assertEquals(23,counts.size());assertTrue(counts.values().stream().allMatch(count->count>0));
            assertEquals(counts,InstanceDatabaseImage.preflight(image,directory));
            InstanceDatabaseImage.replace(target,image);target.commit();
            assertEquals(-1,Files.mismatch(image,snapshot(target,"restored.json")));
            try(var rows=target.createStatement().executeQuery("SELECT password_hash FROM tk_account WHERE owner_id='b'")){assertTrue(rows.next());assertEquals("private-password-hash-b",rows.getString(1));}
            try(var rows=target.createStatement().executeQuery("SELECT created_at FROM tk_receipt")){assertTrue(rows.next());assertEquals(NOW,rows.getObject(1,OffsetDateTime.class).toInstant().toString());}
        }
    }

    @Test void preflightRejectsInvalidPrivateRecordsAndRemovesScratchDatabase() throws Exception {
        try(var source=database()) {
            seed(source);Path image=snapshot(source,"source.json");String valid=Files.readString(image);
            for(String tableName:List.of("tk_auth_session","tk_auth_control","tk_recovery_control","tk_service_settings")) {
                var document=MAPPER.readTree(valid);
                for(var table:document.path("tables"))if(table.path("name").asText().equals(tableName)) {
                    var rows=(com.fasterxml.jackson.databind.node.ArrayNode)table.path("rows");
                    if(tableName.equals("tk_auth_session"))((com.fasterxml.jackson.databind.node.ArrayNode)rows.get(0)).set(1,MAPPER.getNodeFactory().textNode("missing-owner"));else rows.removeAll();
                }
                Files.writeString(image,document.toString());var failure=assertThrows(IOException.class,()->InstanceDatabaseImage.preflight(image,directory));
                assertEquals("INVALID_INSTANCE_DATABASE",failure.getMessage());assertNull(failure.getCause());
                try(var files=Files.list(directory)){assertEquals(List.of(image),files.toList());}
            }
        }
    }

    @Test void coverageRejectsNewPersistedTablesAndColumns() throws Exception {
        try(var database=database()) {
            InstanceDatabaseImage.verifyCoverage(database);
            execute(database,"CREATE TABLE tk_future_secret(id INTEGER)");
            assertThrows(SQLException.class,()->InstanceDatabaseImage.verifyCoverage(database));
            execute(database,"DROP TABLE tk_future_secret");execute(database,"ALTER TABLE tk_account ADD COLUMN future_secret VARCHAR(100)");
            assertThrows(SQLException.class,()->InstanceDatabaseImage.verifyCoverage(database));
        }
    }

    @Test void malformedOrConstraintViolatingImagesNeverReplaceAnyExistingRecordEvenIfCallerCommits() throws Exception {
        try(var source=database();var target=database()) {
            seed(source);seed(target);Path image=snapshot(source,"source.json"),before=snapshot(target,"before.json");
            String valid=Files.readString(image);
            for(String malformed:List.of(valid.substring(0,valid.length()-1),valid+"{}",valid.replace("\"schema\":"+ServerSchema.CURRENT,"\"schema\":999"),valid.replace("\"tk_locale\"","\"unexpected_table\""))) {
                Files.writeString(image,malformed);var failure=assertThrows(IOException.class,()->InstanceDatabaseImage.replace(target,image));
                assertEquals("INVALID_INSTANCE_DATABASE",failure.getMessage());assertNull(failure.getCause());target.commit();assertEquals(-1,Files.mismatch(before,snapshot(target,"after.json")));
            }
            var document=MAPPER.readTree(valid);for(var table:document.path("tables"))if(table.path("name").asText().equals("tk_auth_session"))((com.fasterxml.jackson.databind.node.ArrayNode)table.path("rows").get(0)).set(1,MAPPER.getNodeFactory().textNode("missing-owner"));
            Files.writeString(image,document.toString());var failure=assertThrows(IOException.class,()->InstanceDatabaseImage.replace(target,image));assertNull(failure.getCause());target.commit();assertEquals(-1,Files.mismatch(before,snapshot(target,"after.json")));
        }
    }

    @Test void repeatableSnapshotCannotMixAConcurrentCommittedChange() throws Exception {
        try(var source=database()) {
            seed(source);Path before=snapshot(source,"before.json");
            try(var peer=DriverManager.getConnection(source.getMetaData().getURL())) {
                execute(peer,"UPDATE tk_account SET display_name='Concurrent private change',version=version+1 WHERE owner_id='a'");
                execute(peer,"UPDATE tk_resource SET version=version+1,payload='{}' WHERE owner_id='a'");
            }
            assertEquals(-1,Files.mismatch(before,snapshot(source,"same-snapshot.json")));
            source.commit();assertNotEquals(-1,Files.mismatch(before,snapshot(source,"next-snapshot.json")));
            source.setAutoCommit(true);assertThrows(SQLException.class,()->snapshot(source,"unsafe.json"));
        }
    }

    @Test void firstReadOfIndependentTablesUsesTheSameCommittedSnapshot() throws Exception {
        try(var source=database()) {
            seed(source);Path before=snapshot(source,"before-independent.json");source.commit();var changed=new java.util.concurrent.atomic.AtomicBoolean();
            var guarded=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    if(method.getName().equals("prepareStatement")&&args[0].toString().contains(" FROM tk_service_settings ORDER BY ")&&changed.compareAndSet(false,true)) {
                        try(var peer=DriverManager.getConnection(source.getMetaData().getURL())) {
                            peer.setAutoCommit(false);execute(peer,"UPDATE tk_owner SET zone_id='UTC',version=version+1 WHERE id='a'");
                            execute(peer,"UPDATE tk_service_settings SET version=version+1,saved_smtp_password='Concurrent private SMTP credential' WHERE id=1");peer.commit();
                        }
                    }
                    return method.invoke(source,args);
                }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            Path during=snapshot(guarded,"during-independent.json");assertTrue(changed.get());assertEquals(-1,Files.mismatch(before,during));
            source.commit();assertNotEquals(-1,Files.mismatch(before,snapshot(source,"after-independent.json")));
            source.commit();source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);assertThrows(SQLException.class,()->snapshot(source,"rejected-weak-snapshot.json"));
        }
    }
    @Test void damagedStagingAfterPreflightRollsBackAllDestinationDeletes() throws Exception {
        try(var source=database();var target=database()) {
            seed(source);seed(target);Path image=snapshot(source,"source.json"),before=snapshot(target,"before.json");
            var damaged=new java.util.concurrent.atomic.AtomicBoolean();
            var guarded=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    Object value=method.invoke(target,args);
                    if(!method.getName().equals("createStatement"))return value;
                    var statement=(Statement)value;
                    return java.lang.reflect.Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(ignored,operation,arguments)->{
                        try {
                            Object result=operation.invoke(statement,arguments);
                            if(operation.getName().equals("executeUpdate")&&arguments[0].toString().startsWith("DELETE FROM")&&damaged.compareAndSet(false,true))Files.writeString(image,"truncated staging file");
                            return result;
                        }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });
                }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            assertThrows(IOException.class,()->InstanceDatabaseImage.replace(guarded,image));assertTrue(damaged.get());
            target.commit();assertEquals(-1,Files.mismatch(before,snapshot(target,"after.json")));
        }
    }
}
