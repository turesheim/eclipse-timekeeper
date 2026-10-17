package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(PasskeyStoreTest.Profile.class)
class PasskeyStoreTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:passkey_store_tests");}
    }
    private static final String RP="timekeeper.example",ORIGIN="https://timekeeper.example:8443",PASSWORD="An actual fixture password phrase";
    @Inject AccountStore accounts;
    PasskeyStore passkeys;
    record Browser(String owner,String browser,AccountStore.Issued session,TokenRegistry.Principal principal) { }
    @BeforeEach void initialize() {
        accounts.tx(c->{lock(c);
            for(String table:List.of("tk_auth_session","tk_passkey_ceremony","tk_passkey_credential","tk_passkey_user","tk_recovery_link","tk_recovery_address","tk_api_token","tk_auth_challenge","tk_auth_throttle")) execute(c,"DELETE FROM "+table);
            execute(c,"DELETE FROM tk_account WHERE owner_id IN ('pk-alice','pk-bob')");execute(c,"UPDATE tk_recovery_control SET origin=NULL,epoch=0");
            for(String owner:List.of("pk-alice","pk-bob")) {
                execute(c,"INSERT INTO tk_owner(id,zone_id,version,revision,cursor_secret) SELECT ?,'UTC',0,0,'cursor' WHERE NOT EXISTS(SELECT 1 FROM tk_owner WHERE id=?)",owner,owner);
                execute(c,"INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES(?,?,?,?,'ENABLED',FALSE,0)",owner,owner,AccountPasswords.hash(PASSWORD),owner);
            }return null;
        });
        var origin=new RecoveryOrigin();origin.accounts=accounts;origin.configured=ORIGIN;origin.epoch=1;
        var realm=new PasskeyRealm();realm.origin=origin;realm.configuredEnabled=true;realm.configuredRp="AUTO";realm.initialize();
        passkeys=new PasskeyStore();passkeys.accounts=accounts;passkeys.realm=realm;
    }
    private Browser browser(String owner) {
        String binding=AccountPasswords.secret();var session=accounts.login(owner,PASSWORD,false);
        accounts.tx(c->{execute(c,"UPDATE tk_auth_session SET browser_digest=?,origin_epoch=1 WHERE digest=?",hash(binding),hash(session.secret()));return null;});
        return new Browser(owner,binding,session,accounts.session(session.secret()));
    }
    private String request(JsonNode options) {
        return accounts.tx(c->{try(var statement=query(c,"SELECT payload FROM tk_passkey_ceremony WHERE digest=?",hash(options.path("ceremony").asText()));var rows=statement.executeQuery()) {
            assertTrue(rows.next());return parse(rows.getString(1)).path("data").path("request").asText();
        }catch(java.sql.SQLException failure) {throw new AssertionError(failure);}});
    }
    private ObjectNode register(Browser browser,WebAuthnProtocolTest.Authenticator key,JsonNode proof) throws Exception {
        var input=((ObjectNode)proof).deepCopy().put("name","My passkey");var options=passkeys.registerOptions(browser.principal(),browser.browser(),browser.session().secret(),"fixture",input);
        var creation=PublicKeyCredentialCreationOptions.fromJson(request(options));
        var finish=object().put("ceremony",options.path("ceremony").asText());finish.set("credential",parse(key.registration(creation,RP,ORIGIN,0x5d)));
        return passkeys.register(browser.principal(),browser.browser(),browser.session().secret(),"fixture",finish);
    }
    private ObjectNode assertion(JsonNode options,WebAuthnProtocolTest.Authenticator key,String owner,boolean badSignature) throws Exception {
        var handle=accounts.tx(c->new PasskeyCredentials(c,RP).getUserHandleForUsername(owner).orElseThrow());
        var finish=object().put("ceremony",options.path("ceremony").asText());finish.set("credential",parse(key.assertion(AssertionRequest.fromJson(request(options)),RP,ORIGIN,0x1d,0,handle,badSignature)));return finish;
    }
    private String proof(Browser browser,JsonNode action,WebAuthnProtocolTest.Authenticator key) throws Exception {
        var options=passkeys.managementOptions(browser.principal(),browser.browser(),browser.session().secret(),"fixture",action);
        return passkeys.managementProof(browser.principal(),browser.browser(),browser.session().secret(),"fixture",assertion(options,key,browser.owner(),false)).path("proof").asText();
    }
    @Test void passwordProofEnrollmentDiscoverableRememberedSignInAndRemovalUseExistingSessions() throws Exception {
        var alice=browser("pk-alice");var key=new WebAuthnProtocolTest.Authenticator("stored login key");var first=register(alice,key,object().put("currentPassword",PASSWORD));
        assertEquals(1,passkeys.list(alice.principal()).path("items").size());
        String binding=AccountPasswords.secret();var options=passkeys.loginOptions(binding,null,"fixture",true);assertFalse(options.path("publicKey").has("allowCredentials"));
        var issued=passkeys.login(binding,null,"fixture",assertion(options,key,alice.owner(),false));assertEquals(alice.owner(),issued.account().path("ownerId").asText());assertTrue(issued.persistent());
        assertTrue(Duration.between(Instant.now(),issued.expires()).toDays()>=29);assertTrue(accounts.context(accounts.session(issued.secret())).path("remember").asBoolean());
        var renamed=passkeys.rename(alice.principal(),uuid(first.get("id")),etag(first),"Synced personal key");assertEquals(1,renamed.path("version").asLong());
        passkeys.remove(alice.principal(),uuid(first.get("id")),etag(renamed),alice.browser(),alice.session().secret(),"fixture",object().put("currentPassword",PASSWORD));
        assertEquals(0,passkeys.list(alice.principal()).path("items").size());assertThrows(ApiFailure.class,()->accounts.session(issued.secret()));
        assertEquals(alice.owner(),accounts.session(alice.session().secret()).owner(),"Removing a key does not revoke independent password sessions");
    }
    @Test void freshPasskeyProofIsOneUseActionAndTargetBoundAndTokenSessionsCannotEnroll() throws Exception {
        var alice=browser("pk-alice");var firstKey=new WebAuthnProtocolTest.Authenticator("proof source key");var first=register(alice,firstKey,object().put("currentPassword",PASSWORD));
        String add=proof(alice,object().put("action","ADD"),firstKey);var secondKey=new WebAuthnProtocolTest.Authenticator("added by fresh passkey proof");var second=register(alice,secondKey,object().put("proof",add));
        assertEquals(403,assertThrows(ApiFailure.class,()->passkeys.registerOptions(alice.principal(),alice.browser(),alice.session().secret(),"fixture",object().put("name","Replay").put("proof",add))).status);
        var remove=object().put("action","REMOVE").put("id",second.path("id").asText()).put("etag",etag(second));String bound=proof(alice,remove,firstKey);
        assertEquals(403,assertThrows(ApiFailure.class,()->passkeys.remove(alice.principal(),uuid(first.get("id")),etag(first),alice.browser(),alice.session().secret(),"fixture",object().put("proof",bound))).status);
        assertEquals(2,passkeys.list(alice.principal()).path("items").size());
        var token=accounts.createToken(alice.principal(),"Eclipse",Set.of("read","write"),null);var principal=accounts.managed(hash(token.path("token").asText()));
        assertEquals(403,assertThrows(ApiFailure.class,()->passkeys.registerOptions(principal,alice.browser(),alice.session().secret(),"fixture",object().put("name","Forbidden").put("currentPassword",PASSWORD))).status);
        var exchanged=accounts.exchange(principal);assertEquals(403,assertThrows(ApiFailure.class,()->passkeys.list(accounts.session(exchanged.secret()))).status);
    }
    @Test void rejectedSignaturesCommitConsumptionAndBrowserOwnerSwitchesInvalidateOlderFlowsAndSessions() throws Exception {
        var alice=browser("pk-alice");var key=new WebAuthnProtocolTest.Authenticator("login replay key");register(alice,key,object().put("currentPassword",PASSWORD));
        var options=passkeys.loginOptions(alice.browser(),alice.session().secret(),"fixture",false);
        assertEquals(401,assertThrows(ApiFailure.class,()->passkeys.login(alice.browser(),alice.session().secret(),"fixture",assertion(options,key,alice.owner(),true))).status);
        assertEquals(401,assertThrows(ApiFailure.class,()->passkeys.login(alice.browser(),alice.session().secret(),"fixture",assertion(options,key,alice.owner(),false))).status);
        var bob=browser("pk-bob");var bobKey=new WebAuthnProtocolTest.Authenticator("independent account key");register(bob,bobKey,object().put("currentPassword",PASSWORD));
        var old=passkeys.loginOptions(alice.browser(),alice.session().secret(),"fixture",false);var next=passkeys.loginOptions(alice.browser(),alice.session().secret(),"fixture",false);
        var switched=passkeys.login(alice.browser(),alice.session().secret(),"fixture",assertion(next,bobKey,bob.owner(),false));assertEquals(bob.owner(),switched.account().path("ownerId").asText());
        assertThrows(ApiFailure.class,()->accounts.session(alice.session().secret()));assertEquals(401,assertThrows(ApiFailure.class,()->passkeys.login(alice.browser(),alice.session().secret(),"fixture",assertion(old,key,alice.owner(),false))).status);
        assertEquals(1,passkeys.list(accounts.session(switched.secret())).path("items").size());
    }
    @Test void wrongPasswordsAndForeignManagementKeepTheCurrentSessionAndCannotConsumeAnotherOwnersRegistration() throws Exception {
        var alice=browser("pk-alice");var bob=browser("pk-bob");var key=new WebAuthnProtocolTest.Authenticator("owner-bound registration");
        assertEquals(403,assertThrows(ApiFailure.class,()->passkeys.registerOptions(alice.principal(),alice.browser(),alice.session().secret(),"fixture",object().put("name","Wrong proof").put("currentPassword","wrong"))).status);
        var options=passkeys.registerOptions(alice.principal(),alice.browser(),alice.session().secret(),"fixture",object().put("name","Owner key").put("currentPassword",PASSWORD));
        var finish=object().put("ceremony",options.path("ceremony").asText());finish.set("credential",parse(key.registration(PublicKeyCredentialCreationOptions.fromJson(request(options)),RP,ORIGIN,0x5d)));
        assertEquals(403,assertThrows(ApiFailure.class,()->passkeys.register(bob.principal(),bob.browser(),bob.session().secret(),"fixture",finish)).status);
        var saved=passkeys.register(alice.principal(),alice.browser(),alice.session().secret(),"fixture",finish);assertEquals(alice.owner(),saved.path("ownerId").asText());
        assertEquals(404,assertThrows(ApiFailure.class,()->passkeys.rename(bob.principal(),uuid(saved.get("id")),etag(saved),"Foreign change")).status);
        assertEquals(0,passkeys.list(bob.principal()).path("items").size());assertEquals(alice.owner(),accounts.session(alice.session().secret()).owner());
    }
    @Test void passwordChangesRetainKeysButOperatorRecoveryRevokesKeysSessionsAndPendingFlows() throws Exception {
        var alice=browser("pk-alice");var key=new WebAuthnProtocolTest.Authenticator("retained until account recovery");register(alice,key,object().put("currentPassword",PASSWORD));
        var pending=passkeys.registerOptions(alice.principal(),alice.browser(),alice.session().secret(),"fixture",object().put("name","Before password change").put("currentPassword",PASSWORD));
        accounts.changePassword(alice.principal(),PASSWORD,"Changed fixture password phrase");
        assertThrows(ApiFailure.class,()->accounts.session(alice.session().secret()));
        accounts.tx(c->{try(var statement=query(c,"SELECT consumed FROM tk_passkey_ceremony WHERE digest=?",hash(pending.path("ceremony").asText()));var rows=statement.executeQuery()) {rows.next();assertTrue(rows.getBoolean(1));}catch(java.sql.SQLException failure) {throw new AssertionError(failure);}return null;});
        String binding=AccountPasswords.secret();var options=passkeys.loginOptions(binding,null,"fixture",false);
        var issued=passkeys.login(binding,null,"fixture",assertion(options,key,alice.owner(),false));assertEquals(alice.owner(),accounts.session(issued.secret()).owner());
        String recovery=AccountPasswords.secret();accounts.tx(c->{execute(c,"INSERT INTO tk_auth_challenge VALUES(?,'RECOVERY',?,?,FALSE)",hash(recovery),alice.owner(),Instant.now().plusSeconds(300).toString());return null;});
        accounts.complete(recovery,alice.owner(),"Recovered fixture password phrase",alice.owner(),true);
        assertThrows(ApiFailure.class,()->accounts.session(issued.secret()));
        var after=passkeys.loginOptions(binding,null,"fixture",false);assertEquals(401,assertThrows(ApiFailure.class,()->passkeys.login(binding,null,"fixture",assertion(after,key,alice.owner(),false))).status);
        assertEquals(alice.owner(),accounts.login(alice.owner(),"Recovered fixture password phrase",false).account().path("ownerId").asText());
    }
    @Test void randomExpiredAndReplayedLoginNoncesCannotBypassSharedAttemptLimits() {
        String binding=AccountPasswords.secret();var invalid=object().set("credential",object());((ObjectNode)invalid).put("ceremony",AccountPasswords.secret());
        for(int n=0;n<20;n++) assertEquals(401,assertThrows(ApiFailure.class,()->passkeys.login(binding,null,"fixture",invalid)).status);
        assertEquals(429,assertThrows(ApiFailure.class,()->passkeys.login(binding,null,"fixture",invalid)).status);
    }
}
