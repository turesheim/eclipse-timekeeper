package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
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
    @Inject TaskProviderConfiguration taskProviders;
    @Inject SharedConnectConfiguration sharedConnect;
    @Inject ContractInputs inputs;
    @Inject RecoveryOrigin origin;
    @Inject BrowserOrigins origins;
    @Inject PasskeyRealm realm;
    @Inject RecoveryMail mail;
    @Inject VaultLimits limits;
    @io.quarkus.test.common.http.TestHTTPResource URI baseUri;
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
    private Response noteLimits(Browser browser){return call(browser,"GET","/admin/notes/limits",null,null,false);}
    @Test void internetAccountSetupIsAdministratorOnlyRedactedAndUncached()throws Exception{
        var admin=setup();assertEquals(401,call(null,"GET","/admin/internet-accounts",null,null,false).statusCode());
        var response=call(admin,"GET","/admin/internet-accounts",null,null,false);var data=success(response,200);
        inputs.validate("AdministrationInternetAccounts",data);assertEquals("no-store",response.header("Cache-Control"));assertFalse(data.has("clientSecret"));
        assertEquals("PUBLIC_ORIGIN_REQUIRED",data.path("status").asText());
        assertEquals(422,call(admin,"GET","/admin/internet-accounts?check=invalid",null,null,false).statusCode());
        success(call(admin,"POST","/admin/users",object().put("username","ordinary").put("password",PASSWORD).put("displayName","Ordinary").put("email","ordinary@example.org").put("administrator",false),null,true),201);
        var ordinary=browser(call(null,"POST","/auth/login",object().put("username","ordinary").put("password",PASSWORD).put("remember",false),null,false));
        assertEquals(403,call(ordinary,"GET","/admin/internet-accounts?check=true",null,null,false).statusCode());
    }
    private Response noteLimits(Browser browser,Response previous,ObjectNode values){var body=object().put("currentPassword",PASSWORD);body.set("limits",values);return call(browser,"PUT","/admin/notes/limits",body,previous.header("ETag"),true);}
    @Test void legacyGoogleSettingsDoNotGrowNewFieldsUntilMicrosoftIsChanged()throws Exception{
        var admin=setup();accounts.tx(c->{
            try(var statement=query(c,"SELECT saved,effective FROM tk_service_settings WHERE id=1");var rows=statement.executeQuery()){
                assertTrue(rows.next());var saved=(ObjectNode)parse(rows.getString(1));var effective=(ObjectNode)parse(rows.getString(2));
                ((ObjectNode)saved.get(ServiceSettings.INTERNET)).remove(List.of("microsoftClientId","microsoftClientSecret"));((ObjectNode)effective.get(ServiceSettings.INTERNET)).remove(List.of("microsoftClientId","microsoftClientSecret"));
                execute(c,"UPDATE tk_service_settings SET saved=?,effective=? WHERE id=1",saved.toString(),effective.toString());
            }catch(java.sql.SQLException failure){throw new AssertionError(failure);}return null;
        });
        String path="/admin/internet-accounts";var before=call(admin,"GET",path,null,null,false);
        var input=object().put("mode","DIRECT").putNull("serviceUrl").put("googleClientId","updated-google").put("googleSecretAction","PRESERVE").put("microsoftClientId","").put("microsoftSecretAction","PRESERVE").put("currentPassword",PASSWORD);
        success(call(admin,"PUT",path,input,before.header("ETag"),true),200);
        accounts.tx(c->{try(var statement=query(c,"SELECT saved FROM tk_service_settings WHERE id=1");var rows=statement.executeQuery()){assertTrue(rows.next());var stored=parse(rows.getString(1)).path(ServiceSettings.INTERNET);assertEquals(4,stored.size());assertEquals("updated-google",stored.path("googleClientId").asText());}catch(java.sql.SQLException failure){throw new AssertionError(failure);}return null;});
    }
    @Test void microsoftRegistrationIsWriteOnlyAndOlderGoogleCommandsPreserveIt()throws Exception{
        var admin=setup();String path="/admin/internet-accounts",secret="private-microsoft-application-secret";
        var before=call(admin,"GET",path,null,null,false);
        var input=object().put("mode","DIRECT").putNull("serviceUrl").put("googleClientId","").put("googleSecretAction","PRESERVE").put("microsoftClientId","microsoft-app").put("microsoftSecretAction","REPLACE").put("microsoftClientSecret",secret).put("currentPassword",PASSWORD);
        var written=call(admin,"PUT",path,input,before.header("ETag"),true);var data=success(written,200);inputs.validate("AdministrationInternetAccounts",data);
        assertTrue(data.path("saved").path("microsoftClientSecretConfigured").asBoolean());assertTrue(data.path("effective").path("microsoftClientSecretConfigured").asBoolean());assertFalse(written.asString().contains(secret));
        assertEquals("microsoft-app",taskProviders.current().microsoftId());assertEquals(secret,taskProviders.current().microsoftSecret());
        var legacy=input.deepCopy();legacy.remove(List.of("microsoftClientId","microsoftSecretAction","microsoftClientSecret"));var current=call(admin,"GET",path,null,null,false);
        var preserved=call(admin,"PUT",path,legacy,current.header("ETag"),true);assertTrue(success(preserved,200).path("saved").path("microsoftClientSecretConfigured").asBoolean());
        var clear=input.deepCopy().put("microsoftSecretAction","CLEAR");clear.remove("microsoftClientSecret");var cleared=call(admin,"PUT",path,clear,preserved.header("ETag"),true);assertFalse(success(cleared,200).path("saved").path("microsoftClientSecretConfigured").asBoolean());
        assertTrue(taskProviders.current().microsoftSecret().isEmpty());assertFalse(current(admin).asString().contains(secret));
    }
    @Test void googleCredentialsAreWriteOnlyVersionedAndApplyWithoutRestart()throws Exception{
        var admin=setup();String path="/admin/internet-accounts",secret="private-google-application-secret";
        var initial=call(admin,"GET",path,null,null,false);success(initial,200);
        var input=object().put("mode","DIRECT").putNull("serviceUrl").put("googleClientId","installation-google-id").put("googleSecretAction","REPLACE").put("googleClientSecret",secret).put("currentPassword",PASSWORD);
        assertEquals(403,call(admin,"PUT",path,input,initial.header("ETag"),false).statusCode());
        assertEquals(428,call(admin,"PUT",path,input,null,true).statusCode());
        assertEquals(422,call(admin,"PUT",path,input.deepCopy().put("currentPassword","wrong-password"),initial.header("ETag"),true).statusCode());
        var written=call(admin,"PUT",path,input,initial.header("ETag"),true);var data=success(written,200);inputs.validate("AdministrationInternetAccounts",data);
        assertEquals("no-store",written.header("Cache-Control"));assertTrue(data.path("saved").path("googleClientSecretConfigured").asBoolean());assertTrue(data.path("effective").path("googleClientSecretConfigured").asBoolean());assertFalse(data.path("restartRequired").asBoolean());
        assertFalse(written.asString().contains(secret));assertFalse(current(admin).asString().contains("installation-google-id"));assertFalse(current(admin).asString().contains(secret));
        var preserve=input.deepCopy();preserve.remove("googleClientSecret");preserve.put("googleSecretAction","PRESERVE").put("googleClientId","updated-google-id");
        assertEquals(412,call(admin,"PUT",path,preserve,initial.header("ETag"),true).statusCode());
        var kept=call(admin,"PUT",path,preserve,written.header("ETag"),true);assertTrue(success(kept,200).path("saved").path("googleClientSecretConfigured").asBoolean());
        assertFalse(sharedConnect.enabled());assertEquals("updated-google-id",taskProviders.current().googleId());assertEquals(secret,taskProviders.current().googleSecret());
        var active=call(admin,"GET",path,null,null,false);assertFalse(success(active,200).path("restartRequired").asBoolean());
        var clear=preserve.deepCopy().put("googleSecretAction","CLEAR");var cleared=call(admin,"PUT",path,clear,active.header("ETag"),true);var empty=success(cleared,200);assertFalse(empty.path("saved").path("googleClientSecretConfigured").asBoolean());assertFalse(empty.path("effective").path("googleClientSecretConfigured").asBoolean());
        assertTrue(taskProviders.current().googleSecret().isEmpty());assertFalse(call(admin,"GET",path,null,null,false).asString().contains(secret));
    }
    @Test void oauthChangesReloadOnReplicasWithoutActivatingPendingSmtpOrRetiringSessions()throws Exception{
        var admin=setup();
        var replicaProviders=new TaskProviderConfiguration();replicaProviders.origins=origins;
        var replica=new ServiceSettings();replica.accounts=accounts;replica.origin=origin;replica.realm=realm;replica.browserOrigins=origins;replica.mail=mail;replica.taskProviders=replicaProviders;replica.initialize();
        var previous=current(admin);success(save(admin,input(previous).put("smtpPort",2525),previous),200);
        var initial=call(admin,"GET","/admin/internet-accounts",null,null,false);
        var input=object().put("mode","DIRECT").putNull("serviceUrl").put("googleClientId","live-google").put("googleSecretAction","REPLACE").put("googleClientSecret","live-secret").put("currentPassword",PASSWORD);
        var written=success(call(admin,"PUT","/admin/internet-accounts",input,initial.header("ETag"),true),200);
        assertTrue(written.path("restartRequired").asBoolean());assertTrue(replica.available());
        assertEquals("live-google",replicaProviders.current().googleId());assertEquals("live-secret",replicaProviders.current().googleSecret());
        var general=success(current(admin),200);assertEquals(2525,general.path("saved").path("smtpPort").asInt());assertNotEquals(2525,general.path("effective").path("smtpPort").asInt());
        assertEquals(200,call(admin,"GET","/auth/session",null,null,false).statusCode());
        settings.initialize();realm.initialize();assertFalse(replica.available());assertTrue(settings.available());
    }
    @Test void notesQuotasAreLiveSharedDurableAndIndependentOfPendingAuthenticationSettings() throws Exception {
        var admin=setup();var defaults=noteLimits(admin);var initial=success(defaults,200);
        assertEquals(160_000_000,initial.path("limits").path("fileBytes").asInt());assertEquals(10_000_000,initial.path("limits").path("markdownBytes").asInt());assertEquals(5_000_000_000L,initial.path("limits").path("totalBytes").asLong());assertEquals(100_000,initial.path("limits").path("entries").asInt());
        var pending=save(admin,input(current(admin)).put("smtpPort",2525),current(admin));var before=success(pending,200);assertTrue(before.path("restartRequired").asBoolean());
        var values=object().put("fileBytes",1_000_000).put("markdownBytes",100_000).put("totalBytes",2_000_000L).put("entries",100);
        var saved=noteLimits(admin,defaults,values);assertEquals(values.toString(),success(saved,200).path("limits").toString());assertEquals("no-store",saved.header("Cache-Control"));
        assertEquals(before,success(current(admin),200));assertTrue(settings.available());assertEquals(200,call(admin,"GET","/auth/session",null,null,false).statusCode());
        var replica=new VaultLimits();replica.accounts=accounts;replica.fileBytes=160_000_000;replica.markdownBytes=10_000_000;replica.totalBytes=5_000_000_000L;replica.entries=100_000;
        assertEquals(2_000_000,replica.totalBytes());assertEquals(1_000_000,limits.fileBytes());assertEquals(values,limits.current().json());
        assertEquals(412,noteLimits(admin,defaults,values).statusCode());
        var noOp=noteLimits(admin,saved,values);assertEquals(saved.header("ETag"),noOp.header("ETag"));
        settings.initialize();realm.initialize();var renewed=login();assertEquals(values.toString(),success(noteLimits(renewed),200).path("limits").toString());assertEquals(2525,success(current(renewed),200).path("effective").path("smtpPort").asInt());
    }
    @Test void notesQuotaSavesRequireFreshAdministratorAccountAuthorityCsrfAndValidBounds() throws Exception {
        var admin=setup();var previous=noteLimits(admin);var values=(ObjectNode)success(previous,200).path("limits");
        var body=object().put("currentPassword",PASSWORD);body.set("limits",values);
        assertEquals(403,call(admin,"PUT","/admin/notes/limits",body,previous.header("ETag"),false).statusCode());
        assertEquals(428,call(admin,"PUT","/admin/notes/limits",body,null,true).statusCode());
        assertEquals(422,call(admin,"PUT","/admin/notes/limits",body.deepCopy().put("currentPassword","wrong"),previous.header("ETag"),true).statusCode());
        for(var invalid:List.of(values.deepCopy().put("entries",3),values.deepCopy().put("entries",1.5),values.deepCopy().put("fileBytes",0),values.deepCopy().put("markdownBytes",200_000_000),values.deepCopy().put("totalBytes",100))){
            assertTrue(Set.of(400,422).contains(noteLimits(admin,previous,invalid).statusCode()));
        }
        var tooLarge=values.deepCopy().put("fileBytes",500_000_000);var rejected=noteLimits(admin,previous,tooLarge);assertEquals(422,rejected.statusCode());assertEquals("VAULT_HTTP_LIMIT",parse(rejected.asString()).path("code").asText());
        var tokenInput=object().put("name","Quota reader");tokenInput.putArray("permissions").add("read").add("write");tokenInput.putNull("expiresAt");var token=success(call(admin,"POST","/auth/tokens",tokenInput,null,true),201);
        var derived=browser(given().header("Authorization","Bearer "+token.path("token").asText()).post("/api/v1/auth/token-login"));
        assertFalse(success(noteLimits(derived),200).path("canEdit").asBoolean());assertEquals(403,noteLimits(derived,previous,values).statusCode());
        assertEquals(initialQuota(previous),initialQuota(noteLimits(admin)));
    }
    private JsonNode initialQuota(Response response){return parse(response.asString());}
    @Test void largerDefaultFilesPassTheHttpBoundaryAndStorageIsReadableWithoutWritePermission() throws Exception {
        var admin=setup();
        // Keep a real upload above the former HTTP/JSON ceiling, but stream the
        // test client's bytes rather than retaining multiple attachment copies.
        var payload=temporary.resolve("large.bin");
        try(var output=Files.newOutputStream(payload)){var block=new byte[8192];for(int i=0;i<21*128;i++)output.write(block);}
        try(var client=HttpClient.newHttpClient()){
            var upload=client.send(HttpRequest.newBuilder(baseUri.resolve("/api/v1/notes/files?path=large.bin"))
                    .header("Cookie",admin.cookie()).header("X-CSRF-Token",admin.csrf()).header("If-None-Match","*").header("Content-Type","application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofFile(payload)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(201,upload.statusCode(),upload.body());
            var downloaded=temporary.resolve("download.bin");var response=client.send(HttpRequest.newBuilder(baseUri.resolve("/api/v1/notes/entries/"+parse(upload.body()).path("id").asText()+"/content"))
                    .header("Cookie",admin.cookie()).GET().build(),HttpResponse.BodyHandlers.ofFile(downloaded));
            assertEquals(200,response.statusCode());assertEquals(-1,Files.mismatch(payload,downloaded));
        }
        var tokenInput=object().put("name","Storage reader");tokenInput.putArray("permissions").add("read");tokenInput.putNull("expiresAt");var token=success(call(admin,"POST","/auth/tokens",tokenInput,null,true),201).path("token").asText();
        var reply=given().header("Authorization","Bearer "+token).get("/api/v1/notes/storage");var usage=success(reply,200);assertEquals(21*1024*1024,usage.path("usage").path("bytes").asLong());assertTrue(reply.header("Cache-Control").contains("no-store"));
        assertEquals(401,given().get("/api/v1/notes/storage").statusCode());
        success(call(admin,"POST","/admin/users",object().put("username","storage-peer").put("password",PASSWORD).put("displayName","Peer").put("email","storage-peer@example.org").put("administrator",false),null,true),201);
        var peer=browser(call(null,"POST","/auth/login",object().put("username","storage-peer").put("password",PASSWORD).put("remember",false),null,false));
        assertEquals(0,success(call(peer,"GET","/notes/storage",null,null,false),200).path("usage").path("bytes").asLong());
        assertEquals(403,noteLimits(peer).statusCode());assertEquals(403,noteLimits(peer,noteLimits(admin),(ObjectNode)parse(noteLimits(admin).asString()).path("limits")).statusCode());
    }
    @Test void raisingNotesQuotasImmediatelyUnblocksBothStorageAndEntryGrowth() throws Exception {
        var admin=setup();var tiny=object().put("fileBytes",128).put("markdownBytes",64).put("totalBytes",128).put("entries",4);
        var current=noteLimits(admin,noteLimits(admin),tiny);success(current,200);success(call(admin,"GET","/notes",null,null,false),200);
        var upload=given().header("Cookie",admin.cookie()).header("X-CSRF-Token",admin.csrf()).header("If-None-Match","*").contentType("application/octet-stream").body(new byte[64]).put("/api/v1/notes/files?path=a.bin");assertEquals(201,upload.statusCode());
        assertEquals(507,given().header("Cookie",admin.cookie()).header("X-CSRF-Token",admin.csrf()).header("If-None-Match","*").contentType("application/octet-stream").body(new byte[64]).put("/api/v1/notes/files?path=b.bin").statusCode());
        current=noteLimits(admin,current,tiny.deepCopy().put("entries",5));success(current,200);
        assertEquals(201,given().header("Cookie",admin.cookie()).header("X-CSRF-Token",admin.csrf()).header("If-None-Match","*").contentType("application/octet-stream").body(new byte[64]).put("/api/v1/notes/files?path=b.bin").statusCode());
        String path="/api/v1/notes/entries/"+parse(upload.asString()).path("id").asText()+"/content";
        String contentTag=given().header("Cookie",admin.cookie()).get(path).header("ETag");
        assertEquals(507,given().header("Cookie",admin.cookie()).header("X-CSRF-Token",admin.csrf()).header("If-Match",contentTag).contentType("application/octet-stream").body(new byte[65]).put(path).statusCode());
        success(noteLimits(admin,current,tiny.deepCopy().put("entries",5).put("totalBytes",256)),200);
        assertEquals(200,given().header("Cookie",admin.cookie()).header("X-CSRF-Token",admin.csrf()).header("If-Match",contentTag).contentType("application/octet-stream").body(new byte[65]).put(path).statusCode());
        var storage=success(call(admin,"GET","/notes/storage",null,null,false),200);assertEquals(129,storage.path("usage").path("bytes").asLong());assertEquals(5,storage.path("usage").path("entries").asInt());
    }
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
        var stale=new ServiceSettings();stale.accounts=accounts;stale.origin=origin;stale.realm=realm;stale.browserOrigins=origins;stale.mail=mail;stale.taskProviders=taskProviders;stale.initialize();assertTrue(stale.available());
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
        settings.initialize();realm.initialize();var recovering=new ServiceSettings();recovering.accounts=accounts;recovering.origin=origin;recovering.realm=realm;recovering.browserOrigins=origins;recovering.mail=mail;recovering.taskProviders=taskProviders;recovering.recover=true;
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
