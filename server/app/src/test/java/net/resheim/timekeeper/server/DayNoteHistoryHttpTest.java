package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(DayNoteHistoryHttpTest.Profile.class)
class DayNoteHistoryHttpTest {
    @Inject ServerStore store;
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:day_note_history");}
    }
    private Response get(String token,String path){return given().header("Authorization","Bearer "+token).get("/api/v1"+path);}
    private Response put(String path,String etag,String json){return given().header("Authorization","Bearer test-a").header("If-Match",etag).contentType("application/json").body(json).put("/api/v1"+path);}
    private Response history(String day){return get("test-a","/day-notes/"+day+"/history");}
    @Test void independentChangesKeepEvidenceWithoutBodiesOrCredentials(){
        String day="2026-11-01",label="/day-labels/"+day,note="/day-notes/"+day+"/content";
        var empty=history(day);assertEquals(200,empty.statusCode());assertEquals(0,empty.jsonPath().getLong("historyVersion"));assertFalse(empty.jsonPath().getBoolean("legacyGap"));
        assertEquals(200,put(label,get("test-a",label).header("ETag"),"{\"text\":\"Planning\"}").statusCode());
        var saved=put(note,get("test-a",note).header("ETag"),"{\"body\":\"PRIVATE_BODY_MARKER\"}");assertEquals(200,saved.statusCode());
        assertEquals(200,put(note,saved.header("ETag"),"{\"body\":\"PRIVATE_BODY_MARKER\"}").statusCode());
        assertEquals(412,put(note,"\"stale\"","{\"body\":\"bad\"}").statusCode());
        assertEquals(2,history(day).jsonPath().getLong("historyVersion"));
        var cleared=put(note,saved.header("ETag"),"{\"body\":\"\"}");assertEquals(200,cleared.statusCode());
        assertEquals(204,given().header("Authorization","Bearer test-a").header("If-Match",cleared.header("ETag")).delete("/api/v1"+note).statusCode());
        var recreated=put(note,get("test-a",note).header("ETag"),"{\"body\":\"PRIVATE_RECREATED_BODY\"}");assertEquals(200,recreated.statusCode());
        var beforeLabelClear=history(day);assertEquals(List.of("CREATE","CREATE","CLEAR","DELETE","RECREATE"),beforeLabelClear.jsonPath().getList("items.operation"));
        assertEquals(200,put(label,get("test-a",label).header("ETag"),"{\"text\":\"\"}").statusCode());
        var result=history(day);assertEquals("private, no-store",result.header("Cache-Control"));assertEquals(6,result.jsonPath().getLong("historyVersion"));
        assertEquals("REST_LABEL",result.jsonPath().getString("items[0].source"));assertEquals("REST_NOTE",result.jsonPath().getString("items[1].source"));
        assertEquals("Planning",result.jsonPath().getString("items[3].previousLabel"));assertEquals("Planning",result.jsonPath().getString("items[3].newLabel"));
        assertEquals(1,result.jsonPath().getLong("items[3].labelVersion"));assertEquals(4,result.jsonPath().getLong("current.noteVersion"));
        assertEquals("",result.jsonPath().getString("current.label"));assertTrue(result.jsonPath().getBoolean("current.hasBody"));
        assertEquals("API_TOKEN",result.jsonPath().getString("items[0].client.authentication"));
        assertNotNull(result.jsonPath().getString("items[0].client.tokenId"));assertNotNull(result.jsonPath().getString("items[0].build.version"));
        assertFalse(result.asString().contains("PRIVATE_BODY"));assertFalse(result.asString().contains("PRIVATE_RECREATED_BODY"));assertFalse(result.asString().contains("test-a"));
        for(String secret:List.of("digest","legacyDigest","csrf","body","markdown","token"))assertFalse(result.jsonPath().getMap("items[0]").containsKey(secret));
    }
    @Test void rollbackAndStaleEntryWritesLeaveNoHistory(){
        String day="2026-11-02",entry="/api/v1/day-entries/"+day;
        var invalid=object().put("text","Must roll back").put("labelVersion",0).put("body","ø".repeat(100001)).put("noteVersion",0);
        assertEquals(422,given().header("Authorization","Bearer test-a").contentType("application/json").body(invalid.toString()).post(entry).statusCode());
        assertEquals(0,history(day).jsonPath().getLong("historyVersion"));assertEquals("",history(day).jsonPath().getString("current.label"));
        assertEquals(200,given().header("Authorization","Bearer test-a").contentType("application/json").body("{\"text\":\"Label\",\"labelVersion\":0,\"body\":\"Body\",\"noteVersion\":0}").post(entry).statusCode());
        assertEquals(412,given().header("Authorization","Bearer test-a").contentType("application/json").body("{\"text\":\"Stale\",\"labelVersion\":0,\"body\":\"Stale body\",\"noteVersion\":1}").post(entry).statusCode());
        var result=history(day);assertEquals(2,result.jsonPath().getLong("historyVersion"));assertEquals(List.of("REST_ENTRY","REST_ENTRY"),result.jsonPath().getList("items.source"));
        assertEquals("Label",result.jsonPath().getString("current.label"));
    }
    @Test void paginationIsOwnerDateAndRevisionBoundAndReadableWithReadOnlyTokens(){
        String day="2026-11-03",label="/day-labels/"+day,path="/day-notes/"+day+"/history";
        for(String text:List.of("One","Two","Three"))assertEquals(200,put(label,get("test-a",label).header("ETag"),object().put("text",text).toString()).statusCode());
        var first=get("test-read",path+"?limit=1");assertEquals(200,first.statusCode());assertEquals(1,first.jsonPath().getList("items").size());
        String cursor=first.jsonPath().getString("nextCursor");assertNotNull(cursor);
        var second=given().header("Authorization","Bearer test-read").queryParam("cursor",cursor).queryParam("limit",1).get("/api/v1"+path);
        assertEquals(200,second.statusCode());assertEquals(2,second.jsonPath().getLong("items[0].sequence"));
        assertEquals(400,given().header("Authorization","Bearer test-b").queryParam("cursor",cursor).get("/api/v1"+path).statusCode());
        assertEquals(400,given().header("Authorization","Bearer test-a").queryParam("cursor",cursor).get("/api/v1/day-notes/2026-11-04/history").statusCode());
        var foreign=get("test-b",path);assertTrue(foreign.jsonPath().getList("items").isEmpty());assertEquals("",foreign.jsonPath().getString("current.label"));
        assertEquals(403,given().header("Authorization","Bearer test-read").header("If-Match",get("test-a",label).header("ETag")).contentType("application/json").body("{\"text\":\"denied\"}").put("/api/v1"+label).statusCode());
        assertEquals(200,put(label,get("test-a",label).header("ETag"),"{\"text\":\"Four\"}").statusCode());
        var expired=given().header("Authorization","Bearer test-a").queryParam("cursor",cursor).get("/api/v1"+path);assertEquals(409,expired.statusCode());assertEquals("CURSOR_EXPIRED",expired.jsonPath().getString("code"));
    }
    @Test void webdavMetadataAndDeletionRecordTheRealSourceAndPreserveLabels(){
        String day="2026-11-05",label="/day-labels/"+day,path="/dav/journal/"+day+".md";
        put(label,get("test-a",label).header("ETag"),"{\"text\":\"Keep label\"}").then().statusCode(200);
        String auth="Basic "+Base64.getEncoder().encodeToString("client:test-a".getBytes(StandardCharsets.UTF_8));
        var created=given().header("Authorization",auth).header("If-None-Match","*").body("---\ncustom: one\n---\nPRIVATE_DAV_BODY\n").put(path);assertEquals(201,created.statusCode());
        var changed=given().header("Authorization",auth).header("If-Match",created.header("ETag")).body("---\ncustom: two\n---\nPRIVATE_DAV_BODY\n").put(path);assertEquals(204,changed.statusCode());
        var result=history(day);assertEquals("REPLACE",result.jsonPath().getString("items[2].operation"));assertEquals(List.of("markdown"),result.jsonPath().getList("items[2].changedFields"));
        assertEquals(204,given().header("Authorization",auth).header("If-Match",changed.header("ETag")).delete(path).statusCode());
        result=history(day);assertEquals("WEBDAV",result.jsonPath().getString("items[3].source"));assertEquals("DELETE",result.jsonPath().getString("items[3].operation"));
        assertEquals("Keep label",result.jsonPath().getString("items[3].newLabel"));assertFalse(result.asString().contains("PRIVATE_DAV_BODY"));
    }
    @Test void legacyStateReportsKnownModificationTimeWithoutInventingEvents(){
        var date=DayNotes.date("2026-11-06");String modified="2026-10-01T12:34:56Z";
        store.transaction("a",()->{
            var value=object().put("id",DayNotes.id(date).toString()).put("ownerId","a").put("date",date.toString()).put("version",7).put("body","PRIVATE_LEGACY_BODY").put("text","").put("modifiedAt",modified).put("labelIndependent",true);
            store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,7,?)","a",DayNotes.TYPE,DayNotes.id(date),value.toString());return null;
        });
        var result=history(date.toString());assertTrue(result.jsonPath().getBoolean("legacyGap"));assertTrue(result.jsonPath().getList("items").isEmpty());assertNull(result.jsonPath().get("recordingStartedAt"));
        assertEquals(modified,result.jsonPath().getString("current.noteModifiedAt"));assertFalse(result.asString().contains("PRIVATE_LEGACY_BODY"));
        String note="/day-notes/"+date+"/content";assertEquals(200,put(note,get("test-a",note).header("ETag"),"{\"body\":\"New body\"}").statusCode());
        result=history(date.toString());assertEquals(1,result.jsonPath().getList("items").size());assertTrue(result.jsonPath().getBoolean("legacyGap"));
        assertEquals(7,result.jsonPath().getLong("items[0].previousNoteVersion"));assertEquals(modified,result.jsonPath().getString("items[0].previousModifiedAt"));
    }
    @Test void retentionIsBoundedAndReportsTheMissingPrefix(){
        var date=DayNotes.date("2026-11-07");
        store.transaction("a",()->{
            var labels=new DayLabels(store);
            for(int i=1;i<=DayNoteHistory.RETAINED+2;i++)labels.save(date,etag(labels.get(date)),"Label "+i);
            return null;
        });
        var result=history(date.toString());assertEquals(200,result.statusCode());
        assertEquals(DayNoteHistory.RETAINED+2,result.jsonPath().getLong("historyVersion"));
        assertTrue(result.jsonPath().getBoolean("retentionTruncated"));assertFalse(result.jsonPath().getBoolean("legacyGap"));
        assertEquals(3,result.jsonPath().getLong("items[0].sequence"));
        assertEquals(DayNoteHistory.RETAINED,store.transaction("a",()->store.find(DayNoteHistory.TYPE,DayNotes.id(date)).orElseThrow().path("events").size()));
    }

}
