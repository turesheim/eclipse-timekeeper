package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import static io.restassured.RestAssured.given;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(CalendarSearchStateTest.Profile.class)
class CalendarSearchStateTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("calendarsearchstate",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:calendar_search_state_test"));}
    }
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    private UUID client(){
        UUID id=UUID.randomUUID();var value=object().put("id",id.toString()).put("ownerId",store.owner()).put("version",1).put("name","Calendar search client").put("color","#305070");
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'CLIENT',?,1,?)",store.owner(),id,value.toString());return id;
    }
    @Test void calendarGenerationIsOwnedTransactionalAndIndependentOfOrdinaryNotes(){
        String owner="calendar-search-notes";
        long changed=store.transaction(owner,()->{
            var state=new CalendarSearchState(store);assertEquals(0,state.generation());var vault=new VaultCollection(store,limits);vault.initialize();
            vault.put(new VaultPath("ordinary.md"),null,true,"Ordinary text".getBytes(StandardCharsets.UTF_8));assertEquals(0,state.generation());
            var key=new EventNotes.Key(UUID.randomUUID(),"23".repeat(32));var metadata=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event());metadata.set("event",object().put("uid","calendar-search-note").put("recurrenceId","single").put("title","Event title"));
            var events=new EventNotes(store);events.restore(key.filename(),("---\ntimekeeper-event: "+canonical(metadata)+"\n---\nEvent body").getBytes(StandardCharsets.UTF_8));
            long saved=state.generation();assertTrue(saved>0);events.save(key,etag(events.get(key)),object().put("body","Changed event body"));assertTrue(state.generation()>saved);
            long edited=state.generation();events.delete(key,etag(events.get(key)));assertTrue(state.generation()>edited);return state.generation();
        });
        assertEquals(0,store.transaction("calendar-search-other",()->new CalendarSearchState(store).generation()));
        assertThrows(IllegalStateException.class,()->store.transaction(owner,()->{new CalendarSearchState(store).changed();throw new IllegalStateException("rollback");}));
        assertEquals(changed,store.transaction(owner,()->new CalendarSearchState(store).generation()));
    }
    @Test void pollingAndUnchangedFeedsDoNotInvalidateCalendarSearch(){
        assertEquals(200,given().header("Authorization","Bearer test-a").get("/api/v1/me").statusCode());
        store.transaction("a",()->{
            var calendars=new CalendarSubscriptions(store);var state=new CalendarSearchState(store);UUID client=client();var policy=new CalendarDownload("http://fixture.invalid");
            String original="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n";
            for(int attempt=0;attempt<3;attempt++){
                long before=state.generation();var input=object().put("name","Calendar "+attempt).put("clientId",client.toString()).put("visible",true).put("url","http://fixture.invalid/feed-"+attempt);
                var subscription=calendars.create(input,policy);UUID id=uuid(subscription.path("id"));assertTrue(state.generation()>before);
                var feed=object().put("id",id.toString()).put("ownerId",store.owner()).put("version",1).put("body",original);
                store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),CalendarSubscriptions.FEED,id,1,feed.toString());
                long created=state.generation();var claim=calendars.claim(id,false);assertNotNull(claim);assertEquals(created,state.generation());
                calendars.finish(claim,new CalendarDownload.Result(attempt==0,attempt==2?original+"\r\n":original,"changed-validator",null),null);
                if(attempt==2)assertTrue(state.generation()>created);else assertEquals(created,state.generation());
                long refreshed=state.generation();var edit=input.deepCopy().put("visible",false);calendars.edit(id,etag(calendars.visible(calendars.required(id))),edit,policy);assertTrue(state.generation()>refreshed);
                long hidden=state.generation();calendars.remove(id,etag(calendars.visible(calendars.required(id))));assertTrue(state.generation()>hidden);
            }
            return null;
        });
    }
}
