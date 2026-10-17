package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import java.util.*;
import java.time.*;
import java.nio.file.*;
import java.util.concurrent.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;

@QuarkusTest @TestProfile(AccountStoreTest.Profile.class)
class AccountStoreTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:account_store_tests");}
    }
    @Inject AccountStore accounts;
    @Inject ServerStore store;
    @Inject TokenRegistry registry;
    @TempDir Path temporary;
    private static final String PASSWORD="a sufficiently long initial passphrase";
    private String challenge(String purpose,String owner) throws Exception {
        String secret=AccountPasswords.secret();var value=JsonValues.object();value.put("sha256",JsonValues.hash(secret));value.put("purpose",purpose);
        value.put("expiresAt",Instant.now().plusSeconds(600).toString());if(owner!=null) value.put("ownerId",owner);
        Path file=temporary.resolve(UUID.randomUUID()+".json");Files.writeString(file,value.toString());
        io.quarkus.arc.ClientProxy.unwrap(accounts).setupFile=file.toString();accounts.initialize();return secret;
    }
    @BeforeEach void clear() throws Exception {
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);
        // This isolated authentication fixture deletes owners and all their
        // records. Day-boundary maintenance must finish before that reset.
        actual.stopMaintenance();
        try(var c=actual.datasource.getConnection();var s=c.createStatement()) {
            for(String table:List.of("tk_auth_session","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner","tk_auth_throttle")) s.executeUpdate("DELETE FROM "+table);
            s.executeUpdate("UPDATE tk_auth_control SET bootstrap_completed=FALSE");
        }
    }
    @AfterEach void reset() {io.quarkus.arc.ClientProxy.unwrap(accounts).setupFile="NONE";}
    @Test void oneUseSetupMultipleAccountsAndTokensRetainOwnershipAndRevokeAcrossReplicas() throws Exception {
        String owner="existing-owner-"+UUID.randomUUID();
        store.transaction(owner,()->store.service().createProject(new net.resheim.eclipse.timekeeper.service.Commands.CreateProject("Existing private project")));
        var before=store.transaction(owner,()->store.list("PROJECT"));
        String code=challenge("SETUP",owner);var issued=accounts.complete(code,"Admin",PASSWORD,"Administrator",false);
        assertEquals(owner,issued.account().get("ownerId").asText());assertTrue(issued.account().get("administrator").asBoolean());
        assertThrows(ApiFailure.class,()->accounts.complete(code,"imposter",PASSWORD,"Imposter",false));
        assertEquals(before,store.transaction(owner,()->store.list("PROJECT")));
        var admin=accounts.session(issued.secret());
        var other=accounts.createUser(admin,"Other",PASSWORD,"Other user",false);String otherOwner=other.get("ownerId").asText();assertNotEquals(owner,otherOwner);
        var second=accounts.login("OTHER",PASSWORD,true);var principal=accounts.session(second.secret());
        var token=accounts.createToken(principal,"Eclipse",Set.of("read","write"),Instant.now().plusSeconds(3600));
        String value=token.get("token").asText();var access=registry.authenticate("Bearer "+value);assertEquals(otherOwner,access.owner());
        var reply=given().header("Authorization","Bearer "+value).get("/api/v1/projects");assertEquals(200,reply.statusCode());assertTrue(JsonValues.parse(reply.asString()).get("items").isEmpty());
        var foreign=before.getFirst();assertEquals(404,given().header("Authorization","Bearer "+value).get("/api/v1/projects/"+foreign.get("id").asText()).statusCode());
        assertThrows(ApiFailure.class,()->accounts.users(principal));
        assertThrows(ApiFailure.class,()->accounts.revokeToken(admin,UUID.fromString(token.get("id").asText())));
        assertFalse(accounts.tokens(principal).toString().contains(value));assertFalse(accounts.context(principal).toString().contains(second.secret()));
        accounts.revokeToken(principal,UUID.fromString(token.get("id").asText()));assertThrows(ApiFailure.class,()->registry.authenticate("Bearer "+value));
        assertEquals(otherOwner,accounts.session(second.secret()).owner(),"Independent browser session was revoked with API token");
        var renamed=accounts.updateSelf(principal,JsonValues.etag(second.account()),"renamed","New name");assertEquals(otherOwner,renamed.get("ownerId").asText());
        accounts.updateUser(admin,otherOwner,JsonValues.etag(renamed),"renamed","New name",false,false);
        assertThrows(ApiFailure.class,()->accounts.session(second.secret()));assertThrows(ApiFailure.class,()->accounts.login("renamed",PASSWORD,false));
        accounts.updateUser(admin,otherOwner,JsonValues.etag(findUser(admin,otherOwner)),"renamed","New name",true,false);
        assertThrows(ApiFailure.class,()->accounts.session(second.secret()),"Re-enabling resurrected a revoked browser session");
        var resumed=accounts.login("renamed",PASSWORD,false);assertEquals(otherOwner,accounts.session(resumed.secret()).owner());
        assertThrows(ApiFailure.class,()->accounts.updateUser(admin,owner,JsonValues.etag(issued.account()),"admin","Administrator",false,false),"Last administrator must remain accessible");
    }
    private JsonNode findUser(TokenRegistry.Principal admin,String owner) {for(var value:accounts.users(admin).get("users")) if(value.get("ownerId").asText().equals(owner)) return value;throw new AssertionError();}
    @Test void setupCompletionIsAtomicAcrossConcurrentInstancesAndRecoveryDoesNotClaimArbitraryOwners() throws Exception {
        String code=challenge("SETUP",null);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<AccountStore.Issued>>();
            for(String username:List.of("first","second")) results.add(pool.submit(()->{ready.countDown();go.await();try{return accounts.complete(code,username,PASSWORD,username,false);}catch(ApiFailure used) {assertEquals(401,used.status);return null;}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();var successes=new ArrayList<AccountStore.Issued>();
            for(var result:results) {var value=result.get(10,TimeUnit.SECONDS);if(value!=null) successes.add(value);}assertEquals(1,successes.size());
            var issued=successes.getFirst();String owner=issued.account().get("ownerId").asText();
            var principal=accounts.session(issued.secret());var token=accounts.createToken(principal,"Before recovery",Set.of("read"),null);
            assertThrows(ApiFailure.class,()->accounts.createToken(registry.authenticate("Bearer "+token.get("token").asText()),"Escalation",Set.of("read","write"),null));
            String recovery=challenge("RECOVERY",owner);var recovered=accounts.complete(recovery,"recovered",PASSWORD+" changed","Recovered",true);
            assertEquals(owner,recovered.account().get("ownerId").asText());assertTrue(recovered.account().get("administrator").asBoolean());
            assertThrows(ApiFailure.class,()->accounts.session(issued.secret()));assertThrows(ApiFailure.class,()->registry.authenticate("Bearer "+token.get("token").asText()));
            assertThrows(ApiFailure.class,()->accounts.complete(recovery,"repeat",PASSWORD,"Repeated",true));
            assertThrows(ApiFailure.class,()->accounts.login("not-an-owner",PASSWORD,false));
            var loggedIn=accounts.login("recovered",PASSWORD+" changed",false);var current=accounts.session(loggedIn.secret());
            assertThrows(ApiFailure.class,()->accounts.csrf(current,"wrong"));accounts.csrf(current,loggedIn.csrf());
            accounts.changePassword(current,PASSWORD+" changed",PASSWORD+" again");assertThrows(ApiFailure.class,()->accounts.session(loggedIn.secret()));
        }
    }
    @Test void expiryIdleTimeoutAndFailedLoginThrottlesCannotBeBypassed() throws Exception {
        var setup=accounts.complete(challenge("SETUP",null),"expiry-admin",PASSWORD,"Expiry administrator",false);
        accounts.initialize();assertFalse(accounts.status().get("setupRequired").asBoolean());
        var principal=accounts.session(setup.secret());
        var token=accounts.createToken(principal,"Expiring",Set.of("read","write"),Instant.now().plusSeconds(3600));
        var normal=accounts.login("expiry-admin",PASSWORD,false);var persistent=accounts.login("expiry-admin",PASSWORD,true);
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);
        try(var c=actual.datasource.getConnection()) {
            try(var statement=c.prepareStatement("UPDATE tk_auth_session SET last_seen_at=? WHERE digest=?")) {
                statement.setString(1,Instant.now().minus(Duration.ofHours(1)).toString());statement.setString(2,JsonValues.hash(normal.secret()));assertEquals(1,statement.executeUpdate());
                statement.setString(2,JsonValues.hash(persistent.secret()));assertEquals(1,statement.executeUpdate());
            }
            assertThrows(ApiFailure.class,()->accounts.session(normal.secret()));assertEquals(principal.owner(),accounts.session(persistent.secret()).owner());
            try(var statement=c.prepareStatement("UPDATE tk_auth_session SET expires_at=? WHERE digest=?")) {statement.setString(1,Instant.now().minusSeconds(1).toString());statement.setString(2,JsonValues.hash(persistent.secret()));statement.executeUpdate();}
            assertThrows(ApiFailure.class,()->accounts.session(persistent.secret()));
            try(var statement=c.prepareStatement("UPDATE tk_api_token SET expires_at=? WHERE id=?")) {statement.setString(1,Instant.now().minusSeconds(1).toString());statement.setObject(2,UUID.fromString(token.get("id").asText()));statement.executeUpdate();}
            assertThrows(ApiFailure.class,()->registry.authenticate("Bearer "+token.get("token").asText()));
        }
        for(int attempt=0;attempt<10;attempt++) assertThrows(ApiFailure.class,()->accounts.login("missing-user",PASSWORD,false));
        var blocked=assertThrows(ApiFailure.class,()->accounts.login("missing-user",PASSWORD,false));assertEquals(429,blocked.status);
    }

}
