package net.resheim.timekeeper.connect;

import static net.resheim.timekeeper.connect.ConnectProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConnectProtocolTest {
    @Test void returnsOnlyToTheFixedCallbackOnAnHttpsInstallationOrLocalDevelopmentHost(){
        assertEquals("https://private-nas.example.ts.net:8443/web/task-provider-callback.html",callback("https://private-nas.example.ts.net:8443/web/task-provider-callback.html").toString());
        assertEquals("localhost",callback("http://localhost:8080/web/task-provider-callback.html").getHost());
        for(String value:new String[]{"http://example.net/web/task-provider-callback.html","https://user:secret@example.net/web/task-provider-callback.html","https://example.net/other","https://example.net/web/task-provider-callback.html?next=evil","https://example.net/web/task-provider-callback.html#secret","javascript:alert(1)"})assertThrows(ConnectFailure.class,()->callback(value));
    }
    @Test void permissionsFollowTheSelectedSynchronizersAndDoNotRequestTaskWritesByDefault(){
        String calendar=ConnectProvider.scopes("google",new Services(false,true,false));assertTrue(calendar.contains("calendar.readonly"));assertFalse(calendar.contains("tasks"));
        String tasks=ConnectProvider.scopes("google",new Services(true,false,false));assertTrue(tasks.contains("tasks.readonly"));assertFalse(tasks.contains("calendar"));
        String both=ConnectProvider.scopes("google",new Services(true,true,true));assertTrue(both.contains("https://www.googleapis.com/auth/tasks "));assertTrue(both.contains("calendar.readonly"));
        String microsoft=ConnectProvider.scopes("microsoft",new Services(true,true,false));assertTrue(microsoft.contains("Calendars.Read"));assertTrue(microsoft.contains("Tasks.Read"));assertFalse(microsoft.contains("ReadWrite"));
        assertThrows(ConnectFailure.class,()->new Services(false,false,false));assertThrows(ConnectFailure.class,()->new Services(false,true,true));assertThrows(ConnectFailure.class,()->ConnectProvider.scopes("microsoft",new Services(true,false,true)));
    }
    @Test void acceptsTheStandardS256ChallengeAndEscapesDisplayedInstallationNames(){
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",hash("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertEquals("&lt;script&gt;&amp;&quot;&#39;",html("<script>&\"'"));assertTrue(random().matches("[A-Za-z0-9_-]{43}"));
    }
    @Test void repeatedUnauthenticatedRequestsAreLimitedPerPeerAndRecoverAfterTheWindow(){
        var limits=new ConnectLimits();for(int index=0;index<100;index++)limits.acquire("peer-a",1000);
        assertEquals(429,assertThrows(ConnectFailure.class,()->limits.acquire("peer-a",1001)).status);limits.acquire("peer-b",1001);limits.acquire("peer-a",1600);
    }
}
