package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(InstanceAdministratorTest.Profile.class)
class InstanceAdministratorTest {
    static final String PASSWORD="synthetic instance administrator password";
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){
            String url=System.getenv("TIMEKEEPER_TEST_INSTANCE_DB_URL");
            if(url==null)return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:instance_administrator_tests","timekeeper.auth.tokens-file","NONE");
            return Map.of("quarkus.datasource.jdbc.url",url,"quarkus.datasource.db-kind","postgresql","quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"),"quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"),"timekeeper.auth.tokens-file","NONE");
        }
    }
    @Inject AccountStore accounts;
    @Inject ServerStore store;
    @Inject InstanceAdministrator administrators;
    @Inject InstanceBackupService backups;
    @Inject BackendClock clock;
    @Inject AgroalDataSource datasource;
    @TempDir Path directory;
    TokenRegistry.Principal admin;Path output;
    @BeforeEach void setup() throws Exception {
        ClientProxy.unwrap(store).stopMaintenance();ClientProxy.unwrap(administrators).clock=clock;
        accounts.tx(c->{
            for(String table:List.of("tk_relation","tk_external_reference","tk_active","tk_receipt","tk_change","tk_auth_session","tk_passkey_ceremony","tk_passkey_credential","tk_recovery_link","tk_recovery_address","tk_auth_challenge","tk_api_token","tk_passkey_user","tk_locale","tk_account","tk_resource","tk_owner","tk_auth_throttle"))execute(c,"DELETE FROM "+table);
            execute(c,"UPDATE tk_auth_control SET bootstrap_completed=FALSE");return null;
        });
        String code=AccountPasswords.secret();Path file=directory.resolve("setup.json");Files.writeString(file,object().put("purpose","SETUP").put("sha256",hash(code)).put("expiresAt",Instant.now().plusSeconds(600).toString()).toString());
        ClientProxy.unwrap(accounts).setupFile=file.toString();accounts.initialize();var issued=accounts.complete(code,"instance-admin",PASSWORD,"Administrator",false);
        admin=accounts.session(issued.secret());output=Files.createDirectory(directory.resolve("operations"));
    }
    @AfterEach void reset(){ClientProxy.unwrap(accounts).setupFile="NONE";ClientProxy.unwrap(administrators).clock=clock;ClientProxy.unwrap(backups).datasource=datasource;}

    @Test void administratorMustProveCurrentCredentialAndReceivesOnlyEncryptedArchive() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);administrators.requireFresh(proof);assertFalse(proof.toString().contains(admin.digest()));
        char[] phrase="private backup creation passphrase".toCharArray(),copy=phrase.clone();Path encrypted=backups.create(proof,phrase,output);
        assertTrue(allCleared(phrase));
        try(var archive=InstanceBackupArchive.open(encrypted,copy,output,100_000_000)) {
            assertEquals(23,archive.counts().size());assertEquals(1,archive.counts().get("tk_account"));assertEquals(1,archive.counts().get("tk_auth_session"));
        }finally{Arrays.fill(copy,'\0');}
    }
    @Test void normalReadonlyManagedLegacyAndTokenExchangedSessionsCannotGainInstanceAuthority() {
        String owner=accounts.createUser(admin,"ordinary-user",PASSWORD,"Normal user",false).path("ownerId").asText();
        var normal=accounts.session(accounts.login("ordinary-user",PASSWORD,false).secret());denied(normal);
        var readOnly=accounts.tx(c->accounts.issue(c,admin.owner(),Set.of("read"),false,Instant.now(),null));denied(accounts.session(readOnly.secret()));
        var created=accounts.createToken(admin,"Admin API token",Set.of("read","write"),null);var token=accounts.managed(hash(created.path("token").asText()));denied(token);
        denied(accounts.session(accounts.exchange(token).secret()));denied(new TokenRegistry.Principal(admin.owner(),Set.of("read","write"),"LEGACY","0".repeat(64),null));
        assertNotEquals(admin.owner(),owner);
    }
    private void denied(TokenRegistry.Principal principal) {
        assertEquals(403,assertThrows(ApiFailure.class,()->administrators.require(principal)).status);
        assertEquals(403,assertThrows(ApiFailure.class,()->administrators.verify(principal,PASSWORD)).status);
    }
    @Test void failedProofAttemptsAreRateLimitedWithoutEchoingPassword() {
        for(int index=0;index<5;index++){var failure=assertThrows(ApiFailure.class,()->administrators.verify(admin,"private wrong password"));assertEquals(422,failure.status);assertEquals("INSTANCE_PROOF_REQUIRED",failure.code);assertNull(failure.getCause());}
        assertEquals(429,assertThrows(ApiFailure.class,()->administrators.verify(admin,PASSWORD)).status);
    }
    @Test void successfulProofResetsFailuresWithoutBlockingNormalRepeatedOperations() {
        for(int cycle=0;cycle<2;cycle++) {
            for(int index=0;index<4;index++)assertThrows(ApiFailure.class,()->administrators.verify(admin,"wrong password"));
            administrators.requireFresh(administrators.verify(admin,PASSWORD));
        }
    }
    @Test void expiredOrRevokedProofCannotCreateFilesAndPassphraseIsCleared() throws Exception {
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());ClientProxy.unwrap(administrators).clock=new BackendClock(){@Override public Instant now(){return now.get();}};
        var proof=administrators.verify(admin,PASSWORD);now.set(now.get().plusSeconds(301));
        char[] phrase="private passphrase on expired proof".toCharArray();assertEquals(422,assertThrows(ApiFailure.class,()->backups.create(proof,phrase,output)).status);assertTrue(allCleared(phrase));
        now.set(now.get().minusSeconds(301));accounts.logout(admin);
        assertEquals(401,assertThrows(ApiFailure.class,()->administrators.requireFresh(proof)).status);
        try(var files=Files.list(output)){assertEquals(0,files.count());}
    }
    @Test void demotionDuringBackupCannotPublishArtifact() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);
        ClientProxy.unwrap(backups).datasource=afterCommit(()->accounts.tx(c->{execute(c,"UPDATE tk_account SET administrator=FALSE,version=version+1 WHERE owner_id=?",admin.owner());return null;}));
        char[] phrase="private passphrase during demotion".toCharArray();
        assertEquals(403,assertThrows(ApiFailure.class,()->backups.create(proof,phrase,output)).status);assertTrue(allCleared(phrase));
        try(var files=Files.list(output)){assertEquals(0,files.count());}
    }
    @Test void changedConfigurationCannotPublishAMixedSnapshot() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);String previous=System.getProperty("timekeeper.package-id");
        try {
            ClientProxy.unwrap(backups).datasource=afterCommit(()->System.setProperty("timekeeper.package-id","changed-during-backup"));
            char[] phrase="private passphrase during configuration change".toCharArray();
            var failure=assertThrows(ApiFailure.class,()->backups.create(proof,phrase,output));assertEquals("INSTANCE_CONFIGURATION_CHANGED",failure.code);assertNull(failure.getCause());assertTrue(allCleared(phrase));
            try(var files=Files.list(output)){assertEquals(0,files.count());}
        }finally {if(previous==null)System.clearProperty("timekeeper.package-id");else System.setProperty("timekeeper.package-id",previous);}
    }
    private AgroalDataSource afterCommit(Runnable action) {
        var guarded=(AgroalDataSource)java.lang.reflect.Proxy.newProxyInstance(AgroalDataSource.class.getClassLoader(),new Class<?>[]{AgroalDataSource.class},(proxy,method,args)->{
            try {
                Object value=method.invoke(datasource,args);if(!method.getName().equals("getConnection"))return value;var connection=(Connection)value;
                return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(ignored,operation,arguments)->{
                    try {
                        Object result=operation.invoke(connection,arguments);
                        if(operation.getName().equals("commit"))action.run();
                        return result;
                    }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        return guarded;
    }
    private static boolean allCleared(char[] phrase){for(char value:phrase)if(value!='\0')return false;return true;}
}
