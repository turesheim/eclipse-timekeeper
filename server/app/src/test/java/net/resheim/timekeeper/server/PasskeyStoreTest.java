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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(PasskeyStoreTest.Profile.class)
class PasskeyStoreTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {
            var config=new HashMap<String,String>();String database=System.getenv("TIMEKEEPER_TEST_PASSKEY_DB_URL");
            config.put("quarkus.datasource.jdbc.url",database==null ? "jdbc:h2:mem:passkey_store_tests" : database);
            if(database!=null) {
                config.put("quarkus.datasource.db-kind","postgresql");
                config.put("quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"));
                config.put("quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
            }
            return config;
        }
    }
    private static final String RP="timekeeper.example",ORIGIN="https://timekeeper.example:8443",PASSWORD="An actual fixture password phrase";
    @Inject AccountStore accounts;
    PasskeyStore passkeys;
    BrowserSignIns signIns;
    PasskeyRealm originalRealm;
    record Browser(String owner,String browser,AccountStore.Issued session,TokenRegistry.Principal principal) { }
    @BeforeEach void initialize() {
        originalRealm=io.quarkus.arc.ClientProxy.unwrap(accounts).realm;
        accounts.tx(c->{lock(c);
            for(String table:List.of("tk_auth_session","tk_passkey_ceremony","tk_passkey_credential","tk_passkey_user","tk_recovery_link","tk_recovery_address","tk_api_token","tk_auth_challenge","tk_auth_throttle")) execute(c,"DELETE FROM "+table);
            execute(c,"UPDATE tk_recovery_control SET origin=NULL,epoch=0");
            for(String owner:List.of("pk-alice","pk-bob")) {
                execute(c,"INSERT INTO tk_owner(id,zone_id,version,revision,cursor_secret) SELECT ?,'UTC',0,0,'cursor' WHERE NOT EXISTS(SELECT 1 FROM tk_owner WHERE id=?)",owner,owner);
                String password=AccountPasswords.hash(PASSWORD);
                // Maintenance holds an owner lock while initializing accounts.
                // Reinsertion would need an FK owner lock after locking the
                // account, reversing that order. Reset the existing row in place.
                if(execute(c,"UPDATE tk_account SET username=?,password_hash=?,display_name=?,state='ENABLED',administrator=FALSE,version=0 WHERE owner_id=?",owner,password,owner,owner)==0)
                    execute(c,"INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES(?,?,?,?,'ENABLED',FALSE,0)",owner,owner,password,owner);
            }return null;
        });
        var origin=new RecoveryOrigin();origin.accounts=accounts;origin.configured=ORIGIN;origin.epoch=1;
        var realm=new PasskeyRealm();realm.origin=origin;realm.configuredEnabled=true;realm.configuredRp="AUTO";realm.initialize();
        io.quarkus.arc.ClientProxy.unwrap(accounts).realm=realm;
        signIns=new BrowserSignIns();signIns.accounts=accounts;signIns.realm=realm;
        passkeys=new PasskeyStore();passkeys.accounts=accounts;passkeys.realm=realm;passkeys.browserSignIns=signIns;
    }
    @AfterEach void restoreRealm() {io.quarkus.arc.ClientProxy.unwrap(accounts).realm=originalRealm;}
    private Browser browser(String owner) {
        String binding=AccountPasswords.secret();var attempt=signIns.begin(BrowserSignIns.Method.PASSWORD,binding,null,"fixture");
        var session=accounts.login(owner,PASSWORD,false,signIns.issuer(attempt));
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
        var exchange=signIns.begin(BrowserSignIns.Method.TOKEN,AccountPasswords.secret(),null,"fixture");
        var exchanged=accounts.exchange(principal,signIns.issuer(exchange));assertEquals(403,assertThrows(ApiFailure.class,()->passkeys.list(accounts.session(exchanged.secret()))).status);
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
    @Test void aDelayedPasswordRequestCannotReplaceANewerPasskeySignInAndTheReverseIsAlsoFenced() throws Exception {
        var alice=browser("pk-alice");var key=new WebAuthnProtocolTest.Authenticator("password/passkey race");register(alice,key,object().put("currentPassword",PASSWORD));
        var olderPassword=signIns.begin(BrowserSignIns.Method.PASSWORD,alice.browser(),alice.session().secret(),"fixture");
        var keyOptions=passkeys.loginOptions(alice.browser(),alice.session().secret(),"fixture",true);
        var newerPasskey=passkeys.login(alice.browser(),alice.session().secret(),"fixture",assertion(keyOptions,key,alice.owner(),false));
        assertEquals(401,assertThrows(ApiFailure.class,()->accounts.login("pk-bob",PASSWORD,false,signIns.issuer(olderPassword))).status);
        assertEquals(alice.owner(),accounts.session(newerPasskey.secret()).owner());
        var olderPasskey=passkeys.loginOptions(alice.browser(),newerPasskey.secret(),"fixture",false);
        var nextPassword=signIns.begin(BrowserSignIns.Method.PASSWORD,alice.browser(),newerPasskey.secret(),"fixture");
        var newerPassword=accounts.login("pk-bob",PASSWORD,true,signIns.issuer(nextPassword));
        assertEquals("pk-bob",accounts.session(newerPassword.secret()).owner());assertTrue(newerPassword.persistent());
        assertThrows(ApiFailure.class,()->accounts.session(newerPasskey.secret()));
        assertEquals(401,assertThrows(ApiFailure.class,()->passkeys.login(alice.browser(),newerPasskey.secret(),"fixture",assertion(olderPasskey,key,alice.owner(),false))).status);
        assertBrowserSession(alice.browser(),newerPassword,1);
    }
    private void assertBrowserSession(String browser,AccountStore.Issued issued,long epoch) {
        accounts.tx(c->{try(var statement=query(c,"SELECT digest,origin_epoch FROM tk_auth_session WHERE browser_digest=?",hash(browser));var rows=statement.executeQuery()) {
            assertTrue(rows.next());assertEquals(hash(issued.secret()),rows.getString(1));assertEquals(epoch,rows.getLong(2));assertFalse(rows.next());
        }catch(java.sql.SQLException failure) {throw new AssertionError(failure);}return null;});
    }
    @Test void passwordAttemptIsBrowserAndMethodBoundAndCannotBeConvertedIntoPasskeyProof() throws Exception {
        String binding=AccountPasswords.secret();var attempt=signIns.begin(BrowserSignIns.Method.PASSWORD,binding,null,"fixture");
        var foreign=new BrowserSignIns.Attempt(attempt.method(),new PasskeyCeremonies.Scope(AccountPasswords.secret(),null,ORIGIN,RP,1),attempt.nonce());
        assertEquals(401,assertThrows(ApiFailure.class,()->accounts.login("pk-bob",PASSWORD,false,signIns.issuer(foreign))).status);
        var issued=accounts.login("pk-alice",PASSWORD,false,signIns.issuer(attempt));assertBrowserSession(binding,issued,1);
        assertEquals(401,assertThrows(ApiFailure.class,()->accounts.login("pk-bob",PASSWORD,false,signIns.issuer(attempt))).status);
        var next=signIns.begin(BrowserSignIns.Method.PASSWORD,binding,issued.secret(),"fixture");
        var invalid=object().put("ceremony",next.nonce());invalid.set("credential",object());
        assertEquals(401,assertThrows(ApiFailure.class,()->passkeys.login(binding,issued.secret(),"fixture",invalid)).status);
        assertEquals(401,assertThrows(ApiFailure.class,()->accounts.login("pk-bob",PASSWORD,false,signIns.issuer(next))).status,"Wrong ceremony method is consumed before rejecting");
        assertFalse(next.toString().contains(binding));assertFalse(next.toString().contains(next.nonce()));assertFalse(next.scope().toString().contains(binding));assertFalse(issued.toString().contains(issued.secret()));
    }
    @Test void aDelayedRecoveryRollsBackAccountAndKeyChangesAfterTheBrowserSignsIntoAnotherAccount() throws Exception {
        var alice=browser("pk-alice");var aliceKey=new WebAuthnProtocolTest.Authenticator("account recovery rollback");register(alice,aliceKey,object().put("currentPassword",PASSWORD));
        var bob=browser("pk-bob");var bobKey=new WebAuthnProtocolTest.Authenticator("new browser account");register(bob,bobKey,object().put("currentPassword",PASSWORD));
        String code=AccountPasswords.secret();accounts.tx(c->{execute(c,"INSERT INTO tk_auth_challenge VALUES(?,'RECOVERY',?,?,FALSE)",hash(code),alice.owner(),Instant.now().plusSeconds(300).toString());return null;});
        var delayed=signIns.begin(BrowserSignIns.Method.RECOVERY,alice.browser(),alice.session().secret(),"fixture");
        var options=passkeys.loginOptions(alice.browser(),alice.session().secret(),"fixture",false);
        var switched=passkeys.login(alice.browser(),alice.session().secret(),"fixture",assertion(options,bobKey,bob.owner(),false));
        assertEquals(401,assertThrows(ApiFailure.class,()->accounts.complete(code,alice.owner(),"Recovered fixture password",alice.owner(),true,null,signIns.issuer(delayed))).status);
        assertTrue(accounts.<Boolean>tx(c->currentPassword(c,alice.owner(),PASSWORD)));
        accounts.tx(c->{assertFalse(new PasskeyCredentials(c,RP).owned(alice.owner()).isEmpty());try(var statement=query(c,"SELECT consumed FROM tk_auth_challenge WHERE digest=?",hash(code));var rows=statement.executeQuery()) {rows.next();assertFalse(rows.getBoolean(1));}catch(java.sql.SQLException failure) {throw new AssertionError(failure);}return null;});
        assertEquals(bob.owner(),accounts.session(switched.secret()).owner());
        var fresh=signIns.begin(BrowserSignIns.Method.RECOVERY,alice.browser(),switched.secret(),"fixture");
        var recovered=accounts.complete(code,alice.owner(),"Recovered fixture password",alice.owner(),true,null,signIns.issuer(fresh));
        assertBrowserSession(alice.browser(),recovered,1);assertEquals(alice.owner(),accounts.session(recovered.secret()).owner());
        assertThrows(ApiFailure.class,()->accounts.session(switched.secret()));assertEquals(bob.owner(),accounts.session(bob.session().secret()).owner());
        accounts.tx(c->{assertTrue(new PasskeyCredentials(c,RP).owned(alice.owner()).isEmpty());return null;});
    }
    @Test void boundTokenExchangeRetainsTokenRestrictionsAndOriginCutoverFencesDelayedNormalSignIns() {
        var alice=browser("pk-alice");var token=accounts.createToken(alice.principal(),"Bound Eclipse session",Set.of("read","write"),null);var bearer=accounts.managed(hash(token.path("token").asText()));
        var pending=signIns.begin(BrowserSignIns.Method.TOKEN,alice.browser(),alice.session().secret(),"fixture");
        var exchanged=accounts.exchange(bearer,signIns.issuer(pending));assertBrowserSession(alice.browser(),exchanged,1);
        assertEquals(403,assertThrows(ApiFailure.class,()->passkeys.list(accounts.session(exchanged.secret()))).status);
        var old=signIns.begin(BrowserSignIns.Method.PASSWORD,alice.browser(),exchanged.secret(),"fixture");
        var origin=new RecoveryOrigin();origin.accounts=accounts;origin.configured="https://timekeeper.example:9443";origin.epoch=2;
        var realm=new PasskeyRealm();realm.origin=origin;realm.configuredEnabled=false;realm.configuredRp="AUTO";realm.initialize();
        io.quarkus.arc.ClientProxy.unwrap(accounts).realm=realm;
        var moved=new BrowserSignIns();moved.accounts=accounts;moved.realm=realm;
        assertEquals(503,assertThrows(ApiFailure.class,()->signIns.begin(BrowserSignIns.Method.PASSWORD,alice.browser(),exchanged.secret(),"fixture")).status);
        assertEquals(401,assertThrows(ApiFailure.class,()->accounts.login(alice.owner(),PASSWORD,false,moved.issuer(old))).status);
        var replacement=moved.begin(BrowserSignIns.Method.PASSWORD,alice.browser(),exchanged.secret(),"fixture");
        var issued=accounts.login(alice.owner(),PASSWORD,false,moved.issuer(replacement));assertBrowserSession(alice.browser(),issued,2);
        assertEquals(alice.owner(),accounts.managed(bearer.digest()).owner(),"Origin moves retain Eclipse token access");
    }
}
