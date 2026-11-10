package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(InternetCalendarsTest.Profile.class)
class InternetCalendarsTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return TaskProviderTestDatabase.settings("internet_calendars");}
    }
    @Inject ServerStore store;
    record Account(String owner,UUID connection,UUID client){}
    private <T>T tx(Account account,Supplier<T> work){return store.transaction(account.owner(),work);}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    private Account account(boolean tasks){
        return account(tasks,TaskCloudProvider.Provider.GOOGLE);
    }
    private Account account(boolean tasks,TaskCloudProvider.Provider provider){
        String owner="internet-calendar-"+UUID.randomUUID();return store.transaction(owner,()->{
            var services=object().put("tasks",tasks).put("calendars",true).put("writableTasks",false);var pending=connections().beginShared(provider,URI.create("https://connect.example.test"),"browser",services);var grant=connections().consume(pending.state(),"browser");
            var tokens=new TaskProviderOAuth.Tokens("private-access","private-shared-grant",Instant.now().plusSeconds(3600),Set.of("openid","https://www.googleapis.com/auth/tasks.readonly","https://www.googleapis.com/auth/calendar.readonly"));
            var taskLists=tasks?List.of(new TaskCloudProvider.RemoteList("tasks","Tasks","STANDARD",false,true,parse("{\"id\":\"tasks\",\"title\":\"Tasks\"}"))):List.<TaskCloudProvider.RemoteList>of();
            var connection=connections().connectShared(grant,new TaskCloudProvider.Account("google-account","Google account"),tokens,taskLists);UUID id=uuid(connection.get("id"));
            UUID client=uuid(store.createClient(object().put("name","Calendar client").put("color","#123456")).get("id"));
            new InternetCalendars(store).catalog(id,List.of(new CalendarCloudProvider.RemoteCalendar("work","Work",true),new CalendarCloudProvider.RemoteCalendar("unselected","Unselected",true)));return new Account(owner,id,client);
        });
    }
    private ObjectNode select(Account account){return tx(account,()->{
        var input=object();input.putArray("selectedCalendars").add("work");input.putObject("calendarMappings").put("work",account.client().toString());var before=connections().view(account.connection());return new InternetCalendars(store).select(account.connection(),input,etag(before));
    });}
    private TaskProviderConnections.Claim claim(Account account){return tx(account,()->connections().claim(account.connection(),false));}
    private static ObjectNode event(String id,String title){return (ObjectNode)parse("{\"id\":\""+id+"\",\"summary\":\""+title+"\",\"start\":{\"date\":\"2026-11-10\"},\"end\":{\"date\":\"2026-11-11\"}}");}
    private static TaskProviderHttp.Reply reply(int status,ObjectNode value){return new TaskProviderHttp.Reply(status,Map.of(),value.toString().getBytes(StandardCharsets.UTF_8));}
    @Test void calendarOnlySelectionDoesNotCreateTasksAndDoesNotChangeTaskDraftVersions(){
        var account=account(false);long taskVersion=tx(account,()->connections().view(account.connection()).path("configurationVersion").asLong());var chosen=select(account);
        assertEquals(taskVersion,chosen.path("configurationVersion").asLong());assertEquals(1,chosen.path("selectedCalendars").size());assertEquals(0,chosen.path("selectedLists").size());
        var requests=new ArrayList<String>();var provider=new CalendarCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("www.googleapis.com"),request->{assertEquals("GET",request.method());requests.add(request.uri().toString());var page=object().put("nextSyncToken","sync-1");page.putArray("items").add(event("meeting","Planning"));return reply(200,page);}));
        var claim=claim(account);new InternetCalendars(store).synchronize(claim,provider,"private-access");
        tx(account,()->{assertTrue(store.service().tasks().isEmpty());assertTrue(store.list("ACTIVITY").isEmpty());var events=new CalendarSubscriptions(store).events("2026-11-09","2026-11-16").path("items");assertEquals(1,events.size(),()->"Calendar result="+new CalendarSubscriptions(store).events("2026-11-09","2026-11-16")+" metadata="+store.list(CalendarSubscriptions.SUB)+" cached="+store.list(CalendarSubscriptions.FEED)+" error="+connections().required(account.connection()).path("calendarLastError"));assertEquals("Planning",events.get(0).path("title").asText());assertEquals("sync-1",store.find(InternetCalendars.CACHE,InternetCalendars.id(account.connection(),"work")).orElseThrow().path("syncToken").asText());return null;});
        assertEquals(1,requests.size());assertTrue(requests.getFirst().contains("/calendars/work/events"));assertTrue(requests.stream().noneMatch(value->value.contains("unselected")||value.contains("/tasks")));
    }
    @Test void interruptedPagesResumeAndOnlyTheCompleteScanAdvancesThePublishedCheckpoint(){
        var account=account(false);select(account);var claim=claim(account);var firstPage=new AtomicInteger();var secondPage=new AtomicInteger();boolean[] fail={true};
        var provider=new CalendarCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("www.googleapis.com"),request->{
            var page=object();if(request.uri().getRawQuery().contains("pageToken=page-2")){secondPage.incrementAndGet();if(fail[0])return reply(503,object());page.put("nextSyncToken","complete");page.putArray("items").add(event("second","Second"));}
            else{firstPage.incrementAndGet();page.put("nextPageToken","page-2");page.putArray("items").add(event("first","First"));}return reply(200,page);
        }));
        new InternetCalendars(store).synchronize(claim,provider,"private-access");UUID id=InternetCalendars.id(account.connection(),"work");
        tx(account,()->{assertTrue(store.find(InternetCalendars.CACHE,id).isEmpty());assertTrue(store.find(CalendarSubscriptions.FEED,id).isEmpty());assertEquals("page-2",store.find(InternetCalendars.SCAN,id).orElseThrow().path("cursor").asText());var auth=connections().required(account.connection());auth.putNull("calendarRetryUntil");connections().save(TaskProviderConnections.AUTH,auth);return null;});
        fail[0]=false;new InternetCalendars(store).synchronize(claim,provider,"private-access");
        tx(account,()->{assertEquals(2,new CalendarSubscriptions(store).events("2026-11-09","2026-11-16").path("items").size(),()->"metadata="+store.list(CalendarSubscriptions.SUB)+" cached="+store.list(CalendarSubscriptions.FEED)+" error="+connections().required(account.connection()).path("calendarLastError"));assertEquals("complete",store.find(InternetCalendars.CACHE,id).orElseThrow().path("syncToken").asText());assertTrue(store.find(InternetCalendars.SCAN,id).isEmpty());return null;});assertEquals(1,firstPage.get());assertEquals(2,secondPage.get());
    }
    @Test void foreignAccountsAndClientsCannotBeSelectedOrReadThroughAnInternetAccount(){
        var account=account(false);var other=account(false);select(account);
        tx(other,()->{
            assertThrows(ApiFailure.class,()->connections().required(account.connection()));assertTrue(new CalendarSubscriptions(store).list().path("items").isEmpty());return null;
        });
        tx(account,()->{var input=object();input.putArray("selectedCalendars").add("work");input.putObject("calendarMappings").put("work",other.client().toString());assertThrows(ApiFailure.class,()->new InternetCalendars(store).select(account.connection(),input,etag(connections().view(account.connection()))));assertEquals(account.client().toString(),new CalendarSubscriptions(store).required(InternetCalendars.id(account.connection(),"work")).path("clientId").asText());return null;});
    }
    @Test void deselectionFencesAnInFlightCalendarResultAndKeepsTheTaskSelection(){
        var account=account(true);tx(account,()->connections().select(account.connection(),List.of("tasks"),etag(connections().view(account.connection()))));select(account);var claim=claim(account);
        var provider=new CalendarCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("www.googleapis.com"),request->{
            tx(account,()->{var input=object();input.putArray("selectedCalendars");input.putObject("calendarMappings");new InternetCalendars(store).select(account.connection(),input,etag(connections().view(account.connection())));return null;});var page=object().put("nextSyncToken","must-not-publish");page.putArray("items").add(event("late","Late"));return reply(200,page);
        }));new InternetCalendars(store).synchronize(claim,provider,"private-access");
        tx(account,()->{assertEquals("tasks",connections().view(account.connection()).path("selectedLists").get(0).asText());assertTrue(store.list(CalendarSubscriptions.SUB).isEmpty());assertTrue(store.list(InternetCalendars.CACHE).isEmpty());return null;});
    }
    @Test void deletingAManagedCalendarRemovesOnlyItsSelectionEvenWhenProviderAccessIsDisabled(){
        var account=account(true);select(account);tx(account,()->{var auth=connections().required(account.connection());((ObjectNode)auth.get("services")).put("calendars",false);connections().save(TaskProviderConnections.AUTH,auth);UUID id=InternetCalendars.id(account.connection(),"work");var calendars=new CalendarSubscriptions(store);new InternetCalendars(store).removeSubscription(id,etag(calendars.visible(calendars.required(id))));assertTrue(connections().view(account.connection()).path("selectedCalendars").isEmpty());assertTrue(store.list(CalendarSubscriptions.SUB).isEmpty());return null;});
    }
    @Test void expiredGoogleSyncTokenKeepsTheLastGoodCacheUntilACompleteReplacement(){
        var account=account(false);select(account);var claim=claim(account);var phase=new AtomicInteger();var addresses=new ArrayList<String>();
        var provider=new CalendarCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("www.googleapis.com"),request->{
            String address=request.uri().toString();addresses.add(address);
            if(phase.get()==0){var page=object().put("nextSyncToken","old-token");page.putArray("items").add(event("old","Old"));return reply(200,page);}
            if(address.contains("syncToken=old-token"))return reply(410,object());
            if(phase.get()==1)return reply(503,object());
            var page=object().put("nextSyncToken","replacement-token");page.putArray("items").add(event("new","New"));return reply(200,page);
        }));
        var calendars=new InternetCalendars(store);calendars.synchronize(claim,provider,"private-access");phase.set(1);calendars.synchronize(claim,provider,"private-access");
        UUID id=InternetCalendars.id(account.connection(),"work");tx(account,()->{assertEquals("old-token",store.find(InternetCalendars.CACHE,id).orElseThrow().path("syncToken").asText());assertEquals("Old",new CalendarSubscriptions(store).events("2026-11-09","2026-11-16").path("items").get(0).path("title").asText());var auth=connections().required(account.connection());auth.putNull("calendarRetryUntil");connections().save(TaskProviderConnections.AUTH,auth);return null;});
        phase.set(2);calendars.synchronize(claim,provider,"private-access");tx(account,()->{assertEquals("replacement-token",store.find(InternetCalendars.CACHE,id).orElseThrow().path("syncToken").asText());assertEquals("New",new CalendarSubscriptions(store).events("2026-11-09","2026-11-16").path("items").get(0).path("title").asText());return null;});assertTrue(addresses.stream().anyMatch(value->value.contains("syncToken=old-token")));
    }
    @Test void microsoftExceptionDefinitionsShareTheCalendarBudgetAndPreserveTheLastGoodCache(){
        var account=account(false,TaskCloudProvider.Provider.MICROSOFT);select(account);var claim=claim(account);boolean[] expanded={false};var details=new AtomicInteger();
        var provider=new CalendarCloudProvider(TaskCloudProvider.Provider.MICROSOFT,new TaskProviderHttp(Set.of("graph.microsoft.com"),request->{
            if(request.uri().getPath().endsWith("/events")){
                var page=object();var values=page.putArray("value");if(!expanded[0])values.add(parse("{\"id\":\"old\",\"type\":\"singleInstance\",\"subject\":\"Last good event\",\"start\":{\"dateTime\":\"2026-11-10T09:00:00\",\"timeZone\":\"UTC\"},\"end\":{\"dateTime\":\"2026-11-10T10:00:00\",\"timeZone\":\"UTC\"}}"));
                else for(int i=0;i<11;i++)values.add(object().put("id","master-"+i).put("type","seriesMaster"));return reply(200,page);
            }
            String master=request.uri().getPath().substring(request.uri().getPath().lastIndexOf('/')+1);details.incrementAndGet();var value=object().put("id",master);var exceptions=value.putArray("exceptionOccurrences");for(int i=0;i<500;i++)exceptions.add(object().put("id",master+"-exception-"+i));return reply(200,value);
        }));
        var calendars=new InternetCalendars(store);calendars.synchronize(claim,provider,"private-access");expanded[0]=true;calendars.synchronize(claim,provider,"private-access");
        tx(account,()->{assertEquals("TASK_PROVIDER_RESPONSE_TOO_LARGE",connections().view(account.connection()).path("calendarLastError").asText());assertEquals("Last good event",new CalendarSubscriptions(store).events("2026-11-09","2026-11-16").path("items").get(0).path("title").asText());assertEquals(1,store.find(InternetCalendars.CACHE,InternetCalendars.id(account.connection(),"work")).orElseThrow().path("events").size());return null;});assertEquals(10,details.get());
    }
}
