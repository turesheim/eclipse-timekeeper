package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(NoteTaskArchiveTest.Profile.class)
class NoteTaskArchiveTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("notetaskarchive","jdbc:h2:mem:note_task_archive_test");}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject TokenRegistry tokens;
    @Inject AccountArchive archives;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    private AccountArchiveImport importer(){return new AccountArchiveImport(store,inputs,receipts);}
    private UUID stage(byte[] bytes){return store.transaction("b",()->{var uploads=new AccountUploads(store);var upload=uploads.create("account.zip","application/zip",bytes.length,ArchiveJournalFiles.digest(bytes));UUID id=uuid(upload.path("id"));for(int offset=0,index=0;offset<bytes.length;offset+=AccountUploads.CHUNK_BYTES,index++)uploads.chunk(id,index,Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+AccountUploads.CHUNK_BYTES))));return id;});}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void inconsistentActiveCheckboxAndTaskStateCannotBeRestored(){
        store.transaction("bad-checklist-archive",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();var entry=store.withNoteTaskSync(()->vault.put(new VaultPath("Inconsistent.md"),null,true,bytes("- [ ] Inconsistent\n")));
            var task=store.service().createTask(new CreateTask(Optional.empty(),"Inconsistent",Optional.empty()));task=store.service().updateTaskPlanning(new UpdateTaskPlanning(task.id(),task.version(),new TaskPlanning(TaskStatus.COMPLETED,Optional.empty(),Optional.of(store.now()))));
            UUID source=UUID.randomUUID();var metadata=object().put("sourceType",VaultContent.TYPE).put("sourceKey",entry.path("sourceKey").asText());metadata.putArray("items").add(object().put("taskId",source.toString()).put("marker",2).put("summary","Inconsistent").put("checked",false).put("depth",0));
            var mappings=array().add(object().put("entityType","TASK").put("sourceId",source.toString()).put("id",task.id().value().toString()));
            assertEquals(422,assertThrows(ApiFailure.class,()->NoteTaskArchive.restore(array().add(metadata),mappings,store)).status);assertTrue(store.list(NoteTasks.DOCUMENT).isEmpty());assertTrue(store.list(NoteTasks.LINK).isEmpty());return null;
        });
    }
    @Test void reviewedAccountRestoreRemapsTasksWithoutCreatingDuplicatesAndKeepsDeletedTaskDecisions()throws Exception{
        var principal=tokens.authenticate("Bearer test-a");tokens.authenticate("Bearer test-b");var sourceIds=new HashSet<TaskId>();UUID[] trash={null},sourceClient={null};
        store.transaction("a",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();var work=vault.put(new VaultPath("Work.md"),null,true,bytes("- [ ] Active task\r\n- [ ] Deliberately deleted\r\n"));
            sourceClient[0]=uuid(store.createClient(object().put("name","Checklist client").put("color","#285ca8")).path("id"));var associations=new NoteClients(store);var ref=VaultSources.fromEntry(work);associations.save(ref,associations.tag(ref),sourceClient[0]);
            var deleted=store.service().tasks().stream().filter(task->task.summary().equals("Deliberately deleted")).findFirst().orElseThrow();store.service().deleteTask(new DeleteTask(deleted.id(),deleted.version()));
            var date=DayNotes.date("2026-11-09");var notes=new DayNotes(store);notes.saveContent(date,etag(notes.getContent(date)),object().put("body","- [x] Native task\n"));
            var entry=vault.put(new VaultPath("Trash.md"),null,true,bytes("- [ ] Task changed while trashed\r\n"));trash[0]=VaultCollection.id(entry);vault.delete(entry,vault.tag(entry));var task=store.service().tasks().stream().filter(value->value.summary().equals("Task changed while trashed")).findFirst().orElseThrow();store.service().updateTaskPlanning(new UpdateTaskPlanning(task.id(),task.version(),new TaskPlanning(TaskStatus.COMPLETED,Optional.empty(),Optional.of(store.now()))));store.service().tasks().forEach(value->sourceIds.add(value.id()));return null;
        });
        try(var download=archives.download(principal)){
            var snapshot=AccountArchiveInput.read(download.file(),"application/zip");inputs.validate("AccountArchiveSnapshot",snapshot);assertEquals(3,snapshot.path("work").path("tasks").size());assertEquals(4,AccountArchiveInput.counts(snapshot).path("noteTasks").asInt());assertFalse(snapshot.path("noteTasks").toString().contains("ownerId"));
            UUID upload=stage(Files.readAllBytes(download.file()));var preview=store.transaction("b",()->importer().preview(upload,false));assertTrue(preview.body().path("canImport").asBoolean());
            store.transaction("b",()->{assertTrue(store.service().tasks().isEmpty());assertTrue(store.list(NoteTasks.DOCUMENT).isEmpty());assertTrue(store.list(NoteTasks.LINK).isEmpty());assertTrue(store.list(NoteClients.TYPES.get(VaultContent.TYPE)).isEmpty());return null;});
            var imported=store.transaction("b",()->importer().apply(upload,false,preview.etag()));assertEquals(201,imported.status());
            store.transaction("b",()->{
                assertEquals(3,store.service().tasks().size());assertTrue(store.service().tasks().stream().noneMatch(task->sourceIds.contains(task.id())));var vault=new VaultCollection(store,limits);var entry=vault.get(new VaultPath("Work.md"));UUID client=uuid(new NoteClients(store).get(VaultSources.fromEntry(entry)).path("clientId"));assertNotEquals(sourceClient[0],client);assertEquals("Checklist client",store.find("CLIENT",client).orElseThrow().path("name").asText());assertArrayEquals(bytes("- [ ] Active task\r\n- [ ] Deliberately deleted\r\n"),vault.read(entry));
                vault.put(new VaultPath("Work.md"),vault.tag(entry),false,bytes("- [ ] Active task\r\n- [ ] Deliberately deleted\r\n\r\nProse only\r\n"));assertEquals(3,store.service().tasks().size());
                var active=store.service().tasks().stream().filter(task->task.summary().equals("Active task")).findFirst().orElseThrow();store.service().updateTaskPlanning(new UpdateTaskPlanning(active.id(),active.version(),new TaskPlanning(TaskStatus.COMPLETED,Optional.empty(),Optional.of(store.now()))));assertTrue(new String(vault.read(vault.get(new VaultPath("Work.md"))),StandardCharsets.UTF_8).startsWith("- [x] Active task\r\n"));
                var trashed=vault.get(trash[0]);var restored=vault.restore(trashed,vault.tag(trashed));assertArrayEquals(bytes("- [x] Task changed while trashed\r\n"),vault.read(restored));assertEquals(3,store.service().tasks().size());return null;
            });
            var repeated=store.transaction("b",()->importer().apply(upload,false,preview.etag()));assertEquals(parse(imported.body().toString()),repeated.body());
            var bad=snapshot.deepCopy();((ObjectNode)bad.path("noteTasks").get(0).path("items").get(0)).put("taskId",UUID.randomUUID().toString());assertEquals(422,assertThrows(ApiFailure.class,()->NoteTaskArchive.validate(bad.path("noteTasks"),bad.path("work"))).status);
            var malformed=snapshot.deepCopy();((ObjectNode)malformed.path("noteTasks").get(0).path("items").get(0)).put("taskId","invalid");assertTrue(assertThrows(ApiFailure.class,()->NoteTaskArchive.validate(malformed.path("noteTasks"),malformed.path("work"))).status>=400);
        }
    }
}
