package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(CalendarSearchStateTest.Profile.class)
class CalendarSearchReplayTest {
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @TempDir Path temporary;
    private record Fixture(String owner,UUID subscription,String word,String body){@Override public String toString(){return "Calendar search fixture";}}
    private Fixture create(){
        String owner="calendar-index-"+UUID.randomUUID(),word="calneedle"+UUID.randomUUID().toString().substring(0,8);
        String body="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Dagsverk//Calendar search test//EN\r\nBEGIN:VEVENT\r\nUID:"+word+"\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260504T100000Z\r\nDURATION:PT1H\r\nSUMMARY:Sky planning\r\nDESCRIPTION:Spectrograph calibration "+word+"\r\nLOCATION:North observatory\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        UUID subscription=store.transaction(owner,()->{
            new VaultCollection(store,limits).initialize();new NotesSearchChanges(store).snapshot();UUID client=UUID.randomUUID();
            var value=object().put("id",client.toString()).put("ownerId",store.owner()).put("version",1).put("name","Search client").put("color","#305070");
            store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'CLIENT',?,1,?)",owner,client,value.toString());
            var input=object().put("name","Search calendar").put("clientId",client.toString()).put("visible",true).put("url","http://fixture.invalid/"+word);
            var sub=new CalendarSubscriptions(store).create(input,new CalendarDownload("http://fixture.invalid"));UUID id=uuid(sub.path("id"));
            sub.put("nextRefresh","2099-01-01T00:00:00Z");store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=?",sub.toString(),owner,CalendarSubscriptions.SUB,id);
            var feed=object().put("id",id.toString()).put("ownerId",owner).put("version",1).put("body",body);
            store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",owner,CalendarSubscriptions.FEED,id,1,feed.toString());return id;
        });return new Fixture(owner,subscription,word,body);
    }
    private NotesSearchSources.State state(Fixture fixture){return new CalendarSearchSources(store).state(fixture.owner());}
    private NotesLuceneIndex.Page waitFor(NotesSearchService service,NotesSearchSources.State state,String query)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);java.io.IOException last=null;
        do{try{return service.search(state,NotesSearchQuery.parse(query),50,null,500,CalendarSearchSources.BACKEND);}catch(java.io.IOException pending){last=pending;Thread.sleep(10);}}while(System.nanoTime()<deadline);
        throw new AssertionError("Calendar index did not become ready",last);
    }
    @Test void detailsArePrivateSearchableAndDoNotCreateSavedNotes()throws Exception{
        var fixture=create();var foreign=create();Path cache=temporary.resolve("private-calendar");UUID result;
        try(var service=new NotesSearchService(store,cache)){
            var hits=waitFor(service,state(fixture),"event:observatory "+fixture.word());assertEquals(1,hits.items().size());var hit=hits.items().getFirst();result=hit.id();assertEquals(CalendarSearchSources.SOURCE,hit.sourceType());assertNotNull(hit.calendar());
            assertTrue(service.sourceFences(state(fixture),CalendarSearchSources.BACKEND,EventNotes.TYPE).isEmpty());
            assertTrue(waitFor(service,state(fixture),foreign.word()).items().isEmpty());assertTrue(waitFor(service,state(fixture),"file:calendar-search "+fixture.word()).items().isEmpty());
            store.transaction(fixture.owner(),()->{
                var event=new CalendarSearchSources(store).currentCalendar(hit,new HashMap<>());assertEquals("North observatory",event.path("location").asText());assertEquals("Search client",event.path("clientName").asText());assertFalse(event.toString().contains("fixture.invalid"));assertTrue(store.list(EventNotes.TYPE).isEmpty());return null;
            });
            assertThrows(ApiFailure.class,()->store.transaction(foreign.owner(),()->new CalendarSearchSources(store).currentCalendar(hit,new HashMap<>())));
        }
        try(var restarted=new NotesSearchService(store,cache)){
            assertEquals(result,waitFor(restarted,state(fixture),fixture.word()).items().getFirst().id());assertNotNull(restarted.lookup(state(fixture),CalendarSearchSources.BACKEND,result).calendar());
        }
    }
    @Test void savedEventTextAndDetailsShareOneResultWithoutRewritingCanonicalBytes()throws Exception{
        var fixture=create();var saved=store.transaction(fixture.owner(),()->{
            var occurrence=new CalendarSubscriptions(store).events("2026-05-04","2026-05-05").path("items").get(0);var key=new EventNotes.Key(fixture.subscription(),occurrence.path("key").asText());var notes=new EventNotes(store);
            notes.save(key,etag(notes.get(key)),object().put("fromDate","2026-05-04").put("toDate","2026-05-05").put("body","Private commissioning checklist."));return notes.document(key);
        });
        try(var service=new NotesSearchService(store,temporary.resolve("merged-calendar"))){
            var hits=waitFor(service,state(fixture),"event:observatory checklist");assertEquals(1,hits.items().size());var hit=hits.items().getFirst();assertEquals(EventNotes.TYPE,hit.sourceType());assertEquals(hit.id().toString(),hit.calendar().path("event").path("noteEntryId").asText());
            assertEquals(1,waitFor(service,state(fixture),"event:"+fixture.word()).items().size());
            var fences=service.sourceFences(state(fixture),CalendarSearchSources.BACKEND,EventNotes.TYPE);assertEquals(1,fences.size());
            var noteState=new NotesSearchSources(store).state(fixture.owner());
            assertTrue(service.search(noteState,NotesSearchQuery.parse("checklist"),50,null,1000,NotesSearchService.NOTES,fences).items().isEmpty());
            store.transaction(fixture.owner(),()->{
                var source=store.find(EventNotes.TYPE,hit.sourceId()).orElseThrow();assertArrayEquals(saved,EventNotes.document(source));assertNotNull(new NotesSearchSources(store).current(hit));return null;
            });
        }
    }
    @Test void feedChangesAndVisibilityInvalidateOnlyTheCalendarSnapshot()throws Exception{
        var fixture=create();var notesBefore=new NotesSearchSources(store).state(fixture.owner());var before=state(fixture);
        try(var service=new NotesSearchService(store,temporary.resolve("refresh-calendar"))){
            var old=waitFor(service,before,fixture.word()).items().getFirst();
            store.transaction(fixture.owner(),()->{
                var value=(com.fasterxml.jackson.databind.node.ObjectNode)store.find(CalendarSubscriptions.FEED,fixture.subscription()).orElseThrow();long version=value.path("version").asLong()+1;
                value.put("version",version).put("body",fixture.body().replace(fixture.word(),"updatedcalendarword"));
                store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=?",version,value.toString(),store.owner(),CalendarSubscriptions.FEED,fixture.subscription());new CalendarSearchState(store).changed();return null;
            });
            var changed=state(fixture);assertNotEquals(before.changes(),changed.changes());assertEquals(notesBefore,new NotesSearchSources(store).state(fixture.owner()));
            assertEquals(1,waitFor(service,changed,"updatedcalendarword").items().size());assertTrue(waitFor(service,changed,fixture.word()).items().isEmpty());
            assertThrows(ApiFailure.class,()->store.transaction(fixture.owner(),()->new CalendarSearchSources(store).currentCalendar(old,new HashMap<>())));
            store.transaction(fixture.owner(),()->{
                var calendars=new CalendarSubscriptions(store);var sub=calendars.required(fixture.subscription());var input=object().put("name",sub.path("name").asText()).put("clientId",sub.path("clientId").asText()).put("visible",false);
                calendars.edit(fixture.subscription(),etag(calendars.visible(sub)),input,new CalendarDownload("http://fixture.invalid"));return null;
            });
            assertTrue(waitFor(service,state(fixture),"updatedcalendarword").items().isEmpty());
        }
    }
}
