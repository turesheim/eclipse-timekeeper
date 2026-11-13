package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(NoteTasksTest.Profile.class)
class NoteTasksTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("notetasks","jdbc:h2:mem:note_tasks_test");}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private Task completed(Task task,boolean done){return store.service().updateTaskPlanning(new UpdateTaskPlanning(task.id(),task.version(),new TaskPlanning(done?TaskStatus.COMPLETED:TaskStatus.NOT_STARTED,task.planning().scheduledDate(),done?Optional.of(store.now()):Optional.empty())));}
    private VaultCollection vault(){var vault=new VaultCollection(store,limits);vault.initialize();return vault;}
    @Test void canonicalFileWritesTrackSeparateTasksWithoutChangingMarkdown(){
        store.transaction("tasks-file",()->{
            var vault=vault();String text="\ufeff---\r\ncustom: preserved\r\n---\r\n# Work\r\n- [ ] First **task**\r\n- [x] Same\r\n- [ ] Same\r\n```md\r\n- [ ] Example only\r\n```\r\n";
            var entry=vault.put(new VaultPath("Work.md"),null,true,bytes(text));var tasks=store.service().tasks();assertEquals(3,tasks.size());assertArrayEquals(bytes(text),vault.read(entry));assertEquals(3,tasks.stream().map(Task::id).distinct().count());assertEquals(1,tasks.stream().filter(task->task.planning().status()==TaskStatus.COMPLETED).count());
            String next=text.replace("First **task**","Renamed task").replace("- [x] Same","- [ ] Same");var saved=vault.put(new VaultPath("Work.md"),vault.tag(entry),false,bytes(next));assertEquals(3,store.service().tasks().size());assertTrue(store.service().tasks().stream().allMatch(task->task.planning().status()==TaskStatus.NOT_STARTED));assertTrue(store.service().tasks().stream().anyMatch(task->task.summary().equals("Renamed task")));assertArrayEquals(bytes(next),vault.read(saved));
            var versions=store.service().tasks().stream().map(Task::version).sorted().toList();vault.put(new VaultPath("Work.md"),vault.tag(saved),false,bytes(next+"\r\nProse only\r\n"));assertEquals(versions,store.service().tasks().stream().map(Task::version).sorted().toList());return null;
        });
    }
    @Test void normalTaskCompletionPatchesOnlyTheCheckboxAndRejectsAnOldSourceValidator(){
        store.transaction("tasks-reverse",()->{
            var vault=vault();String text="\ufeff---\r\ncustom: exact\r\n---\r\n- [ ] Keep **formatting** 😀\r\n";var entry=vault.put(new VaultPath("Exact.md"),null,true,bytes(text));String tag=vault.tag(entry);var task=store.service().tasks().getFirst();
            task=completed(task,true);var current=vault.get(VaultCollection.id(entry));assertArrayEquals(bytes(text.replace("[ ]","[x]")),vault.read(current));assertNotEquals(tag,vault.tag(current));assertEquals(412,assertThrows(ApiFailure.class,()->vault.put(new VaultPath("Exact.md"),tag,false,bytes(text+"Draft"))).status);
            completed(task,false);assertArrayEquals(bytes(text),vault.read(vault.get(VaultCollection.id(entry))));return null;
        });
    }
    @Test void taskIdentityFollowsReorderingAndFileMovesAndRemovalDeletesUnusedTasks(){
        store.transaction("tasks-identity",()->{
            var vault=vault();var entry=vault.put(new VaultPath("Before.md"),null,true,bytes("- [ ] Alpha\n- [ ] Beta\n"));var ids=new HashMap<String,TaskId>();store.service().tasks().forEach(task->ids.put(task.summary(),task.id()));
            entry=vault.move(entry,new VaultPath("After.md"),vault.tag(entry));entry=vault.put(new VaultPath("After.md"),vault.tag(entry),false,bytes("- [x] Beta\n- [ ] Added\n- [ ] Alpha\n"));assertEquals(ids.get("Beta"),store.service().tasks().stream().filter(task->task.summary().equals("Beta")).findFirst().orElseThrow().id());
            entry=vault.put(new VaultPath("After.md"),vault.tag(entry),false,bytes("- [ ] Alpha\n"));assertEquals(1,store.service().tasks().size());assertTrue(store.service().task(ids.get("Beta")).isEmpty());assertArrayEquals(bytes("- [ ] Alpha\n"),vault.read(vault.get(VaultCollection.id(entry))));return null;
        });
    }
    @Test void deletingANormalTaskDoesNotRecreateItOnUnrelatedNoteEdits(){
        store.transaction("tasks-delete",()->{
            var vault=vault();var entry=vault.put(new VaultPath("Deleted task.md"),null,true,bytes("- [ ] Deliberate\n"));var task=store.service().tasks().getFirst();store.service().deleteTask(new DeleteTask(task.id(),task.version()));
            entry=vault.put(new VaultPath("Deleted task.md"),vault.tag(entry),false,bytes("Prose\n\n- [ ] Deliberate\n"));assertTrue(store.service().tasks().isEmpty());entry=vault.put(new VaultPath("Deleted task.md"),vault.tag(entry),false,bytes("Prose\n\n- [x] Deliberate\n"));assertEquals(1,store.service().tasks().size());assertNotEquals(task.id(),store.service().tasks().getFirst().id());return null;
        });
    }
    @Test void trashRetainsIdentitiesAndRestoresTheCurrentTaskState(){
        store.transaction("tasks-trash",()->{
            var vault=vault();var entry=vault.put(new VaultPath("Restore.md"),null,true,bytes("- [ ] Restored\r\n"));var task=store.service().tasks().getFirst();vault.delete(entry,vault.tag(entry));completed(task,true);var trashed=vault.get(VaultCollection.id(entry));var restored=vault.restore(trashed,vault.tag(trashed));assertEquals(1,store.service().tasks().size());assertArrayEquals(bytes("- [x] Restored\r\n"),vault.read(restored));return null;
        });
    }
    @Test void nativeDailyAndWeeklyWritersWorkBeforeTheNotesCollectionHasBeenOpened(){
        store.transaction("tasks-native",()->{
            var notes=new DayNotes(store);var day=DayNotes.date("2026-11-09");notes.saveContent(day,etag(notes.getContent(day)),object().put("body","- [ ] Daily task\n"));var task=store.service().tasks().getFirst();completed(task,true);assertEquals("- [x] Daily task\n",notes.getContent(day).path("body").asText());
            var weeks=new WeekNotes(store);var week=WeekNotes.week("2026-W46");weeks.saveDocument(week,etag(weeks.get(week)),bytes("- [x] Weekly task\n"));assertEquals(2,store.service().tasks().size());assertFalse(new VaultState(store,limits).get().path("initialized").asBoolean());return null;
        });
    }
    @Test void binaryFilesAndNonCheckboxExamplesNeverCreateTasks(){
        store.transaction("tasks-examples",()->{var vault=vault();vault.put(new VaultPath("Example.bin"),null,true,bytes("- [ ] Binary bytes"));vault.put(new VaultPath("Examples.md"),null,true,bytes("```markdown\n- [ ] Code\n```\n\n    - [ ] Indented\n\n\\- [ ] Escaped\n\n[ ] Ordinary text\n"));assertTrue(store.service().tasks().isEmpty());return null;});
    }
    @Test void bindingsAreOwnedAndCompletionCannotTouchAnotherAccountsNote(){
        UUID[] id={null};store.transaction("tasks-owner-a",()->{var entry=vault().put(new VaultPath("Private.md"),null,true,bytes("- [ ] Private task\n"));id[0]=VaultCollection.id(entry);return null;});
        store.transaction("tasks-owner-b",()->{assertTrue(store.service().tasks().isEmpty());var entry=vault().put(new VaultPath("Private.md"),null,true,bytes("- [ ] Other task\n"));completed(store.service().tasks().getFirst(),true);assertArrayEquals(bytes("- [x] Other task\n"),vault().read(vault().get(VaultCollection.id(entry))));return null;});
        store.transaction("tasks-owner-a",()->{assertArrayEquals(bytes("- [ ] Private task\n"),vault().read(vault().get(id[0])));return null;});
    }
    @Test void savedEventTasksPreserveIdentityFrontmatterAndTaskPlanning(){
        store.transaction("tasks-event",()->{
            var key=new EventNotes.Key(UUID.randomUUID(),"ab".repeat(32));var events=new EventNotes(store);var value=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event()).put("body","- [ ] Event task\n");value.set("event",object().put("uid","event uid").put("recurrenceId","2026-11-09T10:00:00Z").put("title","Meeting"));byte[] before=EventNotes.document(value);events.saveDocument(key,etag(events.get(key)),before);var task=store.service().tasks().getFirst();
            task=store.service().updateTaskPlanning(new UpdateTaskPlanning(task.id(),task.version(),new TaskPlanning(TaskStatus.WAITING,Optional.of(java.time.LocalDate.of(2026,11,12)),Optional.empty())));events.saveDocument(key,etag(events.get(key)),bytes(new String(before,StandardCharsets.UTF_8)+"\nProse"));assertEquals(TaskStatus.WAITING,store.service().task(task.id()).orElseThrow().planning().status());
            completed(task,true);assertArrayEquals(bytes(new String(before,StandardCharsets.UTF_8).replace("[ ]","[x]")+"\nProse"),events.document(key));assertEquals(java.time.LocalDate.of(2026,11,12),store.service().task(task.id()).orElseThrow().planning().scheduledDate().orElseThrow());return null;
        });
    }
    @Test void openingOrSavingUnchangedLegacyNotesDoesNotEnrollThem(){
        store.transaction("tasks-existing",()->{var vault=vault();var entry=store.withNoteTaskSync(()->vault.put(new VaultPath("Existing.md"),null,true,bytes("- [ ] Existing task\n")));vault.read(entry);vault.put(new VaultPath("Existing.md"),vault.tag(entry),false,bytes("- [ ] Existing task\n"));assertTrue(store.service().tasks().isEmpty());vault.put(new VaultPath("Existing.md"),vault.tag(entry),false,bytes("- [ ] Existing task\n\nEdited prose\n"));assertEquals(1,store.service().tasks().size());return null;});
    }
    @Test void longChecklistTextKeepsItsSourceAndUsesAValidUnicodeTaskTitle(){
        store.transaction("tasks-long",()->{var vault=vault();String title="😀".repeat(1100),text="- [ ] "+title+"\n";var entry=vault.put(new VaultPath("Long.md"),null,true,bytes(text));var task=store.service().tasks().getFirst();assertEquals(1024,task.summary().codePointCount(0,task.summary().length()));assertTrue(task.summary().endsWith("…"));assertArrayEquals(bytes(text),vault.read(entry));completed(task,true);assertArrayEquals(bytes(text.replace("[ ]","[x]")),vault.read(vault.get(VaultCollection.id(entry))));return null;});
    }
    @Test void invalidChecklistWriteRollsBackBothTheSourceAndAllTaskChanges(){
        assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction("tasks-limit",()->vault().put(new VaultPath("Too many.md"),null,true,bytes("- [ ] Item\n".repeat(1001))))).status);
        store.transaction("tasks-limit",()->{assertTrue(store.service().tasks().isEmpty());assertTrue(store.list(NoteTasks.DOCUMENT).isEmpty());assertTrue(store.list(VaultContent.TYPE).isEmpty());return null;});
    }
    @Test void publicWritersRespectReadOnlyTokensAndForeignTaskAndFileValidators(){
        var created=given().header("Authorization","Bearer test-a").queryParam("path","HTTP checklist.md").header("If-None-Match","*").contentType("application/octet-stream").body(bytes("- [ ] HTTP task\n")).put("/api/v1/notes/files");assertEquals(201,created.statusCode());String entry=created.jsonPath().getString("id");
        var tasks=given().header("Authorization","Bearer test-a").queryParam("limit",500).get("/api/v1/tasks");String id=tasks.jsonPath().getString("items[0].id");var task=given().header("Authorization","Bearer test-a").get("/api/v1/tasks/"+id);String planning="{\"status\":\"COMPLETED\",\"scheduledDate\":null,\"completedAt\":\"2026-11-09T10:00:00Z\"}";
        assertEquals(403,given().header("Authorization","Bearer test-read").header("If-Match",task.header("ETag")).contentType("application/json").body(planning).put("/api/v1/tasks/"+id+"/planning").statusCode());
        assertEquals(404,given().header("Authorization","Bearer test-b").header("If-Match",task.header("ETag")).contentType("application/json").body(planning).put("/api/v1/tasks/"+id+"/planning").statusCode());
        assertEquals(403,given().header("Authorization","Bearer test-read").header("If-Match",created.header("ETag")).contentType("application/octet-stream").body(bytes("- [x] HTTP task\n")).put("/api/v1/notes/entries/"+entry+"/content").statusCode());
        assertEquals("- [ ] HTTP task\n",given().header("Authorization","Bearer test-a").get("/api/v1/notes/entries/"+entry+"/content").asString());
    }
    @Test void disabledConfigurationSourcesDoNotBreakTheTaskListProjection(){
        store.transaction("tasks-disabled-source",()->{
            var settings=new VaultSettings(store);settings.save(etag(settings.get()),object().put("syncObsidianConfig",true));
            var vault=vault();vault.mkdir(new VaultPath(".obsidian"));vault.put(new VaultPath(".obsidian/Checklist.md"),null,true,bytes("- [ ] Configuration task\n"));assertEquals(1,new NoteTasks(store).origins().size());
            settings.save(etag(settings.get()),object().put("syncObsidianConfig",false));assertTrue(new NoteTasks(store).origins().isEmpty());assertEquals(1,store.service().tasks().size());return null;
        });
    }
    @Test void taskListSourceProjectionIsOptionalOwnedAndKeepsCanonicalValidators(){
        String title="List source "+UUID.randomUUID(),path=title+".md";
        var created=given().header("Authorization","Bearer test-a").queryParam("path",path).header("If-None-Match","*").contentType("application/octet-stream").body(bytes("- [ ] "+title+"\n")).put("/api/v1/notes/files");assertEquals(201,created.statusCode());String entry=created.jsonPath().getString("id");
        var plain=given().header("Authorization","Bearer test-a").queryParam("q",title).get("/api/v1/tasks");assertEquals(200,plain.statusCode());String task=plain.jsonPath().getString("items[0].id");assertNull(plain.jsonPath().get("items[0].sourceNote"));
        String tag=given().header("Authorization","Bearer test-a").get("/api/v1/tasks/"+task).header("ETag");
        var projected=given().header("Authorization","Bearer test-read").queryParam("q",title).queryParam("includeNoteSources",true).get("/api/v1/tasks");assertEquals(200,projected.statusCode());assertEquals(entry,projected.jsonPath().getString("items[0].sourceNote.id"));assertEquals("MARKDOWN",projected.jsonPath().getString("items[0].sourceNote.kind"));
        assertEquals(tag,given().header("Authorization","Bearer test-a").get("/api/v1/tasks/"+task).header("ETag"));
        assertTrue(given().header("Authorization","Bearer test-b").queryParam("q",title).queryParam("includeNoteSources",true).get("/api/v1/tasks").jsonPath().getList("items").isEmpty());
        assertEquals(400,given().header("Authorization","Bearer test-a").queryParam("includeNoteSources","maybe").get("/api/v1/tasks").statusCode());
        assertEquals(204,given().header("Authorization","Bearer test-a").header("If-Match",created.header("ETag")).delete("/api/v1/notes/entries/"+entry).statusCode());
        var trashed=given().header("Authorization","Bearer test-a").queryParam("q",title).queryParam("includeNoteSources",true).get("/api/v1/tasks");assertEquals(200,trashed.statusCode());assertEquals(task,trashed.jsonPath().getString("items[0].id"));assertNull(trashed.jsonPath().get("items[0].sourceNote"));
    }
}
