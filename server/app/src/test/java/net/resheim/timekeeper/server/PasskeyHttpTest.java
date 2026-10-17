package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(PasskeyHttpTest.Profile.class)
class PasskeyHttpTest {
    private static final String RP="passkeys.example",ORIGIN="https://passkeys.example:8443",PASSWORD="a real sufficiently long passkey HTTP password";
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:passkey_http_tests","timekeeper.auth.public-origin",ORIGIN);}
    }
    @Inject AccountStore accounts;
    @TempDir Path temporary;
    record Browser(String binding,String sid,String csrf,String owner) {
        String cookie() {return BrowserCookies.BROWSER+"="+binding+(sid==null ? "" : "; "+AuthenticationFilter.COOKIE+"="+sid);}
        @Override public String toString() {return "Fixture browser";}
    }
    private Browser anonymous() {
        var response=given().header("Origin",ORIGIN).get("/api/v1/auth/status").then().statusCode(200).extract().response();
        assertTrue(response.jsonPath().getBoolean("passkeysEnabled"));return new Browser(response.cookie(BrowserCookies.BROWSER),null,null,null);
    }
    private Browser issued(Browser browser,Response response) {
        assertEquals(200,response.statusCode(),response.asString());assertEquals(browser.binding(),response.cookie(BrowserCookies.BROWSER));
        return new Browser(browser.binding(),response.cookie(AuthenticationFilter.COOKIE),response.jsonPath().getString("csrf"),response.jsonPath().getString("ownerId"));
    }
    private Response call(Browser browser,String method,String path,Object body) {
        var request=given().header("Origin",ORIGIN).header("Cookie",browser.cookie());
        if(browser.csrf()!=null) request.header("X-CSRF-Token",browser.csrf());
        if(body!=null) request.contentType("application/json").body(body instanceof JsonNode ? body.toString() : body);
        return request.request(method,"/api/v1"+path);
    }
    private JsonNode success(Response response,int status) {assertEquals(status,response.statusCode(),response.asString());return parse(response.asString());}
    private String request(JsonNode options) {
        return accounts.tx(c->{try(var statement=query(c,"SELECT payload FROM tk_passkey_ceremony WHERE digest=?",hash(options.path("ceremony").asText()));var rows=statement.executeQuery()) {
            assertTrue(rows.next());return parse(rows.getString(1)).path("data").path("request").asText();
        }catch(java.sql.SQLException failure) {throw new AssertionError(failure);}});
    }
    private ObjectNode assertion(JsonNode options,WebAuthnProtocolTest.Authenticator key,String owner,boolean badSignature) throws Exception {
        var handle=accounts.tx(c->new PasskeyCredentials(c,RP).handle(owner));
        var body=object().put("ceremony",options.path("ceremony").asText());body.set("credential",parse(key.assertion(AssertionRequest.fromJson(request(options)),RP,ORIGIN,0x1d,0,handle,badSignature)));return body;
    }
    private Response register(Browser browser,WebAuthnProtocolTest.Authenticator key,ObjectNode proof) throws Exception {
        var options=success(call(browser,"POST","/auth/passkeys/register/options",proof.put("name","HTTP passkey")),200);
        var body=object().put("ceremony",options.path("ceremony").asText());body.set("credential",parse(key.registration(PublicKeyCredentialCreationOptions.fromJson(request(options)),RP,ORIGIN,0x5d)));
        var response=call(browser,"POST","/auth/passkeys/register/finish",body);assertEquals(201,response.statusCode(),response.asString());return response;
    }
    private String proof(Browser browser,WebAuthnProtocolTest.Authenticator key,JsonNode action) throws Exception {
        var options=success(call(browser,"POST","/auth/passkeys/proof/options",action),200);
        return success(call(browser,"POST","/auth/passkeys/proof/finish",assertion(options,key,browser.owner(),false)),200).path("proof").asText();
    }
    @Test void signedCredentialsCrossTheHttpBoundaryWithoutWeakeningOwnerProofCsrfOrSessionRevocation() throws Exception {
        String code=AccountPasswords.secret();var challenge=object().put("sha256",hash(code)).put("purpose","SETUP").put("expiresAt",Instant.now().plusSeconds(600).toString());
        var file=temporary.resolve("setup.json");Files.writeString(file,challenge.toString());ClientProxy.unwrap(accounts).setupFile=file.toString();accounts.initialize();
        try {
            var initial=anonymous();var owner=issued(initial,call(initial,"POST","/auth/setup",Map.of("code",code,"username","http-passkey","password",PASSWORD,"displayName","Passkey owner","email","passkey@example.org")));
            given().header("Cookie",owner.cookie()).header("Origin",ORIGIN).contentType("application/json").body(Map.of("name","No CSRF","currentPassword",PASSWORD)).post("/api/v1/auth/passkeys/register/options").then().statusCode(403);
            assertEquals(400,call(owner,"POST","/auth/passkeys/register/options",Map.of("name","Injected owner","currentPassword",PASSWORD,"ownerId","foreign")).statusCode());
            var firstKey=new WebAuthnProtocolTest.Authenticator("real HTTP first passkey");var first=register(owner,firstKey,object().put("currentPassword",PASSWORD));
            var firstBody=parse(first.asString());String firstId=firstBody.path("id").asText();
            var login=anonymous();
            given().header("Cookie",login.cookie()).contentType("application/json").body(Map.of("remember",true)).post("/api/v1/auth/passkeys/login/options").then().statusCode(403);
            given().header("Origin",ORIGIN).contentType("application/json").body(Map.of("remember",true)).post("/api/v1/auth/passkeys/login/options").then().statusCode(403);
            var invalid=success(call(login,"POST","/auth/passkeys/login/options",Map.of("remember",true)),200);
            assertFalse(invalid.path("publicKey").has("allowCredentials"));
            assertEquals(401,call(login,"POST","/auth/passkeys/login/finish",assertion(invalid,firstKey,owner.owner(),true)).statusCode());
            assertEquals(401,call(login,"POST","/auth/passkeys/login/finish",assertion(invalid,firstKey,owner.owner(),false)).statusCode());
            var options=success(call(login,"POST","/auth/passkeys/login/options",Map.of("remember",true)),200);
            var signedReply=call(login,"POST","/auth/passkeys/login/finish",assertion(options,firstKey,owner.owner(),false));var signed=issued(login,signedReply);
            assertTrue(signedReply.getHeaders().getValues("Set-Cookie").stream().anyMatch(v->v.startsWith(AuthenticationFilter.COOKIE+"=") && v.contains("Max-Age=")));
            assertEquals(owner.owner(),signed.owner());
            var secondKey=new WebAuthnProtocolTest.Authenticator("real HTTP second passkey");String addProof=proof(signed,firstKey,object().put("action","ADD"));
            register(signed,secondKey,object().put("proof",addProof));assertEquals(2,success(call(signed,"GET","/auth/passkeys",null),200).path("items").size());
            var renamed=given().header("Origin",ORIGIN).header("Cookie",signed.cookie()).header("X-CSRF-Token",signed.csrf()).header("If-Match",first.header("ETag")).contentType("application/json").body(Map.of("name","Renamed first key")).put("/api/v1/auth/passkeys/"+firstId).then().statusCode(200).extract().response();
            assertEquals("Renamed first key",renamed.jsonPath().getString("name"));
            success(call(signed,"POST","/auth/users",Map.of("username","http-other","password",PASSWORD,"displayName","Other owner","email","other@example.org","administrator",false)),201);
            var other=anonymous();other=issued(other,call(other,"POST","/auth/login",Map.of("username","http-other","password",PASSWORD,"remember",false)));
            assertEquals(0,success(call(other,"GET","/auth/passkeys",null),200).path("items").size());
            given().header("Origin",ORIGIN).header("Cookie",other.cookie()).header("X-CSRF-Token",other.csrf()).header("If-Match",renamed.header("ETag")).contentType("application/json").body(Map.of("name","Foreign rename")).put("/api/v1/auth/passkeys/"+firstId).then().statusCode(404);
            var tokenInput=object().put("name","Token cannot enroll").putNull("expiresAt");tokenInput.putArray("permissions").add("read").add("write");
            var token=success(call(signed,"POST","/auth/tokens",tokenInput),201).path("token").asText();
            var tokenBrowser=anonymous();var exchanged=given().header("Origin",ORIGIN).header("Cookie",tokenBrowser.cookie()).header("Authorization","Bearer "+token).post("/api/v1/auth/token-login");tokenBrowser=issued(tokenBrowser,exchanged);
            assertEquals(403,call(tokenBrowser,"GET","/auth/passkeys",null).statusCode());
            String remove=proof(signed,secondKey,object().put("action","REMOVE").put("id",firstId).put("etag",renamed.header("ETag")));
            var deleted=given().header("Origin",ORIGIN).header("Cookie",signed.cookie()).header("X-CSRF-Token",signed.csrf()).header("If-Match",renamed.header("ETag")).contentType("application/json").body(Map.of("proof",remove)).delete("/api/v1/auth/passkeys/"+firstId).then().statusCode(200).extract().response();
            assertTrue(deleted.jsonPath().getBoolean("signedOut"));assertEquals(2,deleted.getHeaders().getValues("Set-Cookie").stream().filter(v->v.contains("Max-Age=0")).count());
            assertEquals(401,call(signed,"GET","/auth/session",null).statusCode());assertEquals(1,success(call(owner,"GET","/auth/passkeys",null),200).path("items").size());
            var canceled=success(call(login,"POST","/auth/passkeys/login/options",Map.of("remember",false)),200);
            var newer=success(call(login,"POST","/auth/passkeys/login/options",Map.of("remember",false)),200);
            assertEquals(204,call(other,"POST","/auth/passkeys/cancel",Map.of("ceremony",newer.path("ceremony").asText())).statusCode());
            assertEquals(204,call(login,"POST","/auth/passkeys/cancel",Map.of("ceremony",canceled.path("ceremony").asText())).statusCode());
            assertEquals(401,call(login,"POST","/auth/passkeys/login/finish",assertion(canceled,secondKey,owner.owner(),false)).statusCode());
            issued(login,call(login,"POST","/auth/passkeys/login/finish",assertion(newer,secondKey,owner.owner(),false)));
        } finally {ClientProxy.unwrap(accounts).setupFile="NONE";}
    }
}
