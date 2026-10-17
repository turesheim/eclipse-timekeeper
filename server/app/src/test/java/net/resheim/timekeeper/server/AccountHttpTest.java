package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import java.util.*;
import java.time.*;
import java.nio.file.*;
import java.util.concurrent.*;
import io.restassured.response.Response;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;

@QuarkusTest @TestProfile(AccountHttpTest.Profile.class)
class AccountHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:account_http_tests");}
    }
    @Inject AccountStore accounts;
    @Inject ServerStore store;
    @Inject TokenRegistry registry;
    @TempDir Path temporary;
    private static final String PASSWORD="a sufficiently long HTTP passphrase";
    record Browser(String cookie,String csrf,String owner) { }
    private JsonNode node(String value) {return JsonValues.parse(value);}
    private Response call(Browser browser,String method,String path,JsonNode body,boolean csrf) {
        var request=given();if(browser!=null) {request.header("Cookie",browser.cookie());if(csrf) request.header("X-CSRF-Token",browser.csrf());}
        if(body!=null) request.contentType("application/json").body(body.toString());return request.request(method,"/api/v1"+path);
    }
    private Browser browser(Response reply) {
        assertEquals(200,reply.statusCode(),reply.asString());String cookie=reply.header("Set-Cookie");assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("Secure"));assertTrue(cookie.contains("SameSite=Lax"));
        assertFalse(reply.asString().contains(cookie.split(";",2)[0].split("=",2)[1]));assertEquals("no-store",reply.header("Cache-Control"));
        return new Browser(cookie.split(";",2)[0],reply.jsonPath().getString("csrf"),reply.jsonPath().getString("ownerId"));
    }
    @BeforeEach void clear() throws Exception {
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);actual.stopMaintenance();
        try(var c=actual.datasource.getConnection();var s=c.createStatement()) {
            for(String table:List.of("tk_recovery_link","tk_recovery_address","tk_auth_session","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner","tk_auth_throttle")) s.executeUpdate("DELETE FROM "+table);
            s.executeUpdate("UPDATE tk_auth_control SET bootstrap_completed=FALSE");
        }
    }
    @AfterEach void reset() {io.quarkus.arc.ClientProxy.unwrap(accounts).setupFile="NONE";}
    private Browser setup() throws Exception {
        String code=AccountPasswords.secret();var config=JsonValues.object();config.put("sha256",JsonValues.hash(code));config.put("purpose","SETUP");config.put("expiresAt",Instant.now().plusSeconds(600).toString());
        Path file=temporary.resolve("setup.json");Files.writeString(file,config.toString());io.quarkus.arc.ClientProxy.unwrap(accounts).setupFile=file.toString();accounts.initialize();
        var body=JsonValues.object();body.put("code",code);body.put("username","admin");body.put("password",PASSWORD);body.put("displayName","Administrator");body.put("email","admin@example.org");
        Browser result=browser(call(null,"POST","/auth/setup",body,false));assertEquals(401,call(null,"POST","/auth/setup",body,false).statusCode());return result;
    }
    @Test void administrationHasASeparateNamespaceAndSettingsNeverExposeDeploymentSecrets() throws Exception {
        var admin=setup();
        assertEquals(401,call(null,"GET","/admin/settings",null,false).statusCode());
        assertEquals(404,call(admin,"GET","/auth/users",null,false).statusCode(),"The retired route would bypass an admin-prefix proxy policy");
        var reply=call(admin,"GET","/admin/settings",null,false);assertEquals(200,reply.statusCode());assertEquals("no-store",reply.header("Cache-Control"));
        var settings=node(reply.asString());
        assertEquals(Set.of("configurationManagedBy","databaseType","schemaVersion","publicOrigin","originEpoch","passkeysEnabled","passkeyRpId","emailRecoveryEnabled","version","effectiveVersion","restartRequired","smtpPasswordConfigured","effectiveSmtpPasswordConfigured","saved","effective","canEdit"),
                JsonValues.MAPPER.convertValue(settings,java.util.Map.class).keySet());
        assertEquals("administration",settings.path("configurationManagedBy").asText());assertEquals("h2",settings.path("databaseType").asText());
        assertEquals(ServerSchema.CURRENT,settings.path("schemaVersion").asInt());assertTrue(settings.path("publicOrigin").isNull());assertTrue(settings.path("passkeyRpId").isNull());
        assertEquals(400,call(admin,"GET","/admin/settings?unexpected=1",null,false).statusCode());
        assertEquals(201,call(admin,"POST","/admin/users",node("{\"username\":\"ordinary\",\"password\":\""+PASSWORD+"\",\"displayName\":\"Ordinary user\",\"email\":\"ordinary@example.org\",\"administrator\":false}"),true).statusCode());
        var user=browser(call(null,"POST","/auth/login",node("{\"username\":\"ordinary\",\"password\":\""+PASSWORD+"\",\"remember\":false}"),false));
        assertEquals(403,call(user,"GET","/admin/settings",null,false).statusCode());
        assertEquals(403,call(user,"GET","/admin/users",null,false).statusCode());
        var token=call(admin,"POST","/auth/tokens",node("{\"name\":\"Read-only client\",\"permissions\":[\"read\"],\"expiresAt\":null}"),true);
        assertEquals(201,token.statusCode());
        assertEquals(403,given().header("Authorization","Bearer "+node(token.asString()).path("token").asText()).get("/api/v1/admin/settings").statusCode());
    }
    @Test void browserSessionsUseCsrfAndAccountBoundDataAndLogoutRevokesOnlyTheCurrentSession() throws Exception {
        var admin=setup();
        var passkeys=call(admin,"GET","/auth/passkeys",null,false);assertEquals(200,passkeys.statusCode());assertFalse(node(passkeys.asString()).path("enabled").asBoolean());
        assertEquals(200,call(admin,"GET","/me",null,false).statusCode());
        var labels=call(admin,"GET","/labels",null,false);assertEquals(8,node(labels.asString()).get("items").size(),"New account did not receive owned default labels");
        var input=node("{\"name\":\"Private client\",\"color\":\"#ffee00\"}");
        assertEquals(403,call(admin,"POST","/clients",input,false).statusCode());
        assertEquals(403,given().header("Cookie",admin.cookie()).header("X-CSRF-Token",admin.csrf()).header("Origin","https://other.invalid").contentType("application/json").body(input.toString()).post("/api/v1/clients").statusCode());
        var client=call(admin,"POST","/clients",input,true);assertEquals(201,client.statusCode());String id=node(client.asString()).get("id").asText();
        var login=node("{\"username\":\"admin\",\"password\":\""+PASSWORD+"\",\"remember\":true}");
        var secondReply=call(null,"POST","/auth/login",login,false);assertTrue(secondReply.header("Set-Cookie").contains("Max-Age="));var second=browser(secondReply);
        assertEquals(400,given().header("Cookie",admin.cookie()).header("Authorization","Bearer test-a").get("/api/v1/me").statusCode());
        assertEquals(400,call(admin,"POST","/auth/tokens",node("{\"name\":\"Injected\",\"permissions\":[\"read\"],\"expiresAt\":null,\"ownerId\":\"another\"}"),true).statusCode());
        assertEquals(204,call(admin,"POST","/auth/logout",null,true).statusCode());assertEquals(401,call(admin,"GET","/me",null,false).statusCode());
        assertEquals(200,call(second,"GET","/clients/"+id,null,false).statusCode());
        var token=call(second,"POST","/auth/tokens",node("{\"name\":\"Eclipse\",\"permissions\":[\"read\",\"write\"],\"expiresAt\":null}"),true);assertEquals(201,token.statusCode());
        var raw=node(token.asString()).get("token").asText();
        try(var eclipseClient=new net.resheim.eclipse.timekeeper.client.HttpTimekeeperService(java.net.URI.create(io.restassured.RestAssured.baseURI+":"+io.restassured.RestAssured.port),()->raw,Duration.ofSeconds(5))) {
            assertEquals(second.owner(),eclipseClient.profile().owner().value());
            var project=eclipseClient.createProject(new net.resheim.eclipse.timekeeper.service.Commands.CreateProject("Managed Eclipse token project"));
            assertTrue(eclipseClient.projects().stream().anyMatch(value->value.id().equals(project.id())));
        }
        assertEquals(200,given().header("Authorization","Bearer "+raw).get("/api/v1/me").statusCode());
        assertFalse(call(second,"GET","/auth/tokens",null,false).asString().contains(raw));
        String tokenId=node(token.asString()).get("id").asText();assertEquals(204,call(second,"DELETE","/auth/tokens/"+tokenId,null,true).statusCode());
        assertEquals(401,given().header("Authorization","Bearer "+raw).get("/api/v1/me").statusCode());assertEquals(200,call(second,"GET","/me",null,false).statusCode());
    }
    @Test void administratorCannotReadOtherBusinessDataAndDisableTerminatesOutstandingPolls() throws Exception {
        var admin=setup();var user=call(admin,"POST","/admin/users",node("{\"username\":\"other\",\"password\":\""+PASSWORD+"\",\"displayName\":\"Other\",\"email\":\"other@example.org\",\"administrator\":false}"),true);
        assertEquals(201,user.statusCode());String owner=node(user.asString()).get("user").get("ownerId").asText();
        var other=browser(call(null,"POST","/auth/login",node("{\"username\":\"other\",\"password\":\""+PASSWORD+"\",\"remember\":false}"),false));assertEquals(owner,other.owner());
        var created=call(other,"POST","/clients",node("{\"name\":\"Other private client\",\"color\":\"#102030\"}"),true);assertEquals(201,created.statusCode());String id=node(created.asString()).get("id").asText();
        assertEquals(404,call(admin,"GET","/clients/"+id,null,false).statusCode());assertEquals(403,call(other,"GET","/admin/users",null,false).statusCode());
        assertEquals(404,call(admin,"POST","/projects",node("{\"name\":\"Foreign client\",\"clientId\":\""+id+"\"}"),true).statusCode());
        long revision=node(call(other,"GET","/projects",null,false).asString()).get("revision").asLong();
        String key=OwnerBinding.encode(owner);var loaded=call(admin,"GET","/admin/users/"+key,null,false);assertEquals(200,loaded.statusCode());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var poll=pool.submit(()->call(other,"GET","/changes?after="+revision+"&waitSeconds=25",null,false));
            Thread.sleep(300);assertFalse(poll.isDone());
            var body=node("{\"username\":\"other\",\"displayName\":\"Other\",\"enabled\":false,\"administrator\":false}");
            var disabled=given().header("Cookie",admin.cookie()).header("X-CSRF-Token",admin.csrf()).header("If-Match",loaded.header("ETag")).contentType("application/json").body(body.toString()).put("/api/v1/admin/users/"+key);assertEquals(200,disabled.statusCode(),disabled.asString());
            assertEquals(401,poll.get(5,TimeUnit.SECONDS).statusCode());
        }
        assertEquals(401,call(other,"GET","/clients/"+id,null,false).statusCode());
    }
    @Test void tokenMigrationUsesAnHttpOnlySessionAndStillHonorsParentTokenRevocation() {
        var reply=given().header("Cookie",AuthenticationFilter.COOKIE+"=obsolete-cookie").header("Authorization","Bearer test-read").post("/api/v1/auth/token-login");var session=browser(reply);
        assertEquals("[\"read\"]",node(reply.asString()).get("permissions").toString());
        assertEquals(403,call(session,"POST","/clients",node("{\"name\":\"Forbidden\",\"color\":\"#ffffff\"}"),true).statusCode());
        var writable=registry.authenticate("Bearer test-a");var tokens=accounts.tokens(writable).get("items");
        for(var token:tokens) if(token.get("permissions").size()==1) accounts.revokeToken(writable,UUID.fromString(token.get("id").asText()));
        assertEquals(401,call(session,"GET","/me",null,false).statusCode());
    }
    @Test void concurrentLegacyRequestsAndCookieRequestsKeepTheSameLockOrder() throws Exception {
        var session=browser(given().header("Authorization","Bearer test-a").post("/api/v1/auth/token-login"));
        try(var pool=Executors.newFixedThreadPool(6)) {
            var jobs=new ArrayList<Future<?>>();
            for(int index=0;index<6;index++) {final boolean cookie=index%2==0;jobs.add(pool.submit(()->{
                for(int attempt=0;attempt<12;attempt++) {
                    var reply=cookie ? call(session,"GET","/projects",null,false) : given().header("Authorization","Bearer test-a").get("/api/v1/projects");
                    assertEquals(200,reply.statusCode(),"Concurrent authorization failed");
                }
            }));}
            for(var job:jobs) job.get(20,TimeUnit.SECONDS);
        }
    }

}
