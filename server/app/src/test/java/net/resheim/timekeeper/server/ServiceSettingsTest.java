package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest @TestProfile(ServiceSettingsTest.Profile.class)
class ServiceSettingsTest {
    static final String PASSWORD="a sufficiently long service settings password";
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {
            String url=System.getenv("TIMEKEEPER_TEST_SETTINGS_DB_URL");
            if(url==null) return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:service_settings_tests");
            return Map.of("quarkus.datasource.jdbc.url",url,"quarkus.datasource.db-kind","postgresql",
                    "quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"),"quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
        }
    }
    @Inject AccountStore accounts;
    @Inject ServerStore store;
    @Inject ServiceSettings settings;
    @Inject RecoveryOrigin origin;
    @Inject BrowserOrigins origins;
    @Inject PasskeyRealm realm;
    @Inject RecoveryMail mail;
    @TempDir Path temporary;
    record Browser(String cookie,String csrf,String owner) { }
    private Response call(Browser browser,String method,String path,JsonNode input,String etag,boolean csrf) {
        var request=given();if(!origin.configured().equals("NONE")) request.header("Origin",origin.configured());if(browser!=null) {request.header("Cookie",browser.cookie());if(csrf) request.header("X-CSRF-Token",browser.csrf());}
        if(input!=null) request.contentType("application/json").body(input.toString());if(etag!=null) request.header("If-Match",etag);
        return request.request(method,"/api/v1"+path);
    }
    private JsonNode success(Response response,int status) {assertEquals(status,response.statusCode(),response.asString());return parse(response.asString());}
    private Browser browser(Response response) {var account=success(response,200);return new Browser(AuthenticationFilter.COOKIE+"="+response.cookie(AuthenticationFilter.COOKIE)+(response.cookie(BrowserCookies.BROWSER)==null ? "" : "; "+BrowserCookies.BROWSER+"="+response.cookie(BrowserCookies.BROWSER)),account.path("csrf").asText(),account.path("ownerId").asText());}
    private Browser setup() throws Exception {
        String secret=AccountPasswords.secret();var challenge=object().put("sha256",hash(secret)).put("purpose","SETUP").put("expiresAt",Instant.now().plusSeconds(600).toString());
        var path=temporary.resolve("setup.json");Files.writeString(path,challenge.toString());ClientProxy.unwrap(accounts).setupFile=path.toString();accounts.initialize();
        return browser(call(null,"POST","/auth/setup",object().put("code",secret).put("username","admin").put("password",PASSWORD).put("displayName","Administrator").put("email","admin@example.org"),null,false));
    }
    @BeforeEach void clear() {
        ClientProxy.unwrap(store).stopMaintenance();ClientProxy.unwrap(accounts).setupFile="NONE";
        accounts.tx(c->{
            for(String table:List.of("tk_passkey_ceremony","tk_auth_session","tk_passkey_credential","tk_passkey_user","tk_recovery_link","tk_recovery_address","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner","tk_auth_throttle","tk_service_settings")) execute(c,"DELETE FROM "+table);
            execute(c,"UPDATE tk_auth_control SET bootstrap_completed=FALSE");execute(c,"UPDATE tk_recovery_control SET origin=NULL,epoch=0 WHERE id=1");return null;
        });settings.initialize();realm.initialize();
    }
    @AfterEach void reset() {ClientProxy.unwrap(accounts).setupFile="NONE";}
    private Response current(Browser browser) {return call(browser,"GET","/admin/settings",null,null,false);}
    private ObjectNode input(Response response) {
        var value=((ObjectNode)parse(response.asString()).get("saved")).deepCopy();value.remove("originEpoch");
        return value.put("currentPassword",PASSWORD).put("smtpPasswordAction","PRESERVE");
    }
    private Response save(Browser browser,ObjectNode input,Response previous) {return call(browser,"PUT","/admin/settings",input,previous.header("ETag"),true);}
    @Test void savesAreVersionedRedactedPendingAndPreserveReplaceOrClearOnlyTheWriteOnlySecret() throws Exception {
        var admin=setup();var previous=current(admin);var first=success(previous,200);assertTrue(first.path("canEdit").asBoolean());assertFalse(first.path("restartRequired").asBoolean());
        String secret="A private SMTP fixture password";var body=input(previous).put("smtpPort",2525).put("smtpPasswordAction","REPLACE").put("smtpPassword",secret);
        var saved=save(admin,body,previous);var result=success(saved,200);assertTrue(result.path("restartRequired").asBoolean());assertEquals(2525,result.path("saved").path("smtpPort").asInt());assertEquals(587,result.path("effective").path("smtpPort").asInt());
        assertTrue(result.path("smtpPasswordConfigured").asBoolean());assertFalse(result.path("effectiveSmtpPasswordConfigured").asBoolean());assertFalse(saved.asString().contains(secret));assertEquals("no-store",saved.header("Cache-Control"));
        assertEquals(412,save(admin,body,previous).statusCode());assertEquals(result,success(current(admin),200));
        var preserve=input(saved).put("smtpFrom","Timekeeper <timekeeper@example.org>");var preserved=save(admin,preserve,saved);assertEquals(200,preserved.statusCode());assertTrue(parse(preserved.asString()).path("smtpPasswordConfigured").asBoolean());
        assertEquals(secret,accounts.tx(c->{try(var q=query(c,"SELECT saved_smtp_password FROM tk_service_settings WHERE id=1");var rows=q.executeQuery()) {assertTrue(rows.next());return rows.getString(1);}catch(java.sql.SQLException e) {throw new AssertionError(e);}}));
        var cleared=save(admin,input(preserved).put("smtpPasswordAction","CLEAR"),preserved);assertFalse(success(cleared,200).path("smtpPasswordConfigured").asBoolean());
        assertEquals(403,call(admin,"PUT","/admin/settings",input(cleared),cleared.header("ETag"),false).statusCode());
        assertEquals(200,current(admin).statusCode(),"Saving must not activate or revoke the current session");
    }
    @Test void onlyAnActualFreshAdministratorAccountSessionCanSaveAndUncertainRetriesStayConditional() throws Exception {
        var admin=setup();var previous=current(admin);var body=input(previous).put("smtpPort",2525);
        var tokenInput=object().put("name","Admin token");tokenInput.putArray("permissions").add("read").add("write");tokenInput.putNull("expiresAt");
        var token=success(call(admin,"POST","/auth/tokens",tokenInput,null,true),201);
        var exchange=given().header("Authorization","Bearer "+token.path("token").asText()).post("/api/v1/auth/token-login");var derived=browser(exchange);
        assertFalse(success(current(derived),200).path("canEdit").asBoolean());assertEquals(403,save(derived,body,previous).statusCode());
        success(call(admin,"POST","/admin/users",object().put("username","ordinary").put("password",PASSWORD).put("displayName","Ordinary").put("email","ordinary@example.org").put("administrator",false),null,true),201);
        var ordinary=browser(call(null,"POST","/auth/login",object().put("username","ordinary").put("password",PASSWORD).put("remember",false),null,false));
        assertEquals(403,save(ordinary,body,previous).statusCode());
        assertEquals(400,save(admin,body.deepCopy().put("ownerId",ordinary.owner()),previous).statusCode());
        assertEquals(422,save(admin,body.deepCopy().put("currentPassword","wrong password"),previous).statusCode());assertEquals(200,current(admin).statusCode());
        assertEquals(428,call(admin,"PUT","/admin/settings",body,null,true).statusCode());
        var saved=save(admin,body,previous);assertEquals(200,saved.statusCode());assertEquals(412,save(admin,body,previous).statusCode());
        assertEquals(success(saved,200),success(current(admin),200));
    }
    @ParameterizedTest @ValueSource(strings={"http","path","rp","mail","host","port","sender"})
    void invalidConfigurationNeverChangesSavedOrEffectiveValues(String invalid) throws Exception {
        var admin=setup();var previous=current(admin);var body=input(previous);
        switch(invalid) {
            case "http" -> body.put("publicOrigin","http://public.example.org");
            case "path" -> body.put("publicOrigin","https://public.example.org/web/");
            case "rp" -> body.put("publicOrigin","https://public.example.org").put("passkeyRpId","foreign.example.org");
            case "mail" -> body.put("emailRecoveryEnabled",true);
            case "host" -> body.put("smtpHost","https://mail.example.org");
            case "port" -> body.put("smtpPort",65536);
            case "sender" -> body.put("smtpFrom","Display <sender@example.org");
            default -> throw new AssertionError(invalid);
        }
        assertTrue(Set.of(400,422).contains(save(admin,body,previous).statusCode()));assertEquals(parse(previous.asString()),success(current(admin),200));
    }
    @Test void restartActivatesSavedPolicyFencesStaleReplicasAndPreservesAccountsAndWork() throws Exception {
        var admin=setup();var client=success(call(admin,"POST","/clients",object().put("name","Keep private work").put("color","#176653"),null,true),201);
        var stale=new ServiceSettings();stale.accounts=accounts;stale.origin=origin;stale.realm=realm;stale.browserOrigins=origins;stale.mail=mail;stale.initialize();assertTrue(stale.available());
        var previous=current(admin);var saved=save(admin,input(previous).put("smtpPort",2525),previous);assertEquals(200,saved.statusCode());assertTrue(stale.available());
        settings.initialize();realm.initialize();assertFalse(stale.available());assertTrue(settings.available());
        assertEquals(401,call(admin,"GET","/auth/session",null,null,false).statusCode());
        var renewed=browser(call(null,"POST","/auth/login",object().put("username","admin").put("password",PASSWORD).put("remember",false),null,false));
        assertEquals(admin.owner(),renewed.owner());assertEquals(client,success(call(renewed,"GET","/clients/"+client.path("id").asText(),null,null,false),200));
        var effective=success(current(renewed),200);assertFalse(effective.path("restartRequired").asBoolean());assertEquals(2525,effective.path("effective").path("smtpPort").asInt());assertEquals(1,effective.path("originEpoch").asLong());
        settings.initialize();realm.initialize();assertEquals(effective,success(current(renewed),200),"Normal startup must preserve central settings instead of restoring the deployment defaults");
        assertEquals(200,given().get("/q/health/ready").statusCode());
    }
    @Test void proofFailuresAreCommittedToTheBoundedRateLimit() throws Exception {
        var admin=setup();var previous=current(admin);var body=input(previous).put("smtpPort",2525).put("currentPassword","wrong");
        for(int i=0;i<5;i++) assertEquals(422,save(admin,body,previous).statusCode());assertEquals(429,save(admin,body,previous).statusCode());assertEquals(parse(previous.asString()),success(current(admin),200));
    }
    @Test void explicitOperatorRecoveryRequiresANewerEpochAndNormalRestartNeverRestoresOldDefaults() throws Exception {
        var admin=setup();var previous=current(admin);success(save(admin,input(previous).put("smtpPort",2525),previous),200);
        settings.initialize();realm.initialize();var recovering=new ServiceSettings();recovering.accounts=accounts;recovering.origin=origin;recovering.realm=realm;recovering.browserOrigins=origins;recovering.mail=mail;recovering.recover=true;
        assertThrows(IllegalStateException.class,recovering::initialize);assertTrue(settings.available());
        String old=System.getProperty("timekeeper.auth.origin-epoch");
        try {
            System.setProperty("timekeeper.auth.origin-epoch","2");recovering.initialize();realm.initialize();
            assertFalse(settings.available());assertTrue(recovering.available());settings.initialize();realm.initialize();
            var renewed=browser(call(null,"POST","/auth/login",object().put("username","admin").put("password",PASSWORD).put("remember",false),null,false));
            var effective=success(current(renewed),200);assertEquals(2,effective.path("originEpoch").asLong());assertEquals(587,effective.path("effective").path("smtpPort").asInt());assertFalse(effective.path("restartRequired").asBoolean());
            assertEquals(admin.owner(),renewed.owner());
        } finally {if(old==null) System.clearProperty("timekeeper.auth.origin-epoch");else System.setProperty("timekeeper.auth.origin-epoch",old);}
    }

    private Browser login() {
        Browser anonymous=null;
        if(!origin.configured().equals("NONE")) {
            var status=call(null,"GET","/auth/status",null,null,false);success(status,200);
            anonymous=new Browser(BrowserCookies.BROWSER+"="+status.cookie(BrowserCookies.BROWSER),null,null);
        }
        return browser(call(anonymous,"POST","/auth/login",object().put("username","admin").put("password",PASSWORD).put("remember",false),null,false));
    }
    @Test void hostValidationFollowsOnlyTheActivatedPublicHostnameAndNeverTrustsForwardedHosts() throws Exception {
        var admin=setup();
        for(String path:List.of("/web/","/admin/","/q/health/ready","/api/v1/auth/status")) {
            assertEquals(400,given().header("Host","foreign.example.invalid").header("X-Forwarded-Host","localhost").get(path).statusCode());
            assertEquals(400,given().header("Host","localhost.foreign.example.invalid").get(path).statusCode());
        }
        var previous=current(admin);success(save(admin,input(previous).put("publicOrigin","https://old.example.invalid"),previous),200);
        assertEquals(400,given().header("Host","old.example.invalid").get("/web/").statusCode());settings.initialize();realm.initialize();
        assertEquals(200,given().header("Host","old.example.invalid").get("/web/").statusCode());admin=login();previous=current(admin);
        success(save(admin,input(previous).put("publicOrigin","https://new.example.invalid:8443"),previous),200);
        assertEquals(200,given().header("Host","old.example.invalid").get("/admin/").statusCode());assertEquals(400,given().header("Host","new.example.invalid:8443").get("/admin/").statusCode());
        settings.initialize();realm.initialize();assertEquals(400,given().header("Host","old.example.invalid").get("/web/").statusCode());
        assertEquals(200,given().header("Host","new.example.invalid:8443").get("/web/").statusCode());assertEquals(200,given().header("Host","localhost").get("/q/health/ready").statusCode());
        admin=login();assertTrue(success(current(admin),200).path("passkeysEnabled").asBoolean());
    }

}
