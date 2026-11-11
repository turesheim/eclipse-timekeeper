package net.resheim.timekeeper.connect;

import static net.resheim.timekeeper.connect.ConnectProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.resheim.timekeeper.server.TaskProviderHttp;

class ConnectProviderTest {
    private static final ConnectSettings.App APP=new ConnectSettings.App("app-id","private-app-secret","https://connect.example.test/oauth/google/callback");
    private static TaskProviderHttp.Reply reply(int status,String body){return new TaskProviderHttp.Reply(status,Map.of(),body.getBytes(StandardCharsets.UTF_8));}
    @Test void calendarOnlyConsentAndRefreshDoNotRequireOrRequestTaskPermission(){
        var calls=new AtomicInteger();var provider=new ConnectProvider(new TaskProviderHttp(Set.of("oauth2.googleapis.com","openidconnect.googleapis.com"),request->{
            calls.incrementAndGet();if(request.uri().getHost().equals("openidconnect.googleapis.com"))return reply(200,"{\"sub\":\"account\",\"email\":\"person@example.test\",\"name\":\"Person\"}");
            assertEquals("POST",request.method());assertEquals("https://oauth2.googleapis.com/token",request.uri().toString());return reply(200,"{\"access_token\":\"provider-access\",\"refresh_token\":\"private-provider-refresh\",\"expires_in\":3600,\"token_type\":\"Bearer\",\"scope\":\"openid email profile https://www.googleapis.com/auth/calendar.readonly\"}");
        }));
        var services=new Services(false,true,false);String address=provider.authorize("google",APP,services,random(),random());assertFalse(address.contains("tasks"));assertFalse(address.contains("private-app-secret"));
        var tokens=provider.exchange("google",APP,services,"code",random());assertEquals("person@example.test",tokens.path("name").asText());assertEquals("account",tokens.path("remoteAccountId").asText());assertEquals(2,calls.get());
        assertEquals("account",provider.refresh("google",APP,services,tokens).path("remoteAccountId").asText());assertEquals(3,calls.get());
    }
    @Test void refusedRefreshAndMissingPermissionHaveRecoverableCodesWithoutProviderBodies(){
        var refused=new ConnectProvider(new TaskProviderHttp(Set.of("oauth2.googleapis.com"),request->reply(400,"{\"error\":\"invalid_grant\",\"error_description\":\"private-provider-detail\"}")));
        var previous=object().put("refreshToken","private-provider-refresh");var failure=assertThrows(ConnectFailure.class,()->refused.refresh("google",APP,new Services(true,false,false),previous));assertEquals(409,failure.status);assertEquals("RECONNECT_REQUIRED",failure.code);assertFalse(failure.getMessage().contains("private-provider-detail"));
        var missing=new ConnectProvider(new TaskProviderHttp(Set.of("oauth2.googleapis.com"),request->reply(200,"{\"access_token\":\"provider-access\",\"refresh_token\":\"private-provider-refresh\",\"expires_in\":3600,\"token_type\":\"Bearer\",\"scope\":\"openid\"}")));
        assertEquals("PERMISSION_REQUIRED",assertThrows(ConnectFailure.class,()->missing.exchange("google",APP,new Services(true,false,false),"code",random())).code);
    }
}
