package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarCloudProviderTest {
    private static TaskProviderHttp.Reply reply(String body){return new TaskProviderHttp.Reply(200,Map.of(),body.getBytes(StandardCharsets.UTF_8));}
    @Test void googleIncrementalRequestsKeepPagingAndSyncTokenAndNeverExpandRecurrences(){
        var requests=new ArrayList<java.net.http.HttpRequest>();
        var provider=new CalendarCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("www.googleapis.com"),request->{requests.add(request);return reply("{\"items\":[],\"nextSyncToken\":\"next\"}");}));
        var page=provider.events("token","calendar@example.invalid","page two","previous+sync");
        String address=requests.getFirst().uri().toString();assertTrue(address.contains("calendar%40example.invalid/events"));assertTrue(address.contains("syncToken=previous%2Bsync"));assertTrue(address.contains("pageToken=page%20two"));assertTrue(address.contains("singleEvents=false"));assertEquals("next",page.syncToken());assertEquals("Bearer token",requests.getFirst().headers().firstValue("Authorization").orElseThrow());
    }
    @Test void graphSeriesIncludesAllPagedExceptionsAndRejectsOtherCalendarOrHostLinks(){
        var paths=new ArrayList<String>();String base="https://graph.microsoft.com/v1.0/me/calendars/team/events/master";
        var provider=new CalendarCloudProvider(TaskCloudProvider.Provider.MICROSOFT,new TaskProviderHttp(Set.of("graph.microsoft.com"),request->{
            paths.add(request.uri().toString());assertTrue(request.headers().firstValue("Prefer").orElseThrow().contains("ImmutableId"));
            return paths.size()==1?reply("{\"id\":\"master\",\"exceptionOccurrences\":[{\"id\":\"one\"}],\"exceptionOccurrences@odata.nextLink\":\""+base+"/exceptionOccurrences?$skiptoken=two\"}"):reply("{\"value\":[{\"id\":\"two\"}]}");
        }));
        assertEquals(2,provider.series("token","team","master").path("exceptionOccurrences").size());assertEquals(2,paths.size());
        for(String address:List.of("https://evil.invalid/steal?token=x","https://graph.microsoft.com/v1.0/users/foreign/events?$skiptoken=x","https://graph.microsoft.com/v1.0/me/calendars/other/events?$skiptoken=x"))assertThrows(ApiFailure.class,()->provider.events("token","team",address,null));
        assertEquals(2,paths.size());
    }
    @Test void catalogRejectsLoopsAndMarksAvailabilityOnlyAccessAsUnreadable(){
        var provider=new CalendarCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("www.googleapis.com"),request->reply("{\"items\":[{\"id\":\"busy\",\"summary\":\"Busy only\",\"accessRole\":\"freeBusyReader\"}]}")));
        assertFalse(provider.catalog("token").getFirst().readable());
        var loop=new CalendarCloudProvider(TaskCloudProvider.Provider.MICROSOFT,new TaskProviderHttp(Set.of("graph.microsoft.com"),request->reply("{\"value\":[],\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/me/calendars?$top=200\"}")));
        assertThrows(ApiFailure.class,()->loop.catalog("token"));
    }
}
