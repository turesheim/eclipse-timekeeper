package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskProviderOAuthTest {
    private static final Instant NOW=Instant.parse("2026-11-09T12:00:00Z");
    private static final String READ="https://www.googleapis.com/auth/tasks.readonly";
    private static final String VERIFIER="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private TaskProviderOAuth.Settings settings(){return new TaskProviderOAuth.Settings("client-id","private-client-secret","https://dagsverk.example.invalid/web/task-provider-callback.html");}
    private TaskProviderHttp.Reply reply(int status,String json){return new TaskProviderHttp.Reply(status,Map.of(),json.getBytes(StandardCharsets.UTF_8));}
    private TaskProviderOAuth oauth(TaskCloudProvider.Provider provider,TaskProviderHttp.Transport transport){return new TaskProviderOAuth(provider,new TaskProviderHttp(Set.of("oauth2.googleapis.com","login.microsoftonline.com"),transport));}
    private TaskProviderOAuth oauth(){return oauth(TaskCloudProvider.Provider.GOOGLE,request->{fail("Local flow must not make a request");return null;});}
    private Map<String,String> form(String value){
        var values=new HashMap<String,String>();for(String pair:value.split("&")){var item=pair.split("=",2);values.put(URLDecoder.decode(item[0],StandardCharsets.UTF_8),URLDecoder.decode(item[1],StandardCharsets.UTF_8));}return values;
    }
    private String body(HttpRequest request){
        var bytes=new java.io.ByteArrayOutputStream();var done=new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){byte[] data=new byte[buffer.remaining()];buffer.get(data);bytes.writeBytes(data);}
            public void onError(Throwable error){done.completeExceptionally(error);}
            public void onComplete(){done.complete(null);}
        });done.join();return bytes.toString(StandardCharsets.UTF_8);
    }
    @Test void pkceMatchesTheRfc7636S256Vector(){assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",TaskProviderOAuth.challenge(VERIFIER));}
    @Test void googleRequestsReadOnlyOfflineAccessWithUniqueStateAndNoClientSecret(){
        var first=oauth().begin(settings());var second=oauth().begin(settings());var values=form(java.net.URI.create(first.address()).getRawQuery());
        assertTrue(first.address().startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));assertFalse(first.address().contains("private-client-secret"));
        assertEquals("code",values.get("response_type"));assertEquals("openid "+READ,values.get("scope"));assertEquals("offline",values.get("access_type"));assertEquals("consent",values.get("prompt"));
        assertEquals(first.state(),values.get("state"));assertTrue(first.state().matches("[A-Za-z0-9_-]{43}"));assertNotEquals(first.state(),second.state());
        assertNotEquals(first.verifier(),second.verifier());assertEquals(TaskProviderOAuth.challenge(first.verifier()),values.get("code_challenge"));assertEquals("S256",values.get("code_challenge_method"));
        assertFalse(first.toString().contains(first.verifier()));assertFalse(settings().toString().contains("private-client-secret"));
    }
    @Test void microsoftRequestsOnlyIdentityAndReadAccessWithOfflineConsent(){
        var service=oauth(TaskCloudProvider.Provider.MICROSOFT,request->{fail();return null;});var attempt=service.begin(settings());var values=form(java.net.URI.create(attempt.address()).getRawQuery());
        assertTrue(attempt.address().startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"));assertEquals("query",values.get("response_mode"));
        assertTrue(values.get("scope").contains("offline_access"));assertTrue(values.get("scope").contains("https://graph.microsoft.com/Tasks.Read"));assertFalse(values.get("scope").contains("Tasks.ReadWrite"));
    }
    @Test void codeExchangeSendsCredentialsAndVerifierOnlyToTheTokenEndpoint(){
        var service=oauth(TaskCloudProvider.Provider.GOOGLE,request->{
            assertEquals("https://oauth2.googleapis.com/token",request.uri().toString());assertEquals("POST",request.method());assertTrue(request.timeout().orElseThrow().toSeconds()<=30);
            assertEquals("application/x-www-form-urlencoded",request.headers().firstValue("Content-Type").orElseThrow());
            var values=form(body(request));assertEquals("private code+/=&",values.get("code"));assertEquals(VERIFIER,values.get("code_verifier"));
            assertEquals("private-client-secret",values.get("client_secret"));assertEquals(settings().redirect(),values.get("redirect_uri"));
            assertFalse(request.uri().toString().contains("private"));assertFalse(request.headers().firstValue("Cookie").isPresent());
            return reply(200,"{\"access_token\":\"private-access\",\"refresh_token\":\"private-refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"openid "+READ+"\"}");
        });
        var tokens=service.exchange(settings(),"private code+/=&",VERIFIER,NOW);assertEquals(NOW.plusSeconds(3600),tokens.expires());
        assertEquals("private-access",tokens.access());assertEquals("private-refresh",tokens.refresh());assertFalse(tokens.toString().contains("private"));
    }
    @Test void refreshRetainsAnUnreturnedRefreshTokenAndScopeAndAcceptsRotation(){
        var prior=new TaskProviderOAuth.Tokens("old-access","previous-refresh",NOW,Set.of("openid",READ));
        var calls=new java.util.concurrent.atomic.AtomicInteger();var service=oauth(TaskCloudProvider.Provider.GOOGLE,request->{
            var values=form(body(request));assertEquals("refresh_token",values.get("grant_type"));assertEquals("previous-refresh",values.get("refresh_token"));assertFalse(values.containsKey("code_verifier"));
            return reply(200,calls.incrementAndGet()==1?"{\"access_token\":\"new-access\",\"token_type\":\"Bearer\",\"expires_in\":1800}":"{\"access_token\":\"new-access\",\"refresh_token\":\"rotated-refresh\",\"token_type\":\"Bearer\",\"expires_in\":1800}");
        });
        assertEquals("previous-refresh",service.refresh(settings(),prior,NOW).refresh());assertEquals("rotated-refresh",service.refresh(settings(),prior,NOW).refresh());
    }
    @Test void grantedScopesAreCheckedAndMissingOfflineAccessRequiresReconnect(){
        for(String[] response:List.of(new String[]{"{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"openid\"}","TASK_PROVIDER_PERMISSION_REQUIRED"},new String[]{"{\"access_token\":\"access\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"openid "+READ+"\"}","TASK_PROVIDER_OFFLINE_ACCESS_REQUIRED"})){
            var service=oauth(TaskCloudProvider.Provider.GOOGLE,request->reply(200,response[0]));assertEquals(response[1],assertThrows(TaskProviderHttp.Failure.class,()->service.exchange(settings(),"code",VERIFIER,NOW)).getMessage());
        }
    }
    @Test void microsoftAcceptsQualifiedOrShortGrantedScopes(){
        for(String scope:List.of("Tasks.Read User.Read","https://graph.microsoft.com/Tasks.Read https://graph.microsoft.com/User.Read")){
            var service=oauth(TaskCloudProvider.Provider.MICROSOFT,request->reply(200,"{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\""+scope+"\"}"));
            assertEquals("access",service.exchange(settings(),"code",VERIFIER,NOW).access());
        }
    }
    @Test void invalidGrantIsSafeReconnectStateAndNeverIncludesProviderBody(){
        var service=oauth(TaskCloudProvider.Provider.GOOGLE,request->reply(400,"{\"error\":\"invalid_grant\",\"error_description\":\"private-refresh and private-client-secret\"}"));
        var failure=assertThrows(TaskProviderHttp.Failure.class,()->service.exchange(settings(),"code",VERIFIER,NOW));assertEquals("TASK_PROVIDER_RECONNECT_REQUIRED",failure.getMessage());assertFalse(failure.toString().contains("private"));
    }
    @ParameterizedTest @ValueSource(strings={"http://remote.invalid/callback","javascript:alert(1)","https://user:password@host.invalid/callback","https://host.invalid/callback?secret=1","https://host.invalid/callback#secret"})
    void unsafeCallbackConfigurationIsRejected(String callback){assertThrows(IllegalArgumentException.class,()->new TaskProviderOAuth.Settings("id","secret",callback));}
    @Test void invalidCodeOrProofCannotSendTheClientSecret(){
        for(String verifier:List.of("short",VERIFIER+"\n"))assertThrows(TaskProviderHttp.Failure.class,()->oauth().exchange(settings(),"code",verifier,NOW));
        assertThrows(TaskProviderHttp.Failure.class,()->oauth().exchange(settings(),"code\n",VERIFIER,NOW));
    }
}
