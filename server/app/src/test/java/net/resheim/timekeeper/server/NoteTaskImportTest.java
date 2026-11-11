package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.TaskStatus;

@QuarkusTest @TestProfile(NoteTaskImportTest.Profile.class)
class NoteTaskImportTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("notetaskimport","jdbc:h2:mem:note_task_import_test");}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    private static final String MODIFIED="2020-02-03T04:05:06.123456789Z";
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private VaultCollection vault(){var vault=new VaultCollection(store,limits);vault.initialize();return vault;}
    @Test void completedImportsUseOriginalModificationTimeAndPatchOnlyRealCheckboxes(){
        store.transaction("completed-import",()->{
            var vault=vault();String original="\ufeff---\r\ncustom: exact\r\n---\r\n- [ ] Open **task**\r\n- [x] Done\r\n\r\n```md\r\n- [ ] Code example\r\n```\r\n";
            var entry=NoteTaskImport.COMPLETED.write(store,VaultImportDates.parse(null,MODIFIED),()->vault.put(new VaultPath("Imported.md"),null,true,bytes(original)));
            assertArrayEquals(bytes(original.replace("[ ] Open","[x] Open")),vault.read(entry));assertEquals(MODIFIED,entry.path("modifiedAt").asText());
            assertEquals(2,store.service().tasks().size());for(var task:store.service().tasks()){assertEquals(TaskStatus.COMPLETED,task.planning().status());assertEquals(Instant.parse(MODIFIED),task.planning().completedAt().orElseThrow());}
            new NoteTasks(store).existing(entry.path("sourceType").asText(),uuid(entry.path("sourceId")));assertEquals(2,store.service().tasks().size());return null;
        });
    }
    @Test void ignoredItemsStayIgnoredAcrossTransactionsMaintenanceMovesAndProseEdits(){
        UUID[] id={null};
        store.transaction("ignored-import",()->{
            var vault=vault();var entry=NoteTaskImport.IGNORE.write(store,VaultImportDates.parse(null,MODIFIED),()->vault.put(new VaultPath("Ignored.md"),null,true,bytes("- [ ] Ignored\n- [x] Also ignored\n")));id[0]=VaultCollection.id(entry);
            assertTrue(store.service().tasks().isEmpty());assertEquals(2,store.list(NoteTasks.LINK).size());return null;
        });
        store.transaction("ignored-import",()->{
            var vault=vault();var entry=vault.get(id[0]);new NoteTasks(store).existing(entry.path("sourceType").asText(),uuid(entry.path("sourceId")));
            entry=vault.move(entry,new VaultPath("Moved.md"),vault.tag(entry));entry=vault.put(new VaultPath("Moved.md"),vault.tag(entry),false,bytes("Prose\n\n- [ ] Ignored\n- [x] Also ignored\n"));
            assertTrue(store.service().tasks().isEmpty());new NoteTasks(store).existing(entry.path("sourceType").asText(),uuid(entry.path("sourceId")));assertTrue(store.service().tasks().isEmpty());
            vault.put(new VaultPath("Moved.md"),vault.tag(entry),false,bytes("Prose\n\n- [x] Ignored\n- [x] Also ignored\n"));assertEquals(1,store.service().tasks().size());assertEquals("Ignored",store.service().tasks().getFirst().summary());return null;
        });
    }
    @Test void checkboxPolicyPreservesBytesAndNativeJournalImportsKeepDayLabels(){
        store.transaction("checkbox-import",()->{
            var vault=vault();var labels=new DayLabels(store);var date=DayNotes.date("2026-11-01");labels.save(date,etag(labels.get(date)),"Independent label");
            String original="- [ ] Open\r\n- [x] Done\r\n";
            var entry=NoteTaskImport.CHECKBOX.write(store,VaultImportDates.parse(null,MODIFIED),()->vault.put(new VaultPath("journal/2026-11-01.md"),null,true,bytes(original)));
            assertArrayEquals(bytes(original),vault.read(entry));assertEquals(MODIFIED,entry.path("modifiedAt").asText());assertEquals("Independent label",labels.get(date).path("text").asText());
            assertEquals(2,store.service().tasks().size());assertEquals(1,store.service().tasks().stream().filter(task->task.planning().status()==TaskStatus.COMPLETED).count());return null;
        });
    }
    @Test void reviewedReplacementsKeepTaskIdentityAndRejectStaleForeignAndReadOnlyWrites(){
        String path="Task policy "+UUID.randomUUID()+".md",original="- [ ] Reviewed task\n";
        var created=given().header("Authorization","Bearer test-a").header("If-None-Match","*").contentType("application/octet-stream").queryParam("path",path).body(bytes(original)).put("/api/v1/notes/files");assertEquals(201,created.statusCode());String id=created.jsonPath().getString("id"),tag=created.header("ETag");
        var before=store.transaction("a",()->store.service().tasks().stream().filter(task->task.summary().equals("Reviewed task")).findFirst().orElseThrow().id());
        for(var token:List.of("test-read","test-b"))assertEquals(token.equals("test-read")?403:404,given().header("Authorization","Bearer "+token).header("If-Match",tag).contentType("application/octet-stream").queryParam("taskPolicy","COMPLETED").body(bytes(original)).put("/api/v1/notes/entries/"+id+"/import").statusCode());
        assertEquals(422,given().header("Authorization","Bearer test-a").header("If-Match",tag).contentType("application/octet-stream").queryParam("taskPolicy","INVALID").body(bytes(original)).put("/api/v1/notes/entries/"+id+"/import").statusCode());
        var saved=given().header("Authorization","Bearer test-a").header("If-Match",tag).contentType("application/octet-stream").queryParam("taskPolicy","COMPLETED").queryParam("modifiedAt",MODIFIED).body(bytes(original)).put("/api/v1/notes/entries/"+id+"/import");assertEquals(200,saved.statusCode());
        store.transaction("a",()->{var task=store.service().task(before).orElseThrow();assertEquals(TaskStatus.COMPLETED,task.planning().status());assertEquals(Instant.parse(MODIFIED),task.planning().completedAt().orElseThrow());return null;});
        assertEquals("- [x] Reviewed task\n",given().header("Authorization","Bearer test-a").get("/api/v1/notes/entries/"+id+"/content").asString());
        assertEquals(412,given().header("Authorization","Bearer test-a").header("If-Match",tag).contentType("application/octet-stream").queryParam("taskPolicy","IGNORE").body(bytes("Wrong")).put("/api/v1/notes/entries/"+id+"/import").statusCode());
    }
    @Test void invalidImportedChecklistRollsBackTheFileAndEveryTask(){
        assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction("task-import-rollback",()->NoteTaskImport.COMPLETED.write(store,VaultImportDates.parse(null,MODIFIED),()->vault().put(new VaultPath("Invalid.md"),null,true,bytes("- [ ] Item\n".repeat(1001)))))).status);
        store.transaction("task-import-rollback",()->{assertTrue(store.service().tasks().isEmpty());assertTrue(store.list(VaultContent.TYPE).isEmpty());assertTrue(store.list(NoteTasks.DOCUMENT).isEmpty());return null;});
    }
}
