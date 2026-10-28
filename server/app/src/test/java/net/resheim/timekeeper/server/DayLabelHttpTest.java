package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;

@QuarkusTest @TestProfile(DayLabelHttpTest.Profile.class)
class DayLabelHttpTest {
    @Inject ServerStore store;
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:independent_day_labels");}}
    private Response get(String owner,String path){return given().header("Authorization","Bearer "+owner).get("/api/v1"+path);}
    private Response put(String owner,String path,String tag,String json){return given().header("Authorization","Bearer "+owner).header("If-Match",tag).contentType("application/json").body(json).put("/api/v1"+path);}
    private Response edit(String date,String json){return given().header("Authorization","Bearer test-a").contentType("application/json").body(json).post("/api/v1/day-entries/"+date);}
    @Test void labelsAndNotesHaveIndependentVersionsAndDeletion(){
        String day="2026-10-01",label="/day-labels/"+day,note="/day-notes/"+day+"/content";
        var emptyNote=get("test-a",note);var initialLabel=get("test-a",label);
        var named=put("test-a",label,initialLabel.header("ETag"),"{\"text\":\"Planning\"}");assertEquals(200,named.statusCode());assertEquals("private, no-store",named.header("Cache-Control"));
        assertEquals("private, no-store",get("test-a","/day-entries/"+day).header("Cache-Control"));
        assertEquals(emptyNote.header("ETag"),get("test-a",note).header("ETag"));assertEquals(0,get("test-a",note).jsonPath().getLong("version"));
        var content=put("test-a",note,emptyNote.header("ETag"),"{\"body\":\"# Body\\n\"}");assertEquals(200,content.statusCode());
        assertEquals(named.header("ETag"),get("test-a",label).header("ETag"));assertFalse(content.jsonPath().getMap("").containsKey("text"));
        var cleared=put("test-a",note,content.header("ETag"),"{\"body\":\"\"}");assertEquals(200,cleared.statusCode());assertEquals("Planning",get("test-a",label).jsonPath().getString("text"));
        var removed=given().header("Authorization","Bearer test-a").header("If-Match",cleared.header("ETag")).delete("/api/v1"+note);assertEquals(204,removed.statusCode());
        assertTrue(get("test-a",note).jsonPath().getBoolean("deleted"));assertEquals(named.header("ETag"),get("test-a",label).header("ETag"));
        assertEquals("Planning",get("test-a","/day-notes/"+day).jsonPath().getString("text"));
        var blank=put("test-a",label,named.header("ETag"),"{\"text\":\"\"}");assertEquals(200,blank.statusCode());
        assertTrue(get("test-a",note).jsonPath().getBoolean("deleted"));assertEquals(412,put("test-a",label,named.header("ETag"),"{\"text\":\"stale\"}").statusCode());
    }
    @Test void bodyOnlyChangesNeverMaterializeOrVersionAnUnchangedLabel(){
        String day="2026-10-02",label="/day-labels/"+day,note="/day-notes/"+day+"/content";String original=get("test-a",label).header("ETag");
        var first=put("test-a",note,get("test-a",note).header("ETag"),"{\"body\":\"First\"}");assertEquals(200,first.statusCode());
        assertEquals(200,put("test-a",note,first.header("ETag"),"{\"body\":\"Second\"}").statusCode());
        assertEquals(original,get("test-a",label).header("ETag"));assertEquals(0,get("test-a",label).jsonPath().getLong("version"));
    }
    @Test void entryCommandChecksOnlyChangedResourcesAndRollsBackBothOnConflict(){
        String day="2026-10-03";assertEquals(200,edit(day,"{\"text\":\"Label\",\"labelVersion\":0}").statusCode());
        var body=edit(day,"{\"body\":\"Note\",\"noteVersion\":0}");assertEquals(200,body.statusCode());
        assertEquals(412,edit(day,"{\"text\":\"Should roll back\",\"labelVersion\":1,\"body\":\"stale\",\"noteVersion\":0}").statusCode());
        assertEquals("Label",get("test-a","/day-labels/"+day).jsonPath().getString("text"));
        assertEquals(200,edit(day,"{\"text\":\"Updated label\",\"labelVersion\":1}").statusCode());
        assertEquals(200,edit(day,"{\"body\":\"Updated body\",\"noteVersion\":1}").statusCode());
        assertEquals("Updated label",get("test-a","/day-entries/"+day).jsonPath().getString("text"));
        assertEquals(422,edit(day,object().put("text","Must roll back").put("labelVersion",2).put("body","ø".repeat(100001)).put("noteVersion",2).toString()).statusCode());
        assertEquals("Updated label",get("test-a","/day-labels/"+day).jsonPath().getString("text"));
    }
    @Test void markdownReplacementAndDeletionNeverChangeTheCalendarLabel(){
        String day="2026-10-04",label="/day-labels/"+day;var named=put("test-a",label,get("test-a",label).header("ETag"),"{\"text\":\"Keep me\"}");
        String auth="Basic "+Base64.getEncoder().encodeToString("client:test-a".getBytes(StandardCharsets.UTF_8)),path="/dav/journal/"+day+".md",source="---\ntimekeeper-label: Opaque legacy metadata\ncustom: retain\n---\n# Markdown\n";
        var created=given().header("Authorization",auth).header("If-None-Match","*").body(source).put(path);assertEquals(201,created.statusCode());
        assertEquals("Keep me",get("test-a",label).jsonPath().getString("text"));assertEquals(named.header("ETag"),get("test-a",label).header("ETag"));
        assertEquals(source,given().header("Authorization",auth).get(path).asString());
        assertEquals(204,given().header("Authorization",auth).header("If-Match",created.header("ETag")).delete(path).statusCode());
        assertEquals("Keep me",get("test-a",label).jsonPath().getString("text"));assertEquals(404,given().header("Authorization",auth).get(path).statusCode());
    }
    @Test void migrationKeepsLabelsAndMarkdownWhileExplicitLabelClearsCannotResurrect(){
        var day=DayNotes.date("2026-10-05");String body="# Existing body\n";
        store.transaction("a",()->{
            var legacy=object().put("id",DayNotes.id(day).toString()).put("ownerId","a").put("date",day.toString()).put("version",7).put("text","Legacy label").put("body",body);
            store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,7,?)","a",DayNotes.TYPE,DayNotes.id(day),legacy.toString());
            var labels=new DayLabels(store);labels.migrate();var notes=new DayNotes(store);byte[] original=notes.document(day);
            assertEquals("Legacy label",labels.get(day).path("text").asText());assertEquals(7,notes.getContent(day).path("version").asLong());
            labels.save(day,etag(labels.get(day)),"");labels.migrate();assertEquals("",labels.get(day).path("text").asText());
            assertArrayEquals(original,notes.document(day));assertEquals(body,notes.getContent(day).path("body").asText());return null;
        });
    }
    @Test void migrationPreservesLegacyDeletionInterpretationForLabelOnlyRecords(){
        var date=DayNotes.date("2026-10-08");
        store.transaction("a",()->{
            var legacy=object().put("id",DayNotes.id(date).toString()).put("ownerId","a").put("date",date.toString()).put("version",4).put("text","Keep legacy label").put("body","");
            store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,4,?)","a",DayNotes.TYPE,DayNotes.id(date),legacy.toString());
            new JournalState(store).insert(JournalState.Kind.JOURNAL_DELETE,DayNotes.id(date),object().put("date",date.toString()).put("noteVersion",4));
            var notes=new DayNotes(store);assertFalse(notes.getContent(date).path("deleted").asBoolean());
            new DayLabels(store).migrate();assertFalse(notes.getContent(date).path("deleted").asBoolean());assertNotNull(notes.document(date));
            assertEquals("Keep legacy label",new DayLabels(store).get(date).path("text").asText());return null;
        });
    }
    @Test void bodyEditsPreserveOpaqueLegacyMetadataBytesWithoutInterpretingALabel(){
        var date=DayNotes.date("2026-10-07");String header="\ufeff---\r\ntimekeeper-label: [opaque, metadata]\r\ncustom: ' exact spacing '  \r\n---\r\n";
        store.transaction("a",()->{
            var notes=new DayNotes(store);var labels=new DayLabels(store);labels.save(date,etag(labels.get(date)),"Independent label");
            notes.saveDocument(date,etag(notes.getContent(date)),(header+"Old body\r\n").getBytes(StandardCharsets.UTF_8));
            notes.saveContent(date,etag(notes.getContent(date)),object().put("body","New body\n"));
            assertEquals(header+"New body\n",new String(notes.document(date),StandardCharsets.UTF_8));
            assertEquals("Independent label",labels.get(date).path("text").asText());return null;
        });
    }
    @Test void readOnlyAndOtherOwnersCannotChangeOrSeePrivateLabels(){
        String day="2026-10-06",path="/day-labels/"+day;var saved=put("test-a",path,get("test-a",path).header("ETag"),"{\"text\":\"Private label\"}");assertEquals(200,saved.statusCode());
        assertEquals("",get("test-b",path).jsonPath().getString("text"));assertEquals(403,put("test-read",path,saved.header("ETag"),"{\"text\":\"Denied\"}").statusCode());
        assertEquals("Private label",get("test-read",path).jsonPath().getString("text"));
    }
}
