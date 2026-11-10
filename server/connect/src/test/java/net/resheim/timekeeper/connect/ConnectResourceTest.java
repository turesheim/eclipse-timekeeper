package net.resheim.timekeeper.connect;

import static net.resheim.timekeeper.connect.ConnectProtocol.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(ConnectResourceTest.Profile.class)
class ConnectResourceTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){
            String url=System.getenv("TIMEKEEPER_TEST_CONNECT_DB_URL");
            return url==null?Map.of():Map.of("quarkus.datasource.db-kind","postgresql","quarkus.datasource.jdbc.url",url,
                    "quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"),"quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
        }
    }
    @Inject ConnectStore store;
    @Inject ConnectSettings settings;
    @Alternative @Priority(1) @ApplicationScoped
    public static class FixtureProvider extends ConnectProvider {
        static final AtomicInteger refreshes=new AtomicInteger();
        @Override ObjectNode exchange(String provider,ConnectSettings.App app,Services services,String code,String verifier){
            assertEquals("fixture-code",code);var result=object().put("accessToken","provider-access").put("refreshToken","private-provider-refresh").put("expiresAt",Instant.now().plusSeconds(3600).toString()).put("remoteAccountId","fixture-account").put("name","Fixture account");result.putArray("scopes").add("openid").add("https://www.googleapis.com/auth/tasks.readonly");return result;
        }
        @Override ObjectNode refresh(String provider,ConnectSettings.App app,Services services,com.fasterxml.jackson.databind.JsonNode previous){refreshes.incrementAndGet();return ((ObjectNode)previous).deepCopy().put("accessToken","renewed-access").put("refreshToken","rotated-private-refresh").put("expiresAt",Instant.now().plusSeconds(3600).toString());}
    }
    record Flow(String id,String verifier,String state,String cookie,String nonce,String oauthState){}
    private Flow consent(){
        String verifier=random(),state=random();var request=object().put("provider","google").put("returnUri","https://private-nas.example.ts.net:8443/web/task-provider-callback.html").put("state",state).put("challenge",hash(verifier));request.set("services",new Services(true,true,false).json());
        var created=given().contentType("application/json").body(request.toString()).post("/v1/requests").then().statusCode(200).extract().response();String address=created.jsonPath().getString("authorizationUrl"),id=URI.create(address).getPath().substring("/connect/".length());
        var page=given().get("/connect/"+id).then().statusCode(200).extract().response();String cookie=page.header("Set-Cookie").split(";",2)[0];var nonce=Pattern.compile("name=\"nonce\" value=\"([^\"]+)\"").matcher(page.asString());assertTrue(nonce.find());
        assertTrue(page.asString().contains("private-nas.example.ts.net:8443"));assertTrue(page.asString().contains("Read calendar events"));assertTrue(page.asString().contains("Read tasks"));assertFalse(page.asString().contains("fixture-google-secret"));assertTrue(page.header("Set-Cookie").contains("HttpOnly"));assertEquals("no-referrer",page.header("Referrer-Policy"));
        return new Flow(id,verifier,state,cookie,nonce.group(1),null);
    }
    private Flow start(Flow flow){
        var response=given().redirects().follow(false).header("Cookie",flow.cookie()).header("Origin",settings.origin().toString()).formParam("nonce",flow.nonce()).post("/connect/"+flow.id()).then().statusCode(303).extract().response();
        var target=URI.create(response.header("Location"));assertEquals("accounts.google.com",target.getHost());var query=query(target);assertEquals(settings.origin()+"/oauth/google/callback",query.get("redirect_uri"));assertEquals("S256",query.get("code_challenge_method"));assertTrue(query.get("scope").contains("calendar.readonly"));assertTrue(query.get("scope").contains("tasks.readonly"));assertFalse(response.header("Location").contains("fixture-google-secret"));
        return new Flow(flow.id(),flow.verifier(),flow.state(),flow.cookie(),flow.nonce(),query.get("state"));
    }
    private String complete(Flow flow){
        var response=given().redirects().follow(false).header("Cookie",flow.cookie()).queryParam("state",flow.oauthState()).queryParam("code","fixture-code").get("/oauth/google/callback").then().statusCode(303).extract().response();var target=URI.create(response.header("Location"));var query=query(target);
        assertEquals("private-nas.example.ts.net",target.getHost());assertEquals(flow.state(),query.get("state"));assertFalse(response.header("Location").contains("provider-access"));assertFalse(response.header("Location").contains("private-provider-refresh"));return query.get("code");
    }
    private ObjectNode redeem(Flow flow,String code){
        String body=given().contentType("application/json").body(object().put("code",code).put("verifier",flow.verifier()).toString()).post("/v1/exchanges").then().statusCode(200).extract().asString();try{return (ObjectNode)JSON.readTree(body);}catch(Exception failure){throw new AssertionError(failure);}
    }
    @Test void signInUsesAConfirmedDestinationAndABrowserAndInstallationBoundSingleUseHandoff(){
        var flow=consent();given().header("Cookie",flow.cookie()).header("Origin","https://unrelated.example").formParam("nonce",flow.nonce()).post("/connect/"+flow.id()).then().statusCode(403);
        flow=start(flow);given().redirects().follow(false).queryParam("state",flow.oauthState()).queryParam("code","fixture-code").get("/oauth/google/callback").then().statusCode(403);
        String code=complete(flow);given().contentType("application/json").body(object().put("code",code).put("verifier",random()).toString()).post("/v1/exchanges").then().statusCode(403);
        var result=redeem(flow,code);assertEquals("provider-access",result.path("accessToken").asText());assertFalse(result.toString().contains("private-provider-refresh"));assertFalse(result.has("refreshToken"));assertTrue(result.path("grantCredential").asText().matches("[A-Za-z0-9_-]{43}"));
        given().contentType("application/json").body(object().put("code",code).put("verifier",flow.verifier()).toString()).post("/v1/exchanges").then().statusCode(404);
    }
    @Test void credentialsAndProviderRefreshMaterialSurviveStoreRestartAndStayEncrypted(){
        var flow=start(consent());var result=redeem(flow,complete(flow));String credential=result.path("grantCredential").asText(),id="grant:"+hash(credential);var row=store.get(id);assertEquals("private-provider-refresh",row.value().path("tokens").path("refreshToken").asText());
        try(var connection=store.database().getConnection();var query=connection.prepareStatement("SELECT payload FROM tk_connect_record WHERE record_key=?")){
            query.setString(1,id);try(var values=query.executeQuery()){assertTrue(values.next());assertFalse(values.getString(1).contains("private-provider-refresh"));assertFalse(values.getString(1).contains(credential));}
        }catch(Exception failure){throw new AssertionError(failure);}
        var restarted=new ConnectStore();restarted.datasource=store.database();restarted.settings=settings;restarted.start();assertEquals(row.value(),restarted.get(id).value());
        var expired=row.value();((ObjectNode)expired.get("tokens")).put("expiresAt",Instant.now().minusSeconds(1).toString());store.save(row,expired);int before=FixtureProvider.refreshes.get();
        var refreshed=given().header("Authorization","Bearer "+credential).post("/v1/grants/refresh").then().statusCode(200).extract().response();assertEquals("renewed-access",refreshed.jsonPath().getString("accessToken"));assertFalse(refreshed.asString().contains("rotated-private-refresh"));assertEquals(before+1,FixtureProvider.refreshes.get());
        assertEquals("rotated-private-refresh",restarted.get(id).value().path("tokens").path("refreshToken").asText());
    }
    @Test void disconnectingOneInstallationDoesNotDisconnectAnotherGrant(){
        var first=start(consent());String a=redeem(first,complete(first)).path("grantCredential").asText();var second=start(consent());String b=redeem(second,complete(second)).path("grantCredential").asText();
        given().header("Authorization","Bearer "+a).delete("/v1/grants/current").then().statusCode(204);given().header("Authorization","Bearer "+a).post("/v1/grants/refresh").then().statusCode(401);
        given().header("Authorization","Bearer "+b).post("/v1/grants/refresh").then().statusCode(200);given().header("Authorization","Bearer "+random()).post("/v1/grants/refresh").then().statusCode(401);
    }
    @Test void expiredFlowsAndStaleStoreTransitionsCannotCreateAGrant(){
        var row=store.insert("flow:"+random(),"FLOW",Instant.now().minusSeconds(1).getEpochSecond(),object());assertThrows(ConnectFailure.class,()->store.get(row.id()));
        var current=store.insert("test:"+random(),"TEST",Long.MAX_VALUE,object().put("state","before"));store.save(current,object().put("state","after"));assertThrows(ConnectFailure.class,()->store.save(current,object().put("state","stale")));
    }
    private static Map<String,String> query(URI uri){var result=new HashMap<String,String>();for(String part:uri.getRawQuery().split("&")){var pair=part.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return result;}
}
