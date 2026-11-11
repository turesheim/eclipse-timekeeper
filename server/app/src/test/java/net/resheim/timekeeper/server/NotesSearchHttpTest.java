package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(NotesSearchHttpTest.Profile.class)
class NotesSearchHttpTest {
    @Test void bookmarkRankingReplaysAndExpiresCursorsWithoutChangingCanonicalBytes()throws Exception{
        String word="bookmarkrankingneedle",first="search-http/A-bookmark-ranking.md",last="search-http/Z-bookmark-ranking.md";
        create("a",first,word);create("a",last,word);create("b","search-http/foreign-bookmark-ranking.md",word);
        var initial=page(word,1,null);assertEquals(first,initial.path("items").get(0).path("entry").path("path").asText());assertTrue(initial.hasNonNull("nextCursor"));
        var before=store.transaction("a",()->new VaultCollection(store,limits).get(new VaultPath(last)).deepCopy());
        store.transaction("a",()->{var bookmarks=new NoteBookmarks(store);var ref=VaultSources.fromEntry(before);bookmarks.save(ref,bookmarks.tag(ref),true);return null;});
        assertEquals(412,request("test-a",word,1,initial.path("nextCursor").asText()).statusCode());
        var marked=page(word,10,null);assertEquals(2,marked.path("items").size());assertEquals(last,marked.path("items").get(0).path("entry").path("path").asText());assertTrue(marked.path("items").get(0).path("entry").path("bookmarked").asBoolean());
        store.transaction("a",()->{
            var vault=new VaultCollection(store,limits);var entry=vault.get(new VaultPath(last));
            assertEquals(before.path("contentTag"),entry.path("contentTag"));assertEquals(before.path("modifiedAt"),entry.path("modifiedAt"));assertArrayEquals(word.getBytes(StandardCharsets.UTF_8),vault.read(entry));
            var bookmarks=new NoteBookmarks(store);var ref=VaultSources.fromEntry(entry);bookmarks.save(ref,bookmarks.tag(ref),false);return null;
        });
        assertEquals(first,page(word,10,null).path("items").get(0).path("entry").path("path").asText());
    }
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("searchhttp",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:notes_search_http_test"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject ContractInputs inputs;
    private void create(String owner,String path,String body){store.transaction(owner,()->{var vault=new VaultCollection(store,limits);vault.initialize();try{vault.get(new VaultPath("search-http"));}catch(ApiFailure absent){if(absent.status!=404)throw absent;vault.mkdir(new VaultPath("search-http"));}vault.put(new VaultPath(path),null,true,body.getBytes(StandardCharsets.UTF_8));return null;});}
    private io.restassured.response.Response request(String token,String query,int count,String cursor){
        var request=given().header("Authorization","Bearer "+token).queryParam("q",query).queryParam("limit",count);if(cursor!=null)request.queryParam("cursor",cursor);
        return request.get("/api/v1/notes/search");
    }
    private JsonNode page(String query,int limit,String cursor)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do{var reply=request("test-a",query,limit,cursor);if(reply.statusCode()==200){assertEquals("private, no-store",reply.header("Cache-Control"));var result=parse(reply.asString());inputs.validate("NotesSearchPage",result);return result;}
            assertEquals(503,reply.statusCode(),reply.asString());Thread.sleep(20);
        }while(System.nanoTime()<deadline);throw new AssertionError("Notes search did not become ready");
    }
    private io.restassured.response.Response calendarDetail(String token,String id)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do{var reply=given().header("Authorization","Bearer "+token).get("/api/v1/notes/search/calendar/"+id);if(reply.statusCode()!=503)return reply;Thread.sleep(20);}while(System.nanoTime()<deadline);
        throw new AssertionError("Calendar search lookup did not become ready");
    }
    @Test void calendarDetailsAndSavedNotesPageTogetherAndRecheckOwnedCurrentSources()throws Exception{
        String word="combinedcalendarneedle";for(int i=0;i<5;i++)create("a","search-http/combined-"+i+".md",word+" ordinary note");
        UUID subscription=store.transaction("a",()->{
            UUID client=UUID.randomUUID();var value=object().put("id",client.toString()).put("ownerId",store.owner()).put("version",1).put("name","HTTP search client").put("color","#305070");
            store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'CLIENT',?,1,?)",store.owner(),client,value.toString());
            var input=object().put("name","HTTP search calendar").put("clientId",client.toString()).put("visible",true).put("url","http://fixture.invalid/calendar-search");
            var sub=new CalendarSubscriptions(store).create(input,new CalendarDownload("http://fixture.invalid"));UUID id=uuid(sub.path("id"));sub.put("nextRefresh","2099-01-01T00:00:00Z");
            store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=?",sub.toString(),store.owner(),CalendarSubscriptions.SUB,id);
            var body=new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Dagsverk//Search HTTP//EN\r\n");
            for(int i=0;i<13;i++)body.append("BEGIN:VEVENT\r\nUID:combined-").append(i).append("\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20260504T100000Z\r\nDURATION:PT1H\r\nSUMMARY:HTTP sky ").append(i).append("\r\nDESCRIPTION:").append(word).append(" calibration\r\nLOCATION:HTTP observatory\r\nEND:VEVENT\r\n");
            body.append("END:VCALENDAR\r\n");var feed=object().put("id",id.toString()).put("ownerId",store.owner()).put("version",1).put("body",body.toString());
            store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),CalendarSubscriptions.FEED,id,1,feed.toString());
            var event=new CalendarSubscriptions(store).events("2026-05-04","2026-05-05").path("items").get(0);var key=new EventNotes.Key(id,event.path("key").asText());var notes=new EventNotes(store);
            notes.save(key,etag(notes.get(key)),object().put("fromDate","2026-05-04").put("toDate","2026-05-05").put("body","Private saved checklist."));return id;
        });
        var first=page(word,4,null);JsonNode current=first;var seen=new HashSet<String>();int notes=0,events=0,linked=0;String resultId=null;
        do{
            for(var hit:current.path("items")){
                boolean calendar=hit.has("calendarEvent");String id=calendar?hit.path("calendarEvent").path("id").asText():hit.path("entry").path("id").asText();assertTrue(seen.add(id));
                if(calendar){events++;resultId=id;assertEquals("HTTP observatory",hit.path("calendarEvent").path("location").asText());assertFalse(hit.toString().contains("fixture.invalid"));if(hit.has("entry")){linked++;assertEquals(id,hit.path("entry").path("id").asText());}}
                else notes++;
            }
            current=current.has("nextCursor")?page(word,4,current.path("nextCursor").asText()):null;
        }while(current!=null);
        assertEquals(5,notes);assertEquals(13,events);assertEquals(1,linked);assertEquals(18,seen.size());
        var detail=given().header("Authorization","Bearer test-a").get("/api/v1/notes/search/calendar/"+resultId);assertEquals(200,detail.statusCode(),detail.asString());inputs.validate("NotesSearchHit",parse(detail.asString()));
        assertEquals(404,calendarDetail("test-b",resultId).statusCode());
        assertEquals(13,page("event:"+word,200,null).path("items").size());assertEquals(1,page("event:observatory checklist",200,null).path("items").size());
        assertTrue(page("file:calendar-search "+word,200,null).path("items").isEmpty());
        store.transaction("a",()->{var calendars=new CalendarSubscriptions(store);var sub=calendars.required(subscription);calendars.edit(subscription,etag(calendars.visible(sub)),object().put("name","HTTP search calendar").put("clientId",sub.path("clientId").asText()).put("visible",false),new CalendarDownload("http://fixture.invalid"));return null;});
        assertEquals(412,request("test-a",word,4,first.path("nextCursor").asText()).statusCode());assertEquals(5,page(word,200,null).path("items").size());
        var hidden=given().header("Authorization","Bearer test-a").get("/api/v1/notes/search/calendar/"+resultId);assertTrue(Set.of(404,503).contains(hidden.statusCode()));
        assertEquals(1,store.readSnapshot("a",()->store.list(EventNotes.TYPE).stream().filter(row->row.path("subscriptionId").asText().equals(subscription.toString())).count()).intValue());
    }
    @Test void anExplicitTitleWithAnUnchangedPathRefreshesSearchMetadata()throws Exception{
        String title="chosenfilename";create("a","search-http/"+title+".md","# Different imported heading\n");
        assertEquals("Different imported heading",page(title,200,null).path("items").get(0).path("entry").path("title").asText());
        store.transaction("a",()->{var vault=new VaultCollection(store,limits);var entry=vault.get(new VaultPath("search-http/"+title+".md"));vault.renameTitle(entry,title,vault.metadataTag(entry));return null;});
        assertEquals(title,page(title,200,null).path("items").get(0).path("entry").path("title").asText());
    }
    @Test void httpSearchPagesArePrivateRankedPlainTextAndExpireAfterCommittedChanges()throws Exception{
        String word="httpneedle";for(int i=0;i<5;i++)create("a","search-http/"+i+".md","# Title "+i+"\n\n😀 "+word+" owner text");
        create("b","search-http/foreign.md",word+" private foreign text");
        JsonNode first=page(word,2,null);assertEquals(2,first.path("items").size());assertTrue(first.has("nextCursor"));
        var seen=new HashSet<String>();JsonNode current=first;
        do{
            for(var hit:current.path("items")){
                assertTrue(seen.add(hit.path("entry").path("id").asText()));assertFalse(hit.toString().contains("foreign"));var excerpt=hit.path("excerpt");String text=excerpt.path("text").asText();assertTrue(text.length()<=320);
                for(var range:excerpt.path("highlights")){int start=range.path("start").asInt(),end=range.path("end").asInt();assertEquals(word,text.substring(start,end));}
                assertFalse(excerpt.path("highlights").isEmpty());
            }
            current=current.has("nextCursor")?page(word,2,current.path("nextCursor").asText()):null;
        }while(current!=null);
        assertEquals(5,seen.size());String cursor=first.path("nextCursor").asText();
        assertEquals(412,request("test-b",word,2,cursor).statusCode());assertEquals(412,request("test-a","different",2,cursor).statusCode());
        assertEquals(412,request("test-a",word,2,cursor+"x").statusCode());
        create("a","search-http/new.md",word+" newly committed");assertEquals(412,request("test-a",word,2,cursor).statusCode());assertEquals(6,page(word,200,null).path("items").size());
    }
    @Test void statusRebuildAndInputValidationUseNormalAuthenticationAndPermissions()throws Exception{
        assertEquals(401,given().queryParam("q","needle").get("/api/v1/notes/search").statusCode());
        assertEquals(422,request("test-a"," ",50,null).statusCode());assertEquals(422,request("test-a","x".repeat(513),50,null).statusCode());
        assertEquals(422,request("test-a","needle",201,null).statusCode());
        assertEquals(400,given().header("Authorization","Bearer test-a").queryParam("q","needle").queryParam("q","other").get("/api/v1/notes/search").statusCode());
        assertEquals(403,given().header("Authorization","Bearer test-read").post("/api/v1/notes/search/rebuild").statusCode());
        var status=given().header("Authorization","Bearer test-a").get("/api/v1/notes/search/status");assertEquals(200,status.statusCode());inputs.validate("NotesSearchStatus",parse(status.asString()));
        assertEquals("private, no-store",status.header("Cache-Control"));
        var rebuild=given().header("Authorization","Bearer test-a").post("/api/v1/notes/search/rebuild");assertEquals(202,rebuild.statusCode());assertEquals("private, no-store",rebuild.header("Cache-Control"));
    }
}
