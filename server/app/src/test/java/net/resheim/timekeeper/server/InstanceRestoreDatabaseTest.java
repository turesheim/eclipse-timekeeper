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

class InstanceRestoreDatabaseTest {
    @TempDir Path directory;
    private final Instant now=Instant.parse("2026-10-28T12:00:00Z");
    private Path encryptedArchive;
    private final UUID root=UUID.randomUUID(),generation=UUID.randomUUID();
    private final InstanceRestoreDatabase.Deployment deployment=new InstanceRestoreDatabase.Deployment("dagsverk","https://destination.example:8443","AUTO",true);
    private record Target(UUID operation,long version) { }
    private Target bootstrap(Connection c) throws Exception {
        ServerSchema.initializeOrValidate(c);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);c.setAutoCommit(false);
        execute(c,"INSERT INTO tk_owner VALUES('bootstrap','UTC',0,0,?)","a".repeat(64));
        execute(c,"INSERT INTO tk_account VALUES('bootstrap','bootstrap',?,'Bootstrap','ENABLED',TRUE,TRUE,0)",AccountPasswords.hash("Disposable bootstrap password"));
        execute(c,"UPDATE tk_auth_control SET bootstrap_completed=TRUE");
        execute(c,"UPDATE tk_recovery_control SET epoch=9");
        InstanceOperations.bindStorage(c,root);
        var lease=InstanceOperations.begin(c,"bootstrap",InstanceOperations.Type.RESTORE,now);
        InstanceOperations.advance(c,lease,InstanceOperations.State.UPLOADING,InstanceOperations.State.VALIDATING,now);
        InstanceOperations.finishReview(c,lease,object(),now);var operation=InstanceOperations.status(c,lease.id());c.commit();c.setAutoCommit(true);
        return new Target(operation.id(),operation.version());
    }
    private InstanceBackupArchive.Verified archive() throws Exception {return archive(c->{});}
    private InstanceBackupArchive.Verified archive(InstanceDatabaseImage.Inspection edit) throws Exception {
        char[] phrase="Disposable full restore archive passphrase".toCharArray();Path encrypted;
        try(var source=InstanceDatabaseImageTest.database();var config=InstanceConfiguration.capture(InstanceConfigurationTest.base(),Map.of())) {
            InstanceAuthenticationPreflightTest.seed(source);
            execute(source,"UPDATE tk_passkey_credential SET rp_id='previous.example'");
            // A crafted future epoch must not become a valid session after cutover.
            execute(source,"UPDATE tk_auth_session SET origin_epoch=10");edit.check(source);source.commit();
            encrypted=InstanceBackupArchive.create(source,config,object().put("serverVersion","2.0.0").put("commit","0".repeat(40)).put("databaseType","h2").put("packageId","timekeeper").put("publicOrigin","https://source.example"),phrase,directory);
        }
        encryptedArchive=encrypted;return InstanceBackupArchive.open(encrypted,phrase,directory,10_000_000);
    }
    private String value(Connection c,String sql) throws Exception {try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    private Path image(Connection c,String name) throws Exception {
        boolean own=c.getAutoCommit();if(own)c.setAutoCommit(false);
        try {Path path=directory.resolve(name);try(var out=Files.newOutputStream(path)){InstanceDatabaseImage.write(c,out);}return path;}
        finally{if(own){c.rollback();c.setAutoCommit(true);}}
    }
    private InstanceRestoreDatabase.Applied apply(Connection c,InstanceRestoreFence.Exclusive lock,InstanceBackupArchive.Verified archive,Target target) throws Exception {
        c.setAutoCommit(false);return InstanceRestoreDatabase.apply(c,lock,archive,deployment,"bootstrap",target.operation(),target.version(),root,generation,now);
    }
    @Test void appliesSourceDataAndCredentialsButPreservesTargetEvidenceAndKeepsRecoveryPending() throws Exception {
        try(var archive=archive();var c=DriverManager.getConnection("jdbc:h2:"+directory.resolve("target"))) {
            var target=bootstrap(c);var old=new InstanceRestoreFence();old.initialize(c);
            try(var lock=InstanceRestoreFence.exclusive(c)) {
                var result=apply(c,lock,archive,target);assertEquals(generation,result.generation());assertEquals(10,result.originEpoch());c.commit();c.setAutoCommit(true);
                assertEquals("10",value(c,"SELECT epoch FROM tk_recovery_control"));
                assertEquals("https://destination.example:8443",value(c,"SELECT origin FROM tk_recovery_control"));
                assertEquals("0",value(c,"SELECT origin_epoch FROM tk_auth_session"));
                assertEquals("true",value(c,"SELECT consumed FROM tk_recovery_link").toLowerCase(Locale.ROOT));
                assertEquals("true",value(c,"SELECT consumed FROM tk_passkey_ceremony").toLowerCase(Locale.ROOT));
                assertTrue(AccountPasswords.matches("Preserved archive account password",value(c,"SELECT password_hash FROM tk_account WHERE owner_id='a'")));
                assertEquals("0",value(c,"SELECT COUNT(*) FROM tk_account WHERE owner_id='bootstrap'"));
                assertEquals("1",value(c,"SELECT COUNT(*) FROM tk_instance WHERE actor_owner='bootstrap' AND kind='AUDIT' AND payload LIKE '%DATABASE_APPLIED%'"));
                assertEquals("previous.example",value(c,"SELECT rp_id FROM tk_passkey_credential"));
                var control=parse(value(c,"SELECT payload FROM tk_instance WHERE kind='CONTROL'"));assertTrue(control.path("restorePending").asBoolean());assertEquals(root.toString(),control.path("artifactRootId").asText());
                assertEquals("dagsverk",control.path("restore").path("packageId").asText());
                assertEquals("INSTANCE_RESTART_REQUIRED",assertThrows(ApiFailure.class,()->old.enter(c)).code);
                assertEquals("INSTANCE_RESTORE_PENDING",assertThrows(ApiFailure.class,()->new InstanceRestoreFence().initialize(c)).code);
                assertPreserved(archive,c);
            }
        }
    }
    private void assertPreserved(InstanceBackupArchive.Verified archive,Connection c) throws Exception {
        var source=MAPPER.readTree(Files.readString(archive.database()));var restored=MAPPER.readTree(Files.readString(image(c,"restored-"+UUID.randomUUID()+".json")));
        var changed=Set.of("tk_instance","tk_service_settings","tk_recovery_control","tk_auth_session","tk_auth_challenge","tk_recovery_link","tk_passkey_ceremony");
        var tables=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();for(var table:restored.path("tables"))tables.put(table.path("name").asText(),table);
        for(var table:source.path("tables"))if(!changed.contains(table.path("name").asText()))assertEquals(table,tables.get(table.path("name").asText()),table.path("name").asText());
        for(var table:source.path("tables")) {
            String name=table.path("name").asText();String changedColumn=switch(name){case "tk_auth_session"->"origin_epoch";case "tk_auth_challenge","tk_recovery_link","tk_passkey_ceremony"->"consumed";default->null;};
            if(changedColumn!=null) {
                int index=-1;for(int i=0;i<table.path("columns").size();i++)if(table.path("columns").get(i).asText().equals(changedColumn))index=i;
                var expected=table.deepCopy();var actual=tables.get(name).deepCopy();
                for(var row:expected.path("rows"))((com.fasterxml.jackson.databind.node.ArrayNode)row).remove(index);
                for(var row:actual.path("rows"))((com.fasterxml.jackson.databind.node.ArrayNode)row).remove(index);
                assertEquals(expected,actual,name);
            }
            if(name.equals("tk_instance"))for(var row:table.path("rows"))if(row.get(1).asText().equals("AUDIT")) {
                var restoredRows=new HashSet<com.fasterxml.jackson.databind.JsonNode>();tables.get(name).path("rows").forEach(restoredRows::add);assertTrue(restoredRows.contains(row),"Source audit evidence retained");
            }
        }
        var settings=ServiceSettings.forRestore(c);assertEquals(5,settings.version());assertEquals(4,settings.active());assertEquals("private SMTP password",settings.savedPassword());assertEquals("previous SMTP password",settings.effectivePassword());assertEquals(2525,settings.saved().path("smtpPort").asInt());assertEquals(587,settings.effective().path("smtpPort").asInt());
    }
    @Test void closingExclusiveWithoutCommitRollsBackTheEntireDatabasePhase() throws Exception {
        try(var archive=archive();var c=DriverManager.getConnection("jdbc:h2:"+directory.resolve("rollback"))) {
            var target=bootstrap(c);Path before=image(c,"before.json");
            try(var lock=InstanceRestoreFence.exclusive(c)){apply(c,lock,archive,target);}
            assertEquals(-1,Files.mismatch(before,image(c,"after.json")));assertEquals("bootstrap",value(c,"SELECT owner_id FROM tk_account"));
        }
    }
    @Test void nonemptyTargetsStaleReviewsAndWrongStorageNeverReplaceAnyData() throws Exception {
        try(var archive=archive();var c=DriverManager.getConnection("jdbc:h2:"+directory.resolve("refusal"))) {
            var target=bootstrap(c);
            try(var lock=InstanceRestoreFence.exclusive(c)) {
                c.setAutoCommit(false);Path before=image(c,"refusal-before.json");
                var stale=new Target(target.operation(),target.version()-1);assertEquals("STALE_VERSION",assertThrows(ApiFailure.class,()->apply(c,lock,archive,stale)).code);
                assertEquals("INSTANCE_STORAGE_MISMATCH",assertThrows(ApiFailure.class,()->InstanceRestoreDatabase.apply(c,lock,archive,deployment,"bootstrap",target.operation(),target.version(),UUID.randomUUID(),generation,now)).code);
                execute(c,"INSERT INTO tk_resource VALUES('bootstrap','CLIENT',?,1,'{}')",UUID.randomUUID());
                assertEquals("INSTANCE_TARGET_NOT_EMPTY",assertThrows(ApiFailure.class,()->apply(c,lock,archive,target)).code);c.rollback();
                assertEquals(-1,Files.mismatch(before,image(c,"refusal-after.json")));
            }
        }
    }
    @Test void changedStagingChecksumLeavesTheTargetUnchanged() throws Exception {
        try(var archive=archive();var c=DriverManager.getConnection("jdbc:h2:"+directory.resolve("bad-auth"))) {
            var target=bootstrap(c);Path before=image(c,"auth-before.json");
            var document=MAPPER.readTree(Files.readString(archive.database()));
            for(var table:document.path("tables"))if(table.path("name").asText().equals("tk_account"))((com.fasterxml.jackson.databind.node.ArrayNode)table.path("rows").get(0)).set(2,MAPPER.getNodeFactory().textNode("unsupported"));
            Files.writeString(archive.database(),document.toString());
            try(var lock=InstanceRestoreFence.exclusive(c)){assertThrows(java.io.IOException.class,()->apply(c,lock,archive,target));c.rollback();assertEquals(-1,Files.mismatch(before,image(c,"auth-after.json")));}
        }
    }
    @Test void missingExclusivityAndMalformedImportedJobsAreRejectedWithoutChanges() throws Exception {
        try(var archive=archive(source->execute(source,"UPDATE tk_instance SET payload=? WHERE kind='OPERATION'",object().put("type","BACKUP").put("state","FUTURE_UNSUPPORTED").toString()));var c=DriverManager.getConnection("jdbc:h2:"+directory.resolve("unsupported-job"))) {
            var target=bootstrap(c);Path before=image(c,"job-before.json");c.setAutoCommit(false);
            assertThrows(SQLException.class,()->InstanceRestoreDatabase.apply(c,null,archive,deployment,"bootstrap",target.operation(),target.version(),root,generation,now));c.rollback();c.setAutoCommit(true);
            try(var lock=InstanceRestoreFence.exclusive(c)){assertThrows(java.io.IOException.class,()->apply(c,lock,archive,target));c.rollback();}
            assertEquals(-1,Files.mismatch(before,image(c,"job-after.json")));
        }
    }
    private void crash(String url,Target target,Path archive,boolean commit) throws Exception {
        var plan=object().put("operation",target.operation().toString()).put("version",target.version()).put("root",root.toString()).put("generation",generation.toString()).put("archive",archive.toString()).put("stage",directory.toString()).put("commit",commit);
        Path config=directory.resolve("probe.json"),log=directory.resolve("probe.log");Files.writeString(config,plan.toString());
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var command=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),InstanceRestoreDatabasePeer.class.getName(),config.toString());command.environment().put("TIMEKEEPER_RESTORE_PROBE_URL",url);
        var process=command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));assertTrue(Files.readString(log).contains("RESTORE_CRASH_BOUNDARY"));}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
    }
    @Test void h2ProcessCrashesBeforeAndAfterCommitRetainEitherOriginalOrPendingRestoredDatabase() throws Exception {
        try(var archive=archive()) {
            Path encrypted=encryptedArchive;
            for(boolean commit:List.of(false,true)) {
                String url="jdbc:h2:"+directory.resolve("crash-"+commit);Target target;Path before;
                try(var c=DriverManager.getConnection(url)){target=bootstrap(c);before=image(c,"crash-before-"+commit+".json");}
                crash(url,target,encrypted,commit);
                try(var c=DriverManager.getConnection(url)) {
                    c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                    if(commit){assertPreserved(archive,c);assertTrue(parse(value(c,"SELECT payload FROM tk_instance WHERE kind='CONTROL'")).path("restorePending").asBoolean());}
                    else assertEquals(-1,Files.mismatch(before,image(c,"crash-after-false.json")));
                }
            }
        }
    }
    @Test void postgresProcessCrashRetainsCommittedRestoreAndPendingGate() throws Exception {
        try(var archive=archive()) {
            Path encrypted=encryptedArchive;
            InstanceRestoreFenceTest.postgres((url,password,owner)->{
                Target target;try(var c=DriverManager.getConnection(url,"postgres",password)){target=bootstrap(c);}
                crash(url,target,encrypted,true);
                try(var c=DriverManager.getConnection(url,"postgres",password)) {
                    c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);assertPreserved(archive,c);
                    assertTrue(parse(value(c,"SELECT payload FROM tk_instance WHERE kind='CONTROL'")).path("restorePending").asBoolean());
                }
            });
        }
    }
    @Test void postgresAppliesThePortableImageAndRollsBackAnUncommittedRetry() throws Exception {
        try(var archive=archive()) {
            InstanceRestoreFenceTest.postgres((url,password,owner)->{
                try(var c=DriverManager.getConnection(url,"postgres",password)) {
                    execute(c,"SET ROLE "+owner);
                    var target=bootstrap(c);Path before=image(c,"pg-before.json");
                    try(var lock=InstanceRestoreFence.exclusive(c)){apply(c,lock,archive,target);}
                    assertEquals(-1,Files.mismatch(before,image(c,"pg-rollback.json")));
                    try(var lock=InstanceRestoreFence.exclusive(c)){apply(c,lock,archive,target);c.commit();assertPreserved(archive,c);}
                    assertEquals("https://destination.example:8443",value(c,"SELECT origin FROM tk_recovery_control"));
                }
            });
        }
    }
}
