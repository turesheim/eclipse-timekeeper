package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import java.nio.file.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(InternetAccountVerificationHttpTest.Profile.class)
class InternetAccountVerificationHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){var values=TaskProviderTestDatabase.settings("internet_verification");values.putAll(Map.of("timekeeper.auth.public-origin","https://localhost","timekeeper.connect.shared","false","timekeeper.tasks.google.client-id","google-app","timekeeper.tasks.google.client-secret","private-google-secret","timekeeper.tasks.microsoft.client-id","microsoft-app","timekeeper.tasks.microsoft.client-secret","private-microsoft-secret"));return values;}
        public Set<Class<?>> getEnabledAlternatives(){return Set.of(Fixture.class);}
    }
    @Alternative @ApplicationScoped
    public static class Fixture extends SharedConnectResourceHttpTest.Fixture {
        static volatile boolean refreshFailure,calendarFailure;
        static volatile Runnable onCalendar;
        @Override TaskProviderOAuth oauth(TaskCloudProvider.Provider provider){
            return new TaskProviderOAuth(provider,new TaskProviderHttp(Set.of("oauth2.googleapis.com","login.microsoftonline.com"),request->{
                var form=new HashMap<String,String>();for(String pair:bytes(request).split("&")){var parts=pair.split("=",2);form.put(URLDecoder.decode(parts[0],java.nio.charset.StandardCharsets.UTF_8),URLDecoder.decode(parts[1],java.nio.charset.StandardCharsets.UTF_8));}
                boolean refresh=form.get("grant_type").equals("refresh_token");String code=form.get("code");
                if(refresh&&refreshFailure||!refresh&&code.startsWith("denied-"))return reply(400,"{\"error\":\"invalid_client\",\"error_description\":\"private-provider-detail\"}");
                String key=refresh?form.get("refresh_token").substring("fixture-refresh-".length()):hash(code).substring(0,24);
                if(!refresh){STATES.putIfAbsent(key,new State());var state=STATES.get(key);state.tasks=!code.startsWith("calendar-fixture-");state.calendars=code.startsWith("calendar-fixture-")||code.startsWith("combined-fixture-");state.writable=code.startsWith("write-fixture-");}
                var state=STATES.get(key);if(refresh)state.refreshes.incrementAndGet();String scopes=ProviderScopes.requested(provider.name().toLowerCase(Locale.ROOT),state.tasks,state.calendars,state.writable);
                return reply(200,object().put("access_token","fixture-access-"+key).put("refresh_token","fixture-refresh-"+key).put("token_type","Bearer").put("expires_in",3600).put("scope",scopes).toString());
            }));
        }
        @Override CalendarCloudProvider calendars(TaskCloudProvider.Provider provider){
            return new CalendarCloudProvider(provider,new TaskProviderHttp(Set.of("www.googleapis.com","graph.microsoft.com"),request->{
                var action=onCalendar;onCalendar=null;if(action!=null)action.run();
                if(calendarFailure)return reply(403,"{\"private\":\"provider-detail\"}");
                var page=object();page.putArray(provider==TaskCloudProvider.Provider.GOOGLE?"items":"value");return reply(200,page.toString());
            }));
        }
    }
    @Inject RecoveryOrigin origin;@Inject AccountStore accounts;@Inject ServerStore store;@Inject ServiceSettings settings;@Inject PasskeyRealm realm;@Inject ContractInputs inputs;
    @TempDir Path temporary;
    static final String PASSWORD="A sufficiently long verification password";
    record Browser(String cookie,String csrf,String owner){}
    private Browser admin;
    private Response call(Browser browser,String method,String path,JsonNode body,String etag){var request=given();if(!origin.configured().equals("NONE"))request.header("Origin",origin.configured());if(browser!=null)request.header("Cookie",browser.cookie()).header("X-CSRF-Token",browser.csrf());else request.header("Cookie",BrowserCookies.BROWSER+"="+AccountPasswords.secret());if(body!=null)request.contentType("application/json").body(body.toString());if(etag!=null)request.header("If-Match",etag);return request.request(method,"/api/v1"+path);}
    private ObjectNode json(Response response,int expected){assertEquals(expected,response.statusCode(),response.asString());return (ObjectNode)parse(response.asString());}
    private Browser browser(Response response){var value=json(response,200);return new Browser(AuthenticationFilter.COOKIE+"="+response.cookie(AuthenticationFilter.COOKIE)+"; "+BrowserCookies.BROWSER+"="+response.cookie(BrowserCookies.BROWSER),value.path("csrf").asText(),value.path("ownerId").asText());}
    @BeforeEach void setup()throws Exception{
        Fixture.refreshFailure=false;Fixture.calendarFailure=false;Fixture.onCalendar=null;Fixture.failRevocation=false;
        ClientProxy.unwrap(store).stopMaintenance();ClientProxy.unwrap(accounts).setupFile="NONE";
        accounts.tx(c->{for(String table:List.of("tk_passkey_ceremony","tk_auth_session","tk_passkey_credential","tk_passkey_user","tk_recovery_link","tk_recovery_address","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner","tk_auth_throttle","tk_service_settings"))execute(c,"DELETE FROM "+table);execute(c,"UPDATE tk_auth_control SET bootstrap_completed=FALSE");execute(c,"UPDATE tk_recovery_control SET origin=NULL,epoch=0 WHERE id=1");return null;});settings.initialize();realm.initialize();
        String secret=AccountPasswords.secret();var path=temporary.resolve("setup.json");Files.writeString(path,object().put("sha256",hash(secret)).put("purpose","SETUP").put("expiresAt",Instant.now().plusSeconds(600).toString()).toString());ClientProxy.unwrap(accounts).setupFile=path.toString();accounts.initialize();
        admin=browser(call(null,"POST","/auth/setup",object().put("code",secret).put("username","admin").put("password",PASSWORD).put("displayName","Administrator").put("email","admin@example.org"),null));
    }
    @AfterEach void reset(){Fixture.onCalendar=null;ClientProxy.unwrap(accounts).setupFile="NONE";}
    private ObjectNode begin(Browser browser,String provider,boolean tasks,boolean calendars,boolean writable){var input=object().put("verification",true).put("direction",writable?"TWO_WAY":"IMPORT");input.set("services",object().put("tasks",tasks).put("calendars",calendars));return json(call(browser,"POST","/task-providers/"+provider+"/authorize",input,null),200);}
    private String state(ObjectNode authorization){for(String pair:URI.create(authorization.path("authorizationUrl").asText()).getRawQuery().split("&")){if(pair.startsWith("state="))return URLDecoder.decode(pair.substring(6),java.nio.charset.StandardCharsets.UTF_8);}throw new AssertionError("No authorization state");}
    private ObjectNode complete(String state,String code){var value=json(call(admin,"POST","/task-providers/complete",object().put("state",state).put("code",code),null),200);inputs.validate("InternetAccountVerification",value);assertFalse(value.toString().contains("fixture-access"));assertFalse(value.toString().contains("private-provider"));return value;}
    private void noConnection(){store.transaction(admin.owner(),()->{for(String kind:List.of("TASK_AUTH","TASK_CONNECTION","TASK","CALENDAR_SUBSCRIPTION"))assertTrue(store.list(kind).isEmpty(),kind);return null;});}
    @Test void directGoogleAndMicrosoftVerifySelectedApisAndRefreshWithoutCreatingConnections(){
        for(String provider:List.of("google","microsoft")){String code="combined-fixture-"+provider;var result=complete(state(begin(admin,provider,true,true,false)),code);assertEquals("PASSED",result.path("status").asText());for(String name:List.of("configuration","authorization","offlineAccess","calendars","tasks"))assertEquals("PASSED",result.path("checks").path(name).path("status").asText(),name);assertEquals(1,Fixture.STATES.get(hash(code).substring(0,24)).refreshes.get());noConnection();}
    }
    @Test void writePermissionIsCheckedWithoutWritingAndUnselectedCalendarIsNotRead(){
        Fixture.calendarFailure=true;String code="write-fixture-verify";var result=complete(state(begin(admin,"google",true,false,true)),code);assertEquals("PASSED",result.path("status").asText());assertEquals("PASSED",result.path("checks").path("taskWrites").path("status").asText());assertEquals("NOT_REQUESTED",result.path("checks").path("calendars").path("status").asText());assertEquals(0,Fixture.STATES.get(hash(code).substring(0,24)).writes.get());noConnection();
    }
    @Test void providerFailuresHaveSeparateRedactedResults(){
        var denied=complete(state(begin(admin,"google",true,true,false)),"denied-fixture");assertEquals("FAILED",denied.path("checks").path("authorization").path("status").asText());assertEquals("NOT_CHECKED",denied.path("checks").path("tasks").path("status").asText());
        Fixture.refreshFailure=true;Fixture.calendarFailure=true;String code="combined-fixture-failure";var selected=new TaskProviderHttpTestFixture.State();selected.catalogFailure=true;Fixture.STATES.put(hash(code).substring(0,24),selected);
        var failed=complete(state(begin(admin,"google",true,true,false)),code);for(String name:List.of("offlineAccess","calendars","tasks"))assertEquals("FAILED",failed.path("checks").path(name).path("status").asText(),name);assertEquals("PASSED",failed.path("checks").path("authorization").path("status").asText());noConnection();
    }
    @Test void changedConfigurationDuringIoCannotBeReportedAsVerified(){
        Fixture.onCalendar=()->accounts.tx(c->{try(var statement=query(c,"SELECT saved,effective FROM tk_service_settings WHERE id=1");var rows=statement.executeQuery()){assertTrue(rows.next());var saved=(ObjectNode)parse(rows.getString(1));var effective=(ObjectNode)parse(rows.getString(2));((ObjectNode)saved.get(ServiceSettings.INTERNET)).put("googleClientSecret","changed-secret");((ObjectNode)effective.get(ServiceSettings.INTERNET)).put("googleClientSecret","changed-secret");execute(c,"UPDATE tk_service_settings SET version=version+1,active_version=active_version+1,saved=?,effective=? WHERE id=1",saved.toString(),effective.toString());}catch(java.sql.SQLException failure){throw new AssertionError(failure);}return null;});
        var result=complete(state(begin(admin,"google",false,true,false)),"calendar-fixture-race");assertEquals("FAILED",result.path("status").asText());assertEquals("TASK_PROVIDER_CONFIGURATION_CHANGED",result.path("checks").path("configuration").path("code").asText());noConnection();
    }
    @Test void verificationRequiresAnAdministratorBrowserAndRetainsOwnerBrowserAndOneUseBinding(){
        var tokenInput=object().put("name","Administrator API token").putNull("expiresAt");tokenInput.putArray("permissions").add("read").add("write");
        var token=json(call(admin,"POST","/auth/tokens",tokenInput,null),201).path("token").asText();
        assertEquals(403,given().header("Authorization","Bearer "+token).header("Cookie",BrowserCookies.BROWSER+"="+AccountPasswords.secret()).contentType("application/json").body(object().put("verification",true).toString()).post("/api/v1/task-providers/google/authorize").statusCode());
        json(call(admin,"POST","/admin/users",object().put("username","ordinary").put("password",PASSWORD).put("displayName","Ordinary").put("email","ordinary@example.org").put("administrator",false),null),201);
        var ordinary=browser(call(null,"POST","/auth/login",object().put("username","ordinary").put("password",PASSWORD).put("remember",false),null));
        assertEquals(403,call(ordinary,"POST","/task-providers/google/authorize",object().put("verification",true),null).statusCode());
        String state=state(begin(admin,"google",false,true,false));var input=object().put("state",state).put("code","calendar-fixture-binding");assertEquals(409,call(ordinary,"POST","/task-providers/complete",input,null).statusCode());
        var changedBrowser=browser(call(null,"POST","/auth/login",object().put("username","admin").put("password",PASSWORD).put("remember",false),null));assertEquals(403,call(changedBrowser,"POST","/task-providers/complete",input,null).statusCode());complete(state,"calendar-fixture-binding");assertEquals(409,call(admin,"POST","/task-providers/complete",input,null).statusCode());
        String cancel=state(begin(admin,"google",true,false,false));var cancelled=json(call(admin,"POST","/task-providers/cancel",object().put("state",cancel),null),200);inputs.validate("InternetAccountVerificationCancellation",cancelled);assertEquals(409,call(admin,"POST","/task-providers/complete",object().put("state",cancel).put("code","unused"),null).statusCode());noConnection();
    }
    @Test void administratorAuthorityIsRecheckedAfterProviderIo(){
        String state=state(begin(admin,"google",false,true,false));
        Fixture.onCalendar=()->accounts.tx(c->{execute(c,"UPDATE tk_account SET administrator=FALSE WHERE owner_id=?",admin.owner());return null;});
        assertEquals(403,call(admin,"POST","/task-providers/complete",object().put("state",state).put("code","calendar-fixture-role"),null).statusCode());noConnection();
    }
    @Test void managedVerificationDiscardsItsTemporaryGrantAndNeverCreatesAConnection()throws Exception{
        var before=call(admin,"GET","/admin/internet-accounts",null,null);var input=object().put("mode","SHARED").put("serviceUrl","https://connect.example.test").put("googleClientId","").put("googleSecretAction","PRESERVE").put("currentPassword",PASSWORD);json(call(admin,"PUT","/admin/internet-accounts",input,before.header("ETag")),200);
        var authorization=begin(admin,"google",true,true,false);String id=URI.create(authorization.path("authorizationUrl").asText()).getPath().substring("/connect/".length());var pending=Fixture.PENDING.get(id);
        var result=complete(pending.state(),pending.code());assertEquals("PASSED",result.path("status").asText());noConnection();
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();while(Fixture.GRANTS.containsKey(pending.credential())&&System.nanoTime()<deadline)Thread.sleep(20);assertFalse(Fixture.GRANTS.containsKey(pending.credential()));
    }
}
