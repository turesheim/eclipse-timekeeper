package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP coverage for configured HTTPS browser authority, independent of WebAuthn crypto. */
@QuarkusTest @TestProfile(BrowserSessionHttpTest.Profile.class)
class BrowserSessionHttpTest {
    private static final String ORIGIN="https://timekeeper.example:8443",PASSWORD="an actual sufficiently long HTTP password";
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:browser_session_http","timekeeper.auth.public-origin",ORIGIN);}
    }
    @Inject AccountStore accounts;
    @Inject ServerStore store;
    @Inject RecoveryOrigin authority;
    @Inject BrowserOrigins origins;
    @Inject PasskeyRealm realm;
    @Inject BrowserSignIns signIns;
    @TempDir Path temporary;
    record Browser(String binding,String sid,String csrf,String owner) {
        String cookie() {return AuthenticationFilter.COOKIE+"="+sid+"; "+BrowserCookies.BROWSER+"="+binding;}
        @Override public String toString() {return "Fixture browser";}
    }
    @BeforeEach void reset() {
        ClientProxy.unwrap(store).stopMaintenance();ClientProxy.unwrap(authority).configured=ORIGIN;ClientProxy.unwrap(authority).epoch=1;ClientProxy.unwrap(origins).publicOrigin=ORIGIN;
        accounts.tx(c->{lock(c);
            for(String table:List.of("tk_auth_session","tk_passkey_ceremony","tk_passkey_credential","tk_passkey_user","tk_recovery_link","tk_recovery_address","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner","tk_auth_throttle")) execute(c,"DELETE FROM "+table);
            execute(c,"UPDATE tk_auth_control SET bootstrap_completed=FALSE");execute(c,"UPDATE tk_recovery_control SET origin=NULL,epoch=0");return null;
        });realm.initialize();
    }
    @AfterEach void restore() {ClientProxy.unwrap(accounts).setupFile="NONE";ClientProxy.unwrap(authority).configured=ORIGIN;ClientProxy.unwrap(authority).epoch=1;ClientProxy.unwrap(origins).publicOrigin=ORIGIN;}
    private String binding(String origin) {
        var status=given().header("Referer",origin+"/web/").get("/api/v1/auth/status").then().statusCode(200).extract().response();
        String binding=status.cookie(BrowserCookies.BROWSER);assertNotNull(binding);return binding;
    }
    private Browser browser(Response response,String binding) {
        assertEquals(200,response.statusCode(),response.asString());assertEquals(binding,response.cookie(BrowserCookies.BROWSER));
        assertTrue(response.getHeaders().getValues("Set-Cookie").stream().anyMatch(v->v.startsWith(BrowserCookies.BROWSER+"=") && v.contains("HttpOnly; Secure; SameSite=Strict; Max-Age=2592000")));
        return new Browser(binding,response.cookie(AuthenticationFilter.COOKIE),response.jsonPath().getString("csrf"),response.jsonPath().getString("ownerId"));
    }
    private Browser setup() throws Exception {
        String code=AccountPasswords.secret();var challenge=object().put("sha256",hash(code)).put("purpose","SETUP").put("expiresAt",Instant.now().plusSeconds(600).toString());
        var file=temporary.resolve("setup.json");Files.writeString(file,challenge.toString());ClientProxy.unwrap(accounts).setupFile=file.toString();accounts.initialize();
        var body=Map.of("code",code,"username","browser-admin","password",PASSWORD,"displayName","Browser admin","email","browser@example.org");
        given().header("Origin",ORIGIN).contentType("application/json").body(body).post("/api/v1/auth/setup").then().statusCode(403);
        assertTrue(accounts.status().path("setupRequired").asBoolean());String binding=binding(ORIGIN);
        return browser(given().header("Cookie",BrowserCookies.BROWSER+"="+binding).header("Origin",ORIGIN).contentType("application/json").body(body).post("/api/v1/auth/setup"),binding);
    }
    private Response login(String cookie,String origin,boolean remember) {
        return given().header("Cookie",cookie).header("Origin",origin).contentType("application/json").body(Map.of("username","browser-admin","password",PASSWORD,"remember",remember)).post("/api/v1/auth/login");
    }
    private String seen(Browser browser) {
        return accounts.tx(c->{try(var s=query(c,"SELECT last_seen_at FROM tk_auth_session WHERE digest=?",hash(browser.sid()));var rows=s.executeQuery()) {assertTrue(rows.next());return rows.getString(1);}catch(java.sql.SQLException failure) {throw new AssertionError(failure);}});
    }
    @Test void sameHostOtherPortsAndMissingOrForeignBindingsCannotReadOrRefreshTheSession() throws Exception {
        assertNull(given().get("/api/v1/auth/status").then().statusCode(200).extract().header("Set-Cookie"));
        given().header("Origin","https://other.example").get("/api/v1/auth/status").then().statusCode(403);
        var browser=setup();String timestamp=Instant.now().minusSeconds(60).toString();
        accounts.tx(c->{execute(c,"UPDATE tk_auth_session SET last_seen_at=? WHERE digest=?",timestamp,hash(browser.sid()));return null;});
        for(String foreign:List.of("https://timekeeper.example","https://timekeeper.example:9443","https://old.example:8443"))
            given().header("Cookie",browser.cookie()).header("Origin",foreign).get("/api/v1/me").then().statusCode(403);
        given().header("Cookie",browser.cookie()).get("/api/v1/me").then().statusCode(403);
        given().header("Cookie",AuthenticationFilter.COOKIE+"="+browser.sid()).header("Origin",ORIGIN).get("/api/v1/me").then().statusCode(401);
        given().header("Cookie",AuthenticationFilter.COOKIE+"="+browser.sid()+"; "+BrowserCookies.BROWSER+"="+AccountPasswords.secret()).header("Origin",ORIGIN).get("/api/v1/me").then().statusCode(401);
        assertEquals(timestamp,seen(browser));
        given().header("Cookie",browser.cookie()).header("Referer",ORIGIN+"/web/").get("/api/v1/me").then().statusCode(200);
        given().header("Cookie",browser.cookie()+"; "+AuthenticationFilter.COOKIE+"="+browser.sid()).header("Origin",ORIGIN).get("/api/v1/me").then().statusCode(400);
    }
    @Test void passwordFallbackRefreshesBindingAndLogoutCancelsPendingSignInWithoutRevivingIt() throws Exception {
        var first=setup();var reply=login(first.cookie(),ORIGIN,true);var current=browser(reply,first.binding());
        assertTrue(reply.getHeaders().getValues("Set-Cookie").stream().anyMatch(v->v.startsWith(AuthenticationFilter.COOKIE+"=") && v.contains("Max-Age=")));
        given().header("Cookie",first.cookie()).header("Origin",ORIGIN).get("/api/v1/me").then().statusCode(401);
        var delayed=signIns.begin(BrowserSignIns.Method.PASSWORD,current.binding(),current.sid(),"fixture");
        given().header("Cookie",current.cookie()).header("Origin",ORIGIN).post("/api/v1/auth/logout").then().statusCode(403);
        var logout=given().header("Cookie",current.cookie()).header("Origin",ORIGIN).header("X-CSRF-Token",current.csrf()).post("/api/v1/auth/logout").then().statusCode(204).extract().response();
        assertEquals(2,logout.getHeaders().getValues("Set-Cookie").stream().filter(v->v.contains("Max-Age=0")).count());
        assertEquals(401,assertThrows(ApiFailure.class,()->accounts.login("browser-admin",PASSWORD,false,signIns.issuer(delayed))).status);
        String next=binding(ORIGIN);var recovered=browser(login(AuthenticationFilter.COOKIE+"=obsolete-cookie; "+BrowserCookies.BROWSER+"="+next,ORIGIN,false),next);
        assertEquals(current.owner(),recovered.owner());
    }
    @Test void originCutoverKeepsAccountsAndTokensButFencesRetiredCookiesAndPendingPasswordRequests() throws Exception {
        var browser=setup();var principal=accounts.session(browser.sid(),browser.binding());
        var managed=accounts.createToken(principal,"Eclipse",Set.of("read"),null);var token=managed.path("token").asText();
        given().header("Authorization","Bearer "+token).get("/api/v1/me").then().statusCode(200);
        var delayed=signIns.begin(BrowserSignIns.Method.PASSWORD,browser.binding(),browser.sid(),"fixture");
        String replacement="https://timekeeper.example:9443";ClientProxy.unwrap(authority).configured=replacement;ClientProxy.unwrap(authority).epoch=2;ClientProxy.unwrap(origins).publicOrigin=replacement;realm.initialize();
        given().header("Cookie",browser.cookie()).header("Origin",ORIGIN).get("/api/v1/me").then().statusCode(403);
        given().header("Cookie",browser.cookie()).header("Origin",replacement).get("/api/v1/me").then().statusCode(401);
        assertEquals(401,assertThrows(ApiFailure.class,()->accounts.login("browser-admin",PASSWORD,false,signIns.issuer(delayed))).status);
        String binding=binding(replacement);var resumed=browser(login(BrowserCookies.BROWSER+"="+binding,replacement,false),binding);assertEquals(browser.owner(),resumed.owner());
        given().header("Authorization","Bearer "+token).get("/api/v1/me").then().statusCode(200);
        var exchange=given().header("Cookie",resumed.cookie()).header("Origin",replacement).header("Authorization","Bearer "+token).post("/api/v1/auth/token-login").then().statusCode(200).extract().response();
        var readOnly=browser(exchange,binding);assertEquals(List.of("read"),exchange.jsonPath().getList("permissions"));
        given().header("Cookie",readOnly.cookie()).header("Origin",replacement).header("X-CSRF-Token",readOnly.csrf()).contentType("application/json").body(Map.of("name","Forbidden","color","#ffffff")).post("/api/v1/clients").then().statusCode(403);
        String independentBinding=binding(replacement);var manager=browser(login(BrowserCookies.BROWSER+"="+independentBinding,replacement,false),independentBinding);
        given().header("Cookie",manager.cookie()).header("Origin",replacement).header("X-CSRF-Token",manager.csrf()).delete("/api/v1/auth/tokens/"+managed.path("id").asText()).then().statusCode(204);
        given().header("Cookie",readOnly.cookie()).header("Origin",replacement).get("/api/v1/me").then().statusCode(401);
    }
}
