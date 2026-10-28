package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceRestoreRecoveryTest {
    @TempDir Path directory;
    private final UUID generation=UUID.randomUUID(),artifactRoot=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-28T12:00:00Z");
    record Fixture(InstanceRestoreIntent intent,UUID operation,Path settings,String url,String user,String password) { }
    Fixture fixture(String url,String user,String password) throws Exception {return fixture(url,user,password,false);}
    Fixture fixture(String url,String user,String password,boolean httpFixture) throws Exception {
        UUID operation;long version;
        try(var c=DriverManager.getConnection(url,user,password)) {
            ServerSchema.initializeOrValidate(c);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);c.setAutoCommit(false);
            execute(c,"INSERT INTO tk_owner VALUES('bootstrap','UTC',0,0,?)","a".repeat(64));execute(c,"INSERT INTO tk_account VALUES('bootstrap','bootstrap',?,'Bootstrap','ENABLED',TRUE,TRUE,0)",AccountPasswords.hash("Disposable bootstrap password"));execute(c,"UPDATE tk_auth_control SET bootstrap_completed=TRUE");
            InstanceOperations.bindStorage(c,artifactRoot);var lease=InstanceOperations.beginUpload(c,new TokenRegistry.Principal("bootstrap",Set.of("read","write"),"SESSION","b".repeat(64)),100,"a".repeat(64),now);
            InstanceOperations.advance(c,lease,InstanceOperations.State.UPLOADING,InstanceOperations.State.VALIDATING,now);InstanceOperations.finishReview(c,lease,object(),now);operation=lease.id();version=InstanceOperations.status(c,operation).version();c.commit();
        }
        char[] phrase="Synthetic recovery source passphrase".toCharArray();Path encrypted;
        try(var source=InstanceDatabaseImageTest.database();var config=InstanceConfiguration.capture(InstanceConfigurationTest.base(),Map.of())) {
            InstanceAuthenticationPreflightTest.seed(source);
            if(httpFixture){
                // Portability fixtures deliberately contain opaque domain payloads.
                // HTTP acceptance uses well-formed business data instead.
                for(String table:List.of("tk_relation","tk_external_reference","tk_active","tk_resource","tk_receipt","tk_change"))execute(source,"DELETE FROM "+table);
                UUID client=UUID.fromString("11111111-1111-4111-8111-111111111111");
                var payload=object().put("id",client.toString()).put("ownerId","a").put("version",17).put("name","Restored client æ").put("color","#123456");
                execute(source,"INSERT INTO tk_resource VALUES('a','CLIENT',?,17,?)",client,payload.toString());source.commit();
            }
            encrypted=InstanceBackupArchive.create(source,config,object().put("serverVersion","2.0.0").put("commit","0".repeat(40)).put("databaseType","h2").put("packageId","timekeeper").put("publicOrigin","https://source.example"),phrase,directory);
        }
        var values=InstanceConfigurationTest.base();values.put("quarkus.datasource.jdbc.url",url);values.put("quarkus.datasource.db-kind",DatabaseConfiguration.postgres(url)?"postgresql":"h2");values.put("quarkus.datasource.username",user);values.put("quarkus.datasource.password",password);
        var intent=new InstanceRestoreIntent(directory.resolve("intents, café"),10_000_000);var spec=new InstanceRestoreIntent.Spec(generation,operation,version,"bootstrap",artifactRoot,null,new InstanceRestoreDatabase.Deployment("dagsverk",httpFixture?"NONE":"https://destination.example:8443","AUTO",!httpFixture));
        try(var archive=InstanceBackupArchive.open(encrypted,phrase,directory,10_000_000);var target=InstanceConfiguration.capture(values,Map.of());var prepared=intent.prepare(spec,archive,target,now);var c=DriverManager.getConnection(url,user,password)) {
            // Protocol fixture only. Real proof/session/consent commits and
            // rollback are covered through InstanceAdministratorTest.
            var binding=object().put("generation",generation.toString()).put("sha256",prepared.digest());
            c.setAutoCommit(false);var payload=object().put("type","RESTORE").put("state","REVIEW").put("restorePhase","PREPARED");payload.set("restoreIntent",binding);
            execute(c,"UPDATE tk_instance SET version=version+1,payload=? WHERE id=?",payload.toString(),operation);
            var control=object().put("artifactRootId",artifactRoot.toString()).put("restorePending",true);control.set("restoreIntent",binding);execute(c,"UPDATE tk_instance SET payload=? WHERE kind='CONTROL'",control.toString());c.commit();
        }
        Path settings=directory.resolve("destination.properties");var properties=new Properties();properties.putAll(values);properties.setProperty("timekeeper.instance-restore.storage",intent.storage().toString());try(var out=Files.newOutputStream(settings)){properties.store(out,"Synthetic destination configuration");}Files.setPosixFilePermissions(settings,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        return new Fixture(intent,operation,settings,url,user,password);
    }
    private String scalar(Connection c,String sql) throws Exception {try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    private String cli(Fixture fixture,boolean success) throws Exception {
        Path log=directory.resolve("cli.log");String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var command=new ProcessBuilder(executable,"-Dquarkus.config.locations="+fixture.settings().toUri().toASCIIString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),InstanceRestoreMain.class.getName(),fixture.intent().storage().toString());
        command.environment().remove("QUARKUS_PROFILE");Path secret=directory.resolve("database-secret");if(Files.exists(secret))command.environment().put("TIMEKEEPER_SERVER_DB_PASSWORD_FILE",secret.toString());var process=command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(success?0:1,process.exitValue(),Files.readString(log));return Files.readString(log);}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
    }
    private void assertComplete(Fixture fixture) throws Exception {
        var active=InstanceRestoreMaterial.active(fixture.intent().storage());assertEquals(generation,active.generation());assertFalse(Files.exists(fixture.intent().storage().resolve("prepared.json")));assertFalse(Files.exists(fixture.intent().storage().resolve(generation.toString())));
        try(var c=DriverManager.getConnection(fixture.url(),fixture.user(),fixture.password())) {
            var control=parse(scalar(c,"SELECT payload FROM tk_instance WHERE kind='CONTROL'"));assertFalse(control.path("restorePending").asBoolean());assertEquals("COMPLETE",control.path("restore").path("phase").asText());
            assertTrue(AccountPasswords.matches("Preserved archive account password",scalar(c,"SELECT password_hash FROM tk_account WHERE owner_id='a'")));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM tk_account WHERE owner_id='bootstrap'"));
            assertEquals("1",scalar(c,"SELECT COUNT(*) FROM tk_instance WHERE kind='AUDIT' AND payload LIKE '%RESTORE_COMPLETED%'"));assertEquals("RESTORED",InstanceOperations.status(c,fixture.operation()).state().name());
        }
    }
    @Test void offlineEntryPointFinishesRestorationAndCleanupCanBeRepeatedWithoutReapplying() throws Exception {
        var fixture=fixture("jdbc:h2:"+directory.resolve("target"),"sa","");String result=cli(fixture,true);assertTrue(result.startsWith("INSTANCE_RESTORE_COMPLETE\n"));assertComplete(fixture);assertEquals(result,cli(fixture,true));assertComplete(fixture);
    }
    @Test void recoveryResumesAfterDatabaseCommitAndAfterPartialCompletedCleanup() throws Exception {
        var fixture=fixture("jdbc:h2:"+directory.resolve("resume"),"sa","");
        try(var prepared=fixture.intent().open();var c=DriverManager.getConnection(fixture.url(),fixture.user(),fixture.password());var exclusive=InstanceRestoreFence.exclusive(c)) {
            InstanceRestoreMaterial.install(prepared);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);c.setAutoCommit(false);var spec=prepared.spec();InstanceRestoreDatabase.apply(c,exclusive,prepared.archive(),spec.deployment(),spec.actor(),spec.operation(),spec.reviewVersion()+1,spec.artifactRoot(),spec.generation(),now);c.commit();
        }
        assertTrue(cli(fixture,true).contains("INSTANCE_RESTORE_COMPLETE"));assertComplete(fixture);
        // A crash can leave only part of the completed staging directory. The
        // complete-state path never needs to load its removed source image.
        Path stage=fixture.intent().storage().resolve(generation.toString());Files.createDirectory(stage);Files.setPosixFilePermissions(stage,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));Files.writeString(stage.resolve("partial"),"private incomplete cleanup");
        assertTrue(cli(fixture,true).contains("INSTANCE_RESTORE_COMPLETE"));assertComplete(fixture);
    }
    @Test void wrongCredentialsAreRedactedAndAnExistingClientPreventsAllReplacement() throws Exception {
        var fixture=fixture("jdbc:h2:"+directory.resolve("refuse"),"sa","");
        try(var c=DriverManager.getConnection(fixture.url(),fixture.user(),fixture.password())){execute(c,"INSERT INTO tk_resource VALUES('bootstrap','CLIENT',?,1,'{}')",UUID.randomUUID());}
        assertFalse(cli(fixture,false).contains(directory.toString()));
        var settings=new Properties();try(var input=Files.newInputStream(fixture.settings())){settings.load(input);}settings.setProperty("quarkus.datasource.password","private invalid credential");try(var output=Files.newOutputStream(fixture.settings())){settings.store(output,"Synthetic wrong credential");}
        String result=cli(fixture,false);assertFalse(result.contains("private invalid credential"));assertFalse(result.contains("password"));assertFalse(result.contains(directory.toString()));
        try(var c=DriverManager.getConnection(fixture.url(),fixture.user(),fixture.password())){assertEquals("bootstrap",scalar(c,"SELECT owner_id FROM tk_account"));assertEquals("1",scalar(c,"SELECT COUNT(*) FROM tk_resource WHERE entity_type='CLIENT'"));}
    }
    @Test void offlineEntryPointResolvesTheSamePasswordFileAsTheDeploymentEntrypoint() throws Exception {
        var fixture=fixture("jdbc:h2:"+directory.resolve("file-secret-target"),"sa","synthetic file-backed role password");
        Path secret=directory.resolve("database-secret");Files.writeString(secret,"synthetic file-backed role password\n");Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var settings=new Properties();try(var input=Files.newInputStream(fixture.settings())){settings.load(input);}settings.remove("quarkus.datasource.password");try(var output=Files.newOutputStream(fixture.settings())){settings.store(output,"Synthetic file-backed destination");}
        assertTrue(cli(fixture,true).startsWith("INSTANCE_RESTORE_COMPLETE\n"));assertComplete(fixture);
    }
    @Test void postgresEntryPointRestoresUsingAnOrdinaryApplicationRole() throws Exception {
        String login="timekeeper_restore_"+UUID.randomUUID().toString().replace("-","");var created=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            InstanceRestoreFenceTest.postgres((url,password,owner)->{
                try(var admin=DriverManager.getConnection(url,"postgres",password)) {
                    execute(admin,"CREATE ROLE "+login+" LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD 'synthetic-restore-role-password'");created.set(true);execute(admin,"GRANT "+owner+" TO "+login);
                }
                var fixture=fixture(url,login,"synthetic-restore-role-password");assertTrue(cli(fixture,true).contains("INSTANCE_RESTORE_COMPLETE"));assertComplete(fixture);assertTrue(cli(fixture,true).contains("INSTANCE_RESTORE_COMPLETE"));assertComplete(fixture);
            });
        }finally {
            if(created.get())try(var admin=DriverManager.getConnection(System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_URL"),"postgres",System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_PASSWORD"))){execute(admin,"DROP ROLE "+login);}
        }
    }
}
