package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultImportOverwriteTest.Profile.class)
class VaultImportOverwriteTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){var result=new HashMap<>(ArchiveTestDatabase.configuration("notesoverwrite","jdbc:h2:mem:notes_import_overwrite_test"));result.put("timekeeper.notes.max-total-bytes","4096");result.put("timekeeper.notes.max-file-bytes","4096");result.put("timekeeper.notes.max-markdown-bytes","4096");return result;}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject ContractInputs inputs;
    private static final String CREATED="2020-01-02T03:04:05Z",MODIFIED="2019-02-03T04:05:06.123456789Z";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private com.fasterxml.jackson.databind.JsonNode create(String path){
        var reply=given().header("Authorization","Bearer test-a").header("If-None-Match","*").contentType("application/octet-stream").queryParam("path",path).body(bytes("Original\r\n")).put("/api/v1/notes/files");assertEquals(201,reply.statusCode());return parse(reply.asString());
    }
    private io.restassured.response.Response overwrite(com.fasterxml.jackson.databind.JsonNode entry,String token,String tag,String body){
        var request=given().header("Authorization","Bearer "+token).contentType("application/octet-stream");if(tag!=null)request.header("If-Match",tag);
        return request.body(bytes(body)).put("/api/v1/notes/entries/"+entry.path("id").asText()+"/import");
    }
    private String content(com.fasterxml.jackson.databind.JsonNode entry){return given().header("Authorization","Bearer test-a").get("/api/v1/notes/entries/"+entry.path("id").asText()+"/content").asString();}
    @Test void overwritePreservesIdentityAndAdditionDateAndAtomicallyImportsOriginalDates(){
        var entry=create("overwrite-dates.md");assertEquals("ADDED",entry.path("createdAtSource").asText());
        var replaced=overwrite(entry,"test-a",entry.path("entryEtag").asText(),"New content\r\n");assertEquals(200,replaced.statusCode());var next=parse(replaced.asString());
        assertEquals(entry.path("id"),next.path("id"));assertEquals(entry.path("createdAt"),next.path("createdAt"));assertEquals("ADDED",next.path("createdAtSource").asText());
        var dated=given().header("Authorization","Bearer test-a").header("If-Match",next.path("entryEtag").asText()).contentType("application/octet-stream").queryParam("createdAt",CREATED).queryParam("modifiedAt",MODIFIED).body(bytes("Imported exact\r\n")).put("/api/v1/notes/entries/"+entry.path("id").asText()+"/import");
        assertEquals(200,dated.statusCode());var result=parse(dated.asString());inputs.validate("VaultEntry",result);assertEquals(CREATED,result.path("createdAt").asText());assertEquals("ORIGINAL",result.path("createdAtSource").asText());assertEquals(MODIFIED,result.path("modifiedAt").asText());assertEquals("Imported exact\r\n",content(entry));
    }
    @Test void missingStaleForeignAndReadOnlyValidatorsCannotOverwrite(){
        var entry=create("overwrite-guards.md");String tag=entry.path("entryEtag").asText();
        assertEquals(428,overwrite(entry,"test-a",null,"Rejected").statusCode());assertEquals(412,overwrite(entry,"test-a","\"stale\"","Rejected").statusCode());assertEquals(404,overwrite(entry,"test-b",tag,"Rejected").statusCode());assertEquals(403,overwrite(entry,"test-read",tag,"Rejected").statusCode());
        create("overwrite-quota-other.md");assertEquals(507,overwrite(entry,"test-a",tag,"x".repeat(4096)).statusCode());assertEquals("Original\r\n",content(entry));
        var changed=overwrite(entry,"test-a",tag,"Concurrent change");assertEquals(200,changed.statusCode());assertEquals(412,overwrite(entry,"test-a",tag,"Outdated import").statusCode());assertEquals("Concurrent change",content(entry));
    }
    @Test void movesAndDeletionAfterReviewAreConflictsAndNativeLabelsRemainIndependent(){
        var moved=create("overwrite-move.md");String tag=moved.path("entryEtag").asText();
        store.transaction("a",()->{var vault=new VaultCollection(store,limits);var entry=vault.get(uuid(moved.path("id")));vault.move(entry,new VaultPath("overwrite-moved.md"),vault.tag(entry));return null;});
        assertEquals(412,overwrite(moved,"test-a",tag,"Wrong destination").statusCode());assertEquals("Original\r\n",content(moved));
        var deleted=create("overwrite-delete.md");assertEquals(204,given().header("Authorization","Bearer test-a").header("If-Match",deleted.path("entryEtag").asText()).delete("/api/v1/notes/entries/"+deleted.path("id").asText()).statusCode());assertEquals(412,overwrite(deleted,"test-a",deleted.path("entryEtag").asText(),"Revive unexpectedly").statusCode());
        var day=create("journal/2026-11-08.md");store.transaction("a",()->{var labels=new DayLabels(store);var date=DayNotes.date("2026-11-08");labels.save(date,etag(labels.get(date)),"Keep label");return null;});
        assertEquals(200,overwrite(day,"test-a",day.path("entryEtag").asText(),"Native imported body\r\n").statusCode());
        store.transaction("a",()->{assertEquals("Keep label",new DayLabels(store).get(DayNotes.date("2026-11-08")).path("text").asText());assertEquals("Native imported body\r\n",new DayNotes(store).getContent(DayNotes.date("2026-11-08")).path("body").asText());return null;});
    }
}
