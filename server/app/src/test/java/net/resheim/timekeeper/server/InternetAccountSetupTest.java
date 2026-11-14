package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InternetAccountSetupTest {
    private InternetAccountSetup setup(String response,AtomicInteger requests){
        var setup=new InternetAccountSetup();setup.origins=new BrowserOrigins();setup.origins.configure("https://dagsverk.example.test:8443");
        setup.shared=new SharedConnectConfiguration();setup.shared.enabled=true;setup.shared.configured=Optional.of("https://connect.example.test");
        setup.providers=new TaskProviderConfiguration();setup.providers.origins=setup.origins;
        setup.providers.googleId=Optional.of("private-google-id");setup.providers.googleSecret=Optional.of("private-google-secret");setup.providers.microsoftId=setup.providers.microsoftSecret=Optional.empty();
        setup.clients=new TaskProviderClients(){@Override SharedConnectClient shared(URI endpoint){
            return new SharedConnectClient(endpoint,new TaskProviderHttp(Set.of(endpoint.getHost()),request->{
                requests.incrementAndGet();assertEquals("/v1/providers",request.uri().getPath());assertEquals("GET",request.method());assertTrue(request.headers().firstValue("Authorization").isEmpty());
                return new TaskProviderHttp.Reply(200,Map.of(),response.getBytes(StandardCharsets.UTF_8));
            }));
        }};return setup;
    }
    @Test void localInspectionDoesNotContactTheServiceOrExposeCredentials(){
        var calls=new AtomicInteger();var setup=setup("{}",calls);var result=setup.inspect(false);
        assertEquals(0,calls.get());assertEquals("NOT_CHECKED",result.path("status").asText());assertTrue(result.path("googleConfigured").isNull());
        assertEquals("https://connect.example.test/oauth/google/callback",result.path("googleRedirectUrl").asText());
        assertEquals("https://dagsverk.example.test:8443/web/task-provider-callback.html",result.path("callbackUrl").asText());
        assertFalse(result.toString().contains("private-google"));
    }
    @Test void checkingReportsTheServicesActualRegistrations(){
        var calls=new AtomicInteger();var result=setup("{\"protocol\":1,\"google\":false,\"microsoft\":true}",calls).inspect(true);
        assertEquals(1,calls.get());assertEquals("READY",result.path("status").asText());assertFalse(result.path("googleConfigured").asBoolean());assertTrue(result.path("microsoftConfigured").asBoolean());
    }
    @Test void malformedRepliesAreUnavailableInsteadOfImplyingGoogleIsReady(){
        var result=setup("{\"protocol\":2,\"google\":true,\"microsoft\":true,\"secret\":\"private-secret\"}",new AtomicInteger()).inspect(true);
        assertEquals("UNAVAILABLE",result.path("status").asText());assertTrue(result.path("googleConfigured").isNull());assertFalse(result.toString().contains("private-secret"));
    }
    @Test void aMissingPublicOriginPreventsChecksAndDirectModeUsesTheInstallationCallback(){
        var calls=new AtomicInteger();var setup=setup("{}",calls);setup.origins.configure("NONE");
        assertEquals("PUBLIC_ORIGIN_REQUIRED",setup.inspect(true).path("status").asText());assertEquals(0,calls.get());
        setup.origins.configure("https://dagsverk.example.test:8443");setup.shared.enabled=false;var direct=setup.inspect(true);
        assertEquals("DIRECT",direct.path("mode").asText());assertTrue(direct.path("googleConfigured").asBoolean());assertFalse(direct.path("microsoftConfigured").asBoolean());
        assertEquals(direct.path("callbackUrl"),direct.path("googleRedirectUrl"));assertTrue(direct.path("serviceUrl").isNull());assertEquals(0,calls.get());assertFalse(direct.toString().contains("private-google"));
    }    @Test void readinessCachesBrieflyAndExplicitRetryRechecksWhileInvalidCallbacksStayDisabled(){
        var calls=new AtomicInteger();var setup=setup("{\"protocol\":1,\"google\":true,\"microsoft\":false}",calls);
        assertEquals("READY",setup.readiness(false).path("google").asText());setup.readiness(false);assertEquals(1,calls.get());
        assertEquals("TASK_PROVIDER_NOT_CONFIGURED",setup.readiness(true).path("microsoft").asText());assertEquals(2,calls.get());
        setup.origins.configure("http://nas.example.test:8443");assertEquals("TASK_PROVIDER_CALLBACK_INVALID",setup.readiness(false).path("google").asText());assertEquals(2,calls.get());
        setup.origins.configure("NONE");assertEquals("TASK_PROVIDER_PUBLIC_ORIGIN_REQUIRED",setup.readiness(false).path("google").asText());assertEquals(2,calls.get());
    }
    @Test void aHealthyBrokerWithNoRegistrationsIsNotReportedReady(){
        var setup=setup("{\"protocol\":1,\"google\":false,\"microsoft\":false}",new AtomicInteger());
        assertEquals("REGISTRATION_REQUIRED",setup.inspect(true).path("status").asText());
        var readiness=setup.readiness(true);assertEquals("TASK_PROVIDER_NOT_CONFIGURED",readiness.path("google").asText());assertEquals("TASK_PROVIDER_NOT_CONFIGURED",readiness.path("microsoft").asText());
    }

}
