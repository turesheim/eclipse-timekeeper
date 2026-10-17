package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(EmailRecoveryStoreTest.Profile.class)
class EmailRecoveryStoreTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {
            var config=new HashMap<String,String>();
            String database=System.getenv("TIMEKEEPER_TEST_RECOVERY_DB_URL");
            config.put("quarkus.datasource.jdbc.url",database==null ? "jdbc:h2:mem:email_recovery_tests" : database);
            if(database!=null) {
                config.put("quarkus.datasource.db-kind","postgresql");
                config.put("quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"));
                config.put("quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
            }
            return config;
        }
    }
    @Inject AccountStore accounts;
    @Inject TokenRegistry registry;
    @Inject ServerStore store;
    @TempDir Path temporary;
    EmailRecoveryStore recovery;
    RecoveryOrigin origin;
    PasskeyRealm originalRealm;
    BrowserSignIns signIns;
    TokenRegistry.Principal alice,bob;
    AccountStore.Issued aliceSession,bobSession;
    static final String PASSWORD="a sufficiently long initial passphrase";
    static final String NEXT="another sufficiently long password";

    @BeforeEach void setup() throws Exception {
        // This fixture removes all owners/resources between cases, unlike any
        // account operation. Quiesce periodic domain writes before resetting it.
        ClientProxy.unwrap(store).stopMaintenance();
        accounts.tx(c->{lock(c);
            for(String table:List.of("tk_recovery_link","tk_recovery_address","tk_passkey_ceremony","tk_auth_session","tk_passkey_credential","tk_passkey_user","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner","tk_auth_throttle")) execute(c,"DELETE FROM "+table);
            execute(c,"UPDATE tk_auth_control SET bootstrap_completed=FALSE");
            execute(c,"UPDATE tk_recovery_control SET origin=NULL,epoch=0 WHERE id=1");return null;
        });
        // Establish the trusted origin before opening sessions: a cutover
        // deliberately invalidates every session issued at the previous origin.
        origin=new RecoveryOrigin();origin.accounts=accounts;origin.configured="https://vpn.example.org:8443";origin.epoch=1;origin.initialize();
        originalRealm=ClientProxy.unwrap(accounts).realm;useOrigin(origin);
        aliceSession=accounts.complete(challenge("SETUP",null),"alice",PASSWORD,"Alice",false,null,issuer(BrowserSignIns.Method.SETUP));alice=accounts.session(aliceSession.secret());
        accounts.createUser(alice,"bob",PASSWORD,"Bob",false);bobSession=login("bob",PASSWORD);bob=accounts.session(bobSession.secret());
        recovery=replica(origin);
    }
    @AfterEach void resetSetupFile() {ClientProxy.unwrap(accounts).setupFile="NONE";ClientProxy.unwrap(accounts).realm=originalRealm;}
    private void useOrigin(RecoveryOrigin origin) {
        var realm=new PasskeyRealm();realm.origin=origin;realm.configuredEnabled=true;realm.configuredRp="AUTO";
        ClientProxy.unwrap(accounts).realm=realm;signIns=new BrowserSignIns();signIns.accounts=accounts;signIns.realm=realm;
    }
    private AccountStore.SessionIssuer issuer(BrowserSignIns.Method method) {
        return signIns.issuer(signIns.begin(method,AccountPasswords.secret(),null,"fixture"));
    }
    private AccountStore.Issued login(String username,String password) {return accounts.login(username,password,false,issuer(BrowserSignIns.Method.PASSWORD));}
    private EmailRecoveryStore replica(RecoveryOrigin origin) {
        var result=new EmailRecoveryStore();result.accounts=accounts;result.origins=origin;return result;
    }
    private String challenge(String purpose,String owner) throws Exception {
        String code=AccountPasswords.secret();var node=object();node.put("sha256",hash(code));node.put("purpose",purpose);node.put("expiresAt",Instant.now().plusSeconds(600).toString());if(owner!=null) node.put("ownerId",owner);
        var file=temporary.resolve("challenge.json");Files.writeString(file,node.toString());ClientProxy.unwrap(accounts).setupFile=file.toString();accounts.initialize();return code;
    }
    private JsonNode slot(TokenRegistry.Principal principal,String selected) {
        for(var value:recovery.addresses(principal).get("items")) if(value.path("slot").asText().equals(selected)) return value;throw new AssertionError();
    }
    private EmailRecoveryStore.Change change(TokenRegistry.Principal principal,String selected,String email) {
        return recovery.change(principal,selected,email,PASSWORD,etag(slot(principal,selected)));
    }
    private EmailRecoveryStore.Delivery enroll(TokenRegistry.Principal principal,String selected,String email) {
        var delivery=change(principal,selected,email).deliveries().getFirst();recovery.verify(delivery.secret());return delivery;
    }

    @Test void primaryAndBackupAreIndependentlyVerifiedAndOwnerBound() {
        var primary=change(alice,"PRIMARY","Alice+time@EXAMPLE.org").deliveries().getFirst();
        assertEquals("Alice+time@example.org",primary.address());assertEquals(List.of(),recovery.requestReset("alice"));
        recovery.preview(primary.secret(),"VERIFY");recovery.preview(primary.secret(),"VERIFY");recovery.verify(primary.secret());
        assertThrows(ApiFailure.class,()->recovery.verify(primary.secret()));
        assertEquals(1,recovery.requestReset("alice").size());
        var backup=change(alice,"BACKUP","backup@example.org").deliveries().getFirst();
        assertEquals(1,recovery.requestReset("alice").size(),"Unverified backup cannot reset");
        recovery.verify(backup.secret());
        assertEquals(List.of(),recovery.requestReset("alice"),"Repeated requests are throttled without exposing eligibility");
        accounts.tx(c->{execute(c,"UPDATE tk_auth_throttle SET window_start='2000-01-01T00:00:00Z' WHERE id=?",hash("recovery:reset-user:alice"));return null;});
        var deliveries=recovery.requestReset("ALICE");assertEquals(2,deliveries.size());
        assertEquals(Set.of("Alice+time@example.org","backup@example.org"),Set.of(deliveries.get(0).address(),deliveries.get(1).address()));
        assertTrue(slot(bob,"PRIMARY").path("verifiedAddress").isNull());
        assertThrows(ApiFailure.class,()->recovery.change(bob,"PRIMARY","attacker@example.org","wrong password",etag(slot(bob,"PRIMARY"))));
        assertThrows(ApiFailure.class,()->change(alice,"BACKUP","alice+time@EXAMPLE.ORG"),"The same mailbox cannot fill both roles");
        assertEquals(List.of(),recovery.requestReset("unknown"));assertEquals(List.of(),recovery.requestReset("bob"));
        assertEquals("Recovery email delivery",deliveries.getFirst().toString());
        assertFalse(recovery.addresses(alice).toString().contains(deliveries.getFirst().secret()));
    }

    @Test void usingBackupRevokesAllOwnersResetLinksSessionsAndTokensButPreservesOtherAccountsAndWork() {
        enroll(alice,"PRIMARY","alice@example.org");enroll(alice,"BACKUP","backup@example.org");
        var token=accounts.createToken(alice,"Eclipse",Set.of("read","write"),null);
        store.transaction(alice.owner(),()->store.service().createProject(new net.resheim.eclipse.timekeeper.service.Commands.CreateProject("Private retained project")));
        var before=store.transaction(alice.owner(),()->store.list("PROJECT"));
        var deliveries=recovery.requestReset("alice");var backup=deliveries.stream().filter(d->d.address().equals("backup@example.org")).findFirst().orElseThrow();
        recovery.preview(backup.secret(),"RESET");recovery.preview(backup.secret(),"RESET");
        var completed=recovery.reset(backup.secret(),NEXT);assertEquals(alice.owner(),completed.owner());assertEquals(2,completed.notifyAddresses().size());
        for(var delivery:deliveries) assertThrows(ApiFailure.class,()->recovery.reset(delivery.secret(),NEXT));
        assertThrows(ApiFailure.class,()->accounts.session(aliceSession.secret()));
        assertThrows(ApiFailure.class,()->registry.authenticate("Bearer "+token.path("token").asText()));
        assertThrows(ApiFailure.class,()->accounts.login("alice",PASSWORD,false));
        var resumed=login("alice",NEXT);assertEquals(alice.owner(),resumed.account().path("ownerId").asText());assertTrue(resumed.account().path("administrator").asBoolean());
        assertEquals(before,store.transaction(alice.owner(),()->store.list("PROJECT")));
        assertEquals(bob.owner(),accounts.session(bobSession.secret()).owner());
    }

    @Test void replacementKeepsPreviousVerifiedMailboxUntilConfirmationAndRemovalFencesOutstandingLinks() {
        enroll(alice,"PRIMARY","old@example.org");enroll(alice,"BACKUP","backup@example.org");
        var replacement=change(alice,"PRIMARY","new@example.org");assertTrue(replacement.notifyAddresses().contains("old@example.org"));
        var prior=recovery.requestReset("alice");assertTrue(prior.stream().anyMatch(d->d.address().equals("old@example.org")));assertFalse(prior.stream().anyMatch(d->d.address().equals("new@example.org")));
        var verified=recovery.verify(replacement.deliveries().getFirst().secret());assertTrue(verified.notifyAddresses().contains("old@example.org"));
        for(var delivery:prior) assertFalse(recovery.deliverable(delivery));
        assertEquals("new@example.org",slot(alice,"PRIMARY").path("verifiedAddress").asText());
        var reset=recovery.requestReset("alice");change(alice,"BACKUP",null);
        for(var delivery:reset) assertFalse(recovery.deliverable(delivery));
        assertTrue(slot(alice,"BACKUP").path("verifiedAddress").isNull());
        assertThrows(ApiFailure.class,()->change(alice,"PRIMARY",null));
        assertThrows(ApiFailure.class,()->recovery.change(alice,"PRIMARY","stale@example.org",PASSWORD,"\"stale\""));
    }

    @Test void expiryPurposeTamperingDisabledAccountsAndPasswordChangesNeverBypassChecks() {
        var verification=change(bob,"PRIMARY","bob@example.org").deliveries().getFirst();
        assertThrows(ApiFailure.class,()->recovery.reset(verification.secret(),NEXT));
        recovery.verify(verification.secret());var reset=recovery.requestReset("bob").getFirst();
        assertThrows(ApiFailure.class,()->recovery.verify(reset.secret()));assertThrows(ApiFailure.class,()->recovery.reset(AccountPasswords.secret(),NEXT));
        accounts.tx(c->{execute(c,"UPDATE tk_recovery_link SET expires_at='2000-01-01T00:00:00Z' WHERE digest=?",hash(reset.secret()));return null;});
        assertThrows(ApiFailure.class,()->recovery.reset(reset.secret(),NEXT));
        var pending=recovery.requestReset("bob").getFirst();var previous=accounts.account(bob);
        accounts.updateUser(alice,bob.owner(),etag(previous),"bob","Bob",false,false);
        assertFalse(recovery.deliverable(pending));assertEquals(List.of(),recovery.requestReset("bob"));
        assertThrows(ApiFailure.class,()->recovery.reset(pending.secret(),NEXT));
        enroll(alice,"PRIMARY","alice@example.org");var beforeChange=recovery.requestReset("alice").getFirst();
        accounts.changePassword(alice,PASSWORD,NEXT);assertFalse(recovery.deliverable(beforeChange));
    }

    @Test void concurrentCompletionAndRestartUseSharedAuthority() throws Exception {
        enroll(alice,"PRIMARY","alice@example.org");enroll(alice,"BACKUP","backup@example.org");
        var links=recovery.requestReset("alice");var another=replica(origin);var results=new ArrayList<Boolean>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{try {recovery.reset(links.get(0).secret(),NEXT);return true;}catch(ApiFailure used) {assertEquals(401,used.status);return false;}});
            var second=pool.submit(()->{try {another.reset(links.get(1).secret(),NEXT);return true;}catch(ApiFailure used) {assertEquals(401,used.status);return false;}});
            results.add(first.get(20,TimeUnit.SECONDS));results.add(second.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count());
        assertEquals(alice.owner(),login("alice",NEXT).account().path("ownerId").asText());
        assertFalse(another.deliverable(links.getFirst()));
    }

    @Test void newHostnamePreservesVerifiedAddressesAndCannotReviveOldLinksOnRollback() {
        enroll(alice,"PRIMARY","alice@example.org");var old=recovery.requestReset("alice").getFirst();
        var external=new RecoveryOrigin();external.accounts=accounts;external.configured="https://time.example.org";external.epoch=2;external.initialize();var moved=replica(external);
        assertFalse(recovery.deliverable(old));assertThrows(ApiFailure.class,()->moved.preview(old.secret(),"RESET"));
        var fresh=moved.requestReset("alice").getFirst();assertEquals("https://time.example.org",fresh.origin());
        moved.reset(fresh.secret(),NEXT);useOrigin(external);assertEquals(alice.owner(),login("alice",NEXT).account().path("ownerId").asText());
        assertThrows(IllegalStateException.class,origin::initialize);
    }

    @Test void operatorRecoveryInvalidatesPendingEmailLinks() throws Exception {
        enroll(alice,"PRIMARY","alice@example.org");var link=recovery.requestReset("alice").getFirst();
        accounts.complete(challenge("RECOVERY",alice.owner()),"alice",NEXT,"Alice",true);
        assertFalse(recovery.deliverable(link));assertThrows(ApiFailure.class,()->recovery.reset(link.secret(),NEXT));
    }
}
