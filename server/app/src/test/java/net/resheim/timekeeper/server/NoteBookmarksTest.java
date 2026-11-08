package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(NoteBookmarksTest.Profile.class)
class NoteBookmarksTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("bookmarks","jdbc:h2:mem:note_bookmarks_test");}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject ContractInputs inputs;
    private io.restassured.response.Response read(String token,String type,String key){return given().header("Authorization","Bearer "+token).queryParam("sourceType",type).queryParam("sourceKey",key).get("/api/v1/notes/bookmark");}
    private io.restassured.response.Response save(String token,String type,String key,String tag,Object body){
        var request=given().header("Authorization","Bearer "+token).queryParam("sourceType",type).queryParam("sourceKey",key).contentType("application/json");if(tag!=null)request.header("If-Match",tag);return request.body(body).put("/api/v1/notes/bookmark");
    }
    @Test void dateBookmarksArePrivateAndCannotBypassConcurrencyOrReadOnlyTokens(){
        String date="2026-11-08",type=DayNotes.TYPE;var first=read("test-a",type,date);assertEquals(200,first.statusCode());inputs.validate("NoteBookmarkState",parse(first.asString()));assertFalse(first.jsonPath().getBoolean("bookmarked"));String initial=first.header("ETag");
        assertEquals(428,save("test-a",type,date,null,"{\"bookmarked\":true}").statusCode());
        assertEquals(422,save("test-a",type,date,initial,"{\"bookmarked\":\"true\"}").statusCode());
        assertEquals(403,save("test-read",type,date,initial,"{\"bookmarked\":true}").statusCode());
        var marked=save("test-a",type,date,initial,"{\"bookmarked\":true}");assertEquals(200,marked.statusCode());assertTrue(marked.jsonPath().getBoolean("bookmarked"));
        assertFalse(read("test-b",type,date).jsonPath().getBoolean("bookmarked"));assertTrue(read("test-read",type,date).jsonPath().getBoolean("bookmarked"));
        var repeated=save("test-a",type,date,marked.header("ETag"),"{\"bookmarked\":true}");assertEquals(200,repeated.statusCode());assertEquals(marked.header("ETag"),repeated.header("ETag"));
        assertEquals(412,save("test-a",type,date,initial,"{\"bookmarked\":false}").statusCode());
        var removed=save("test-a",type,date,marked.header("ETag"),"{\"bookmarked\":false}");assertEquals(200,removed.statusCode());assertNotEquals(initial,removed.header("ETag"));
        assertEquals(412,save("test-a",type,date,initial,"{\"bookmarked\":true}").statusCode());
        assertEquals(422,read("test-a",type,"2026-02-30").statusCode());assertEquals(422,read("test-a","PROJECT",UUID.randomUUID().toString()).statusCode());
        assertEquals(400,given().header("Authorization","Bearer test-a").queryParam("sourceType",type).queryParam("sourceKey",date).queryParam("extra","x").get("/api/v1/notes/bookmark").statusCode());
    }
    @Test void emptyDayAppearsInCalendarWithoutCreatingContentAndSurvivesLaterBodyEdits(){
        store.transaction("bookmark-empty",()->{
            var marks=new NoteBookmarks(store);var ref=marks.reference(DayNotes.TYPE,"2026-11-09");var notes=new DayNotes(store);var day=DayNotes.date(ref.key());var before=notes.getContent(day);String tag=etag(before);
            marks.save(ref,marks.tag(ref),true);assertEquals(before,notes.getContent(day));assertTrue(store.find(DayNotes.TYPE,ref.id()).isEmpty());assertTrue(store.list(DayNoteHistory.TYPE).isEmpty());
            var items=notes.list(day,day).path("items");assertEquals(1,items.size());assertTrue(items.get(0).path("bookmarked").asBoolean());assertFalse(items.get(0).path("hasBody").asBoolean());
            notes.saveContent(day,tag,object().put("body","Unrelated body draft"));assertTrue(marks.day(day));assertEquals("Unrelated body draft",notes.getContent(day).path("body").asText());return null;
        });
    }
    @Test void fileBookmarkFollowsRenameAndTrashWithoutChangingBytesDatesOrBodyValidators(){
        store.transaction("bookmark-file",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();var entry=vault.put(new VaultPath("Before.md"),null,true,"---\r\ncustom: exact\r\n---\r\nBody  \r\n".getBytes(StandardCharsets.UTF_8));
            var marks=new NoteBookmarks(store);var ref=marks.reference(VaultContent.TYPE,entry.path("sourceKey").asText());String bodyTag=vault.tag(entry),metadataTag=vault.metadataTag(entry),snapshot=vault.generation();var before=entry.deepCopy();long history=store.list(DayNoteHistory.TYPE).size();
            marks.save(ref,marks.tag(ref),true);var current=vault.get(VaultCollection.id(entry));assertEquals(before.toString(),current.toString());assertEquals(bodyTag,vault.tag(current));assertNotEquals(metadataTag,vault.metadataTag(current));assertNotEquals(snapshot,vault.generation());assertEquals(history,store.list(DayNoteHistory.TYPE).size());assertTrue(vault.metadata(current).path("bookmarked").asBoolean());
            var moved=vault.move(current,new VaultPath("After.md"),bodyTag);assertTrue(vault.metadata(moved).path("bookmarked").asBoolean());
            var saved=vault.put(new VaultPath("After.md"),bodyTag,false,"New body".getBytes(StandardCharsets.UTF_8));assertTrue(vault.metadata(saved).path("bookmarked").asBoolean());vault.delete(saved,vault.tag(saved));assertEquals(404,assertThrows(ApiFailure.class,()->marks.reference(ref.type(),ref.key())).status);
            var restored=vault.restore(vault.get(VaultCollection.id(saved)),vault.tag(vault.get(VaultCollection.id(saved))));assertTrue(vault.metadata(restored).path("bookmarked").asBoolean());return null;
        });
    }
    @Test void ordinaryFileEndpointsCannotReadAnotherOwnersBookmark(){
        var entry=given().header("Authorization","Bearer test-a").queryParam("path","Private bookmark.md").header("If-None-Match","*").contentType("application/octet-stream").body("Private".getBytes(StandardCharsets.UTF_8)).put("/api/v1/notes/files");assertEquals(201,entry.statusCode());String key=entry.jsonPath().getString("sourceKey");
        var read=read("test-a",VaultContent.TYPE,key);assertEquals(200,read.statusCode());assertEquals(404,read("test-b",VaultContent.TYPE,key).statusCode());assertEquals(404,save("test-b",VaultContent.TYPE,key,read.header("ETag"),"{\"bookmarked\":true}").statusCode());
    }
}
