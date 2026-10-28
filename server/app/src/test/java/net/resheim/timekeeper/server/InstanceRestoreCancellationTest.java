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

class InstanceRestoreCancellationTest {
    @TempDir Path directory;
    private final Instant now=Instant.parse("2026-10-28T12:00:00Z");
    private InstanceRestoreRecoveryTest.Fixture fixture(String name) throws Exception {
        var test=new InstanceRestoreRecoveryTest();test.directory=directory.resolve(name);Files.createDirectory(test.directory);
        return test.fixture("jdbc:h2:"+test.directory.resolve("target"),"sa","");
    }
    private Connection connection(InstanceRestoreRecoveryTest.Fixture fixture) throws SQLException {return DriverManager.getConnection(fixture.url(),fixture.user(),fixture.password());}
    private String scalar(Connection c,String sql) throws SQLException {try(var statement=c.createStatement();var rows=statement.executeQuery(sql)){assertTrue(rows.next());return rows.getString(1);}}
    private void assertCancelled(Connection c,UUID operation) throws Exception {
        var control=parse(scalar(c,"SELECT payload FROM tk_instance WHERE kind='CONTROL'"));assertFalse(control.path("restorePending").asBoolean());assertFalse(control.has("restoreIntent"));assertFalse(control.has("restoreGeneration"));
        assertEquals("EXPIRED",InstanceOperations.status(c,operation).state().name());assertEquals("1",scalar(c,"SELECT COUNT(*) FROM tk_instance WHERE kind='AUDIT' AND payload LIKE '%RESTORE_CANCELLED%'"));
        assertEquals("1",scalar(c,"SELECT COUNT(*) FROM tk_account WHERE owner_id='bootstrap'"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM tk_account WHERE owner_id='a'"));
        var fence=new InstanceRestoreFence();fence.initialize(c);try(var guard=fence.enter(c)){ }
    }
    @Test void cancellationNeedsNoReadableDecryptedImageAndCanCleanUpAfterAnInterruptedDeletion() throws Exception {
        var fixture=fixture("corrupt");var intent=fixture.intent();UUID generation;
        try(var prepared=intent.open()){generation=prepared.spec().generation();InstanceRestoreMaterial.install(prepared);}
        Path stage=intent.storage().resolve(generation.toString());Files.writeString(stage.resolve("database.json"),"corrupt incomplete private source");Files.delete(stage.resolve("source-configuration.json"));Files.deleteIfExists(stage.resolve("intent.json"));
        try(var c=connection(fixture);var cancellation=intent.cancellation(c,fixture.operation());var exclusive=InstanceRestoreFence.exclusive(c)) {
            cancellation.cancelOffline(c,exclusive,"bootstrap","Disposable bootstrap password",now);assertCancelled(c,fixture.operation());
            // Stop after the durable receipt, without performing any cleanup.
        }
        // Simulate a crash after pointer deletion and half the plaintext cleanup.
        Files.delete(intent.storage().resolve("prepared.json"));Files.deleteIfExists(stage.resolve("intent.json"));
        try(var c=connection(fixture);var cancellation=intent.cancellation(c,fixture.operation());var exclusive=InstanceRestoreFence.exclusive(c)) {
            cancellation.cancelOffline(c,exclusive,"bootstrap","Disposable bootstrap password",now);cancellation.cleanup(c);assertCancelled(c,fixture.operation());
            cancellation.cleanup(c);assertCancelled(c,fixture.operation());
        }
        assertFalse(Files.exists(stage));assertFalse(Files.exists(intent.storage().resolve("material").resolve(generation.toString())));
    }
    @Test void wrongOrUnrelatedCredentialsNeverReleasePendingConsentAndAttemptsAreDurable() throws Exception {
        var fixture=fixture("credentials");var intent=fixture.intent();
        try(var c=connection(fixture);var cancellation=intent.cancellation(c,fixture.operation());var exclusive=InstanceRestoreFence.exclusive(c)) {
            for(int index=0;index<5;index++) {
                String name=index==0?"another-admin":"bootstrap";String password=index==0?"Disposable bootstrap password":"Incorrect private password";
                assertEquals("INSTANCE_PROOF_REQUIRED",assertThrows(ApiFailure.class,()->cancellation.cancelOffline(c,exclusive,name,password,now)).code);
                assertTrue(parse(scalar(c,"SELECT payload FROM tk_instance WHERE kind='CONTROL'")).path("restorePending").asBoolean());
            }
            assertEquals("RATE_LIMITED",assertThrows(ApiFailure.class,()->cancellation.cancelOffline(c,exclusive,"bootstrap","Disposable bootstrap password",now)).code);
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM tk_instance WHERE kind='AUDIT' AND payload LIKE '%RESTORE_CANCELLED%'"));assertTrue(Files.exists(intent.storage().resolve("prepared.json")));
            cancellation.cancelOffline(c,exclusive,"bootstrap","Disposable bootstrap password",now.plusSeconds(301));cancellation.cleanup(c);assertCancelled(c,fixture.operation());
        }
    }
    @Test void aCommittedReplacementCannotBeCancelledOrHaveItsMaterialRemoved() throws Exception {
        var fixture=fixture("applied");var intent=fixture.intent();
        try(var c=connection(fixture);var prepared=intent.open();var exclusive=InstanceRestoreFence.exclusive(c)) {
            InstanceRestoreMaterial.install(prepared);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);c.setAutoCommit(false);var spec=prepared.spec();
            InstanceRestoreDatabase.apply(c,exclusive,prepared.archive(),spec.deployment(),spec.actor(),spec.operation(),spec.reviewVersion()+1,spec.artifactRoot(),spec.generation(),now);c.commit();c.setAutoCommit(true);
        }
        try(var c=connection(fixture);var cancellation=intent.cancellation(c,fixture.operation());var exclusive=InstanceRestoreFence.exclusive(c)) {
            assertEquals("INSTANCE_OPERATION_STATE",assertThrows(ApiFailure.class,()->cancellation.cancelOffline(c,exclusive,"bootstrap","Disposable bootstrap password",now)).code);
            assertEquals("INSTANCE_OPERATION_STATE",assertThrows(ApiFailure.class,()->cancellation.cleanup(c)).code);assertTrue(Files.exists(intent.storage().resolve("prepared.json")));
            assertEquals("1",scalar(c,"SELECT COUNT(*) FROM tk_account WHERE owner_id='a'"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM tk_account WHERE owner_id='bootstrap'"));
        }
        try(var c=connection(fixture)){InstanceRestoreRecovery.resume(c,intent,now);assertFalse(parse(scalar(c,"SELECT payload FROM tk_instance WHERE kind='CONTROL'")).path("restorePending").asBoolean());}
    }
    @Test void stoppedServerEntryPointAcceptsCredentialsOnlyOnStdinAndCanBeRepeated() throws Exception {
        var fixture=fixture("cli");String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        for(int attempt=0;attempt<3;attempt++) {
            boolean valid=attempt>0;Path log=directory.resolve("cancel-cli-"+attempt+".log");
            var process=new ProcessBuilder(executable,"-Dquarkus.config.locations="+fixture.settings().toUri().toASCIIString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),InstanceRestoreMain.class.getName(),"--cancel",fixture.intent().storage().toString(),fixture.operation().toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                try(var input=process.getOutputStream()){input.write(MAPPER.writeValueAsBytes(object().put("username","bootstrap").put("currentPassword",valid?"Disposable bootstrap password":"Private wrong cancellation password")));}
                assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(valid?0:1,process.exitValue());String result=Files.readString(log);
                assertFalse(result.contains("password"));assertFalse(result.contains("jdbc:"));if(valid)assertEquals("INSTANCE_RESTORE_CANCELLED\n",result.replace("\r\n","\n"));
            }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
        }
        try(var c=connection(fixture)){assertCancelled(c,fixture.operation());}assertFalse(Files.exists(fixture.intent().storage().resolve("prepared.json")));
    }
    @Test void postgresApplicationRoleCanCancelAndRetryWithoutPrivilegedHostCredentials() throws Exception {
        String login="timekeeper_cancel_"+UUID.randomUUID().toString().replace("-","");var created=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            InstanceRestoreFenceTest.postgres((url,password,owner)->{
                try(var admin=DriverManager.getConnection(url,"postgres",password)) {
                    execute(admin,"CREATE ROLE "+login+" LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD 'synthetic-cancellation-role-password'");created.set(true);execute(admin,"GRANT "+owner+" TO "+login);
                }
                var test=new InstanceRestoreRecoveryTest();test.directory=directory;var fixture=test.fixture(url,login,"synthetic-cancellation-role-password");
                for(int attempt=0;attempt<2;attempt++)try(var c=connection(fixture);var cancellation=fixture.intent().cancellation(c,fixture.operation());var exclusive=InstanceRestoreFence.exclusive(c)) {
                    cancellation.cancelOffline(c,exclusive,"bootstrap","Disposable bootstrap password",now);cancellation.cleanup(c);assertCancelled(c,fixture.operation());
                }
            });
        }finally {
            if(created.get())try(var admin=DriverManager.getConnection(System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_URL"),"postgres",System.getenv("TIMEKEEPER_INSTANCE_TEST_PG_PASSWORD"))){execute(admin,"DROP ROLE "+login);}
        }
    }
    @Test void receiptCannotDeleteAnotherStagedGenerationOrFollowASymlinkedMaterialParent() throws Exception {
        var fixture=fixture("cleanup");var intent=fixture.intent();
        try(var c=connection(fixture);var cancellation=intent.cancellation(c,fixture.operation());var exclusive=InstanceRestoreFence.exclusive(c)) {
            cancellation.cancelOffline(c,exclusive,"bootstrap","Disposable bootstrap password",now);
            var pointer=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(Files.readString(intent.storage().resolve("prepared.json")));String original=pointer.toString();
            pointer.put("generation",UUID.randomUUID().toString());Files.writeString(intent.storage().resolve("prepared.json"),pointer.toString());assertThrows(java.io.IOException.class,()->cancellation.cleanup(c));
            Files.writeString(intent.storage().resolve("prepared.json"),original);
            Path outside=directory.resolve("outside");Files.createDirectory(outside);Files.createSymbolicLink(intent.storage().resolve("material"),outside);
            assertThrows(java.io.IOException.class,()->cancellation.cleanup(c));assertTrue(Files.isDirectory(outside));Files.delete(intent.storage().resolve("material"));cancellation.cleanup(c);
        }
    }
}
