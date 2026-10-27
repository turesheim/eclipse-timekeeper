package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(AccountArchiveImportTest.Profile.class)
class AccountArchiveImportTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return ArchiveTestDatabase.configuration("import","jdbc:h2:mem:account_archive_import_tests");}
    }
    @Inject ServerStore store;
    @Inject AccountArchive archives;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Inject TokenRegistry tokens;
    private static final String TARGET_PASSWORD="The target keeps its own passphrase";
    private AccountArchiveImport importer() {return new AccountArchiveImport(store,inputs,receipts);}
    @BeforeEach void reset() throws Exception {
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);actual.stopMaintenance();
        try(var c=actual.datasource.getConnection()) {
            c.setAutoCommit(false);
            for(String table:List.of("tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource"))
                try(var s=c.createStatement()) {s.executeUpdate("DELETE FROM "+table+" WHERE owner_id IN ('a','b')");}
            c.commit();
        }
        for(String owner:List.of("a","b")) store.transaction(owner,()->{store.updateProfile("UTC");return null;});
        String passwordHash=AccountPasswords.hash(TARGET_PASSWORD);
        store.transaction("b",()->{
            store.update("UPDATE tk_account SET username='archive-target',display_name='Archive target',password_hash=?,state='ENABLED',administrator=FALSE WHERE owner_id=?",passwordHash,store.owner());
            store.update("DELETE FROM tk_recovery_address WHERE owner_id=?",store.owner());
            store.update("INSERT INTO tk_recovery_address VALUES(?,'PRIMARY',?,NULL,1)",store.owner(),"target-private@example.invalid");return null;
        });
    }
    private ObjectNode source() throws Exception {
        store.transaction("a",()->{
            store.updateProfile("Europe/Oslo");store.update("UPDATE tk_account SET administrator=TRUE,display_name='Source administrator' WHERE owner_id=?",store.owner());
            var service=store.service();var project=service.createProject(new CreateProject("Archive project"));
            store.clientDetails("PROJECT",project.id().value(),object().put("icon","code"));
            var task=service.createTask(new CreateTask(Optional.of(project.id()),Optional.empty(),"Archive task",Optional.of("https://example.invalid/task"),Set.of()));
            var label=service.createLabel(new CreateLabel("Archive label",Optional.of("#0c2238")));
            var start=Instant.parse("2025-10-20T06:03:00.123456789Z");var end=Instant.parse("2025-10-20T07:03:00.987654321Z");
            service.createActivity(new CreateActivity(task.id(),new OwnerId(store.owner()),start,Optional.of(end),"Exact activity notes",Set.of(label.id()),Optional.empty(),Optional.of(project.id())));
            var session=store.workSessionService().createWorkSession(new CreateWorkSession(new OwnerId(store.owner()),start,Optional.of(end),"Exact session notes"));
            store.clientDetails("WORK_SESSION",session.id().value(),object().put("projectId",project.id().value().toString()).put("title","Archive title"));
            new ImportLedger(store).create("Original import audit",hash("source"),"Europe/Oslo",hash("file"),hash("plan"));return null;
        });
        try(var file=archives.download(tokens.authenticate("Bearer test-a"))) {return AccountArchiveInput.read(file.file(),"application/zip");}
    }
    private UUID stage(ObjectNode archive) throws Exception {
        byte[] bytes=archive.toString().getBytes(StandardCharsets.UTF_8);String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return store.transaction("b",()->{
            var uploads=new AccountUploads(store);var upload=uploads.create("account.json","application/json",bytes.length,digest);UUID id=uuid(upload.get("id"));
            for(int offset=0,index=0;offset<bytes.length;offset+=AccountUploads.CHUNK_BYTES,index++) uploads.chunk(id,index,Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+AccountUploads.CHUNK_BYTES))));
            return id;
        });
    }
    private ObjectNode work() {return store.transaction("b",()->{var value=object();for(var entry:DomainTransfer.TYPES.entrySet()) {var items=value.putArray(entry.getKey());store.list(entry.getValue()).forEach(items::add);}value.set("profile",store.profile());return value;});}
    @Test void dayNotesRoundTripWithoutWorkAndNeverOverwriteAnExistingDate() throws Exception {
        source();
        store.transaction("a",()->{var notes=new DayNotes(store);var date=DayNotes.date("2026-05-17");notes.save(date,etag(notes.get(date)),"Ferie");return null;});
        ObjectNode archive;try(var file=archives.download(tokens.authenticate("Bearer test-a"))){archive=AccountArchiveInput.read(file.file(),"application/zip");}
        assertEquals(object().put("date","2026-05-17").put("text","Ferie"),archive.path("dayNotes").get(0));
        assertEquals(1,archive.path("dayNotes").size());inputs.validate("AccountArchiveSnapshot",archive);
        // A note-only archive remains useful when there are no timed records.
        for(String name:DomainTransfer.TYPES.keySet())((ObjectNode)archive.path("work")).putArray(name);
        UUID id=stage(archive);var review=store.transaction("b",()->importer().preview(id,false));
        assertTrue(review.body().path("canImport").asBoolean());assertEquals(1,review.body().path("counts").path("dayNotes").asInt());
        store.transaction("b",()->{assertTrue(store.list(DayNotes.TYPE).isEmpty());return null;});
        store.transaction("b",()->importer().apply(id,false,review.etag()));
        store.transaction("b",()->{assertEquals("Ferie",new DayNotes(store).get(DayNotes.date("2026-05-17")).path("text").asText());assertTrue(store.list("WORK_SESSION").isEmpty());return null;});
        var conflicting=archive.deepCopy().put("archiveId",UUID.randomUUID().toString());((ObjectNode)conflicting.path("dayNotes").get(0)).put("text","Avspasering");
        UUID conflictId=stage(conflicting);var rejected=store.transaction("b",()->importer().preview(conflictId,false));assertFalse(rejected.body().path("canImport").asBoolean());
        store.transaction("b",()->{assertEquals("Ferie",new DayNotes(store).get(DayNotes.date("2026-05-17")).path("text").asText());return null;});
        var duplicate=archive.deepCopy().put("archiveId",UUID.randomUUID().toString());((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("dayNotes")).add(duplicate.path("dayNotes").get(0).deepCopy());UUID duplicateId=stage(duplicate);
        assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction("b",()->importer().preview(duplicateId,false))).status);
    }
    @Test void markdownBodiesRoundTripExactlyAndLegacyArchivesCannotEraseThem() throws Exception {
        source();String markdown="\r\n# Private journal\r\n\r\n- [ ] Oppfølging\t\r\n  keep spaces  \r\n";
        store.transaction("a",()->{
            var notes=new DayNotes(store);
            for(var entry:Map.of("2026-10-01",object().put("text","Planning").put("body",markdown),"2026-10-02",object().put("body","\n \t\n"),"2026-10-03",object().put("text","Legacy label")).entrySet()){
                var date=DayNotes.date(entry.getKey());notes.save(date,etag(notes.get(date)),entry.getValue());
            }
            var date=DayNotes.date("2026-10-04");notes.save(date,etag(notes.get(date)),object().put("body","Removed"));notes.save(date,etag(notes.get(date)),object().put("body",""));return null;
        });
        ObjectNode archive;try(var file=archives.download(tokens.authenticate("Bearer test-read"))){archive=AccountArchiveInput.read(file.file(),"application/zip");}
        inputs.validate("AccountArchiveSnapshot",archive);assertEquals(3,archive.path("dayNotes").size());
        assertEquals(markdown,archive.path("dayNotes").get(0).path("body").asText());assertEquals("",archive.path("dayNotes").get(1).path("text").asText());assertEquals("\n \t\n",archive.path("dayNotes").get(1).path("body").asText());assertFalse(archive.path("dayNotes").get(2).has("body"));
        for(String name:DomainTransfer.TYPES.keySet())((ObjectNode)archive.path("work")).putArray(name);
        UUID id=stage(archive);var review=store.transaction("b",()->importer().preview(id,false));assertTrue(review.body().path("canImport").asBoolean());
        store.transaction("b",()->{assertTrue(store.list(DayNotes.TYPE).isEmpty());return null;});
        store.transaction("b",()->importer().apply(id,false,review.etag()));
        store.transaction("b",()->{var notes=new DayNotes(store);assertEquals(markdown,notes.get(DayNotes.date("2026-10-01")).path("body").asText());assertEquals("Planning",notes.get(DayNotes.date("2026-10-01")).path("text").asText());assertEquals(3,notes.list(DayNotes.date("2026-10-01"),DayNotes.date("2026-10-04")).path("items").size());return null;});
        var legacy=archive.deepCopy().put("archiveId",UUID.randomUUID().toString());var legacyNotes=legacy.putArray("dayNotes");legacyNotes.add(object().put("date","2026-10-01").put("text","Planning"));legacyNotes.add(object().put("date","2026-10-03").put("text","Legacy label"));
        UUID legacyId=stage(legacy);var legacyReview=store.transaction("b",()->importer().preview(legacyId,false));assertTrue(legacyReview.body().path("canImport").asBoolean());store.transaction("b",()->importer().apply(legacyId,false,legacyReview.etag()));
        var conflict=archive.deepCopy().put("archiveId",UUID.randomUUID().toString());((ObjectNode)conflict.path("dayNotes").get(0)).put("body","Different private journal");UUID conflictId=stage(conflict);
        assertFalse(store.transaction("b",()->importer().preview(conflictId,false)).body().path("canImport").asBoolean());
        store.transaction("b",()->{assertEquals(markdown,new DayNotes(store).get(DayNotes.date("2026-10-01")).path("body").asText());return null;});
    }
    @Test void reviewedRoundTripRemapsTheWholeGraphAndNeverRestoresIdentityOrImportAuthority() throws Exception {
        var archive=source();UUID id=stage(archive);var before=work();
        var identity=store.transaction("b",()->AccountStore.account(store.current().connection(),store.owner(),false));
        var review=store.transaction("b",()->importer().preview(id,true));assertEquals(200,review.status());assertTrue(review.body().path("canImport").asBoolean());assertEquals(before,work());
        var imported=store.transaction("b",()->importer().apply(id,true,review.etag()));assertEquals(201,imported.status());
        store.transaction("b",()->{
            assertEquals(identity,AccountStore.account(store.current().connection(),store.owner(),false));assertFalse(identity.path("administrator").asBoolean());
            try(var query=store.statement("SELECT password_hash FROM tk_account WHERE owner_id=?",store.owner());var rows=query.executeQuery()) {assertTrue(rows.next());assertTrue(AccountPasswords.matches(TARGET_PASSWORD,rows.getString(1)));}
            catch(java.sql.SQLException failure) {throw new RuntimeException(failure);}
            try(var query=store.statement("SELECT verified_address FROM tk_recovery_address WHERE owner_id=? AND slot='PRIMARY'",store.owner());var rows=query.executeQuery()) {assertTrue(rows.next());assertEquals("target-private@example.invalid",rows.getString(1));}
            catch(java.sql.SQLException failure) {throw new RuntimeException(failure);}
            assertEquals("Europe/Oslo",store.profile().path("zoneId").asText());
            var session=store.list("WORK_SESSION").getFirst();var project=store.list("PROJECT").getFirst();var activity=store.list("ACTIVITY").getFirst();var task=store.list("TASK").getFirst();
            assertEquals("code",project.path("icon").asText());assertEquals(project.get("id"),session.get("projectId"));assertEquals(project.get("clientId"),session.get("clientId"));assertEquals(project.get("id"),task.get("projectId"));assertEquals(task.get("id"),activity.get("taskId"));
            assertEquals(archive.path("work").path("workSessions").get(0).get("start"),session.get("start"));assertEquals(archive.path("work").path("activities").get(0).get("end"),activity.get("end"));
            var sourceIds=new HashSet<String>();for(String name:DomainTransfer.TYPES.keySet()) archive.path("work").path(name).forEach(value->sourceIds.add(value.path("id").asText()));
            for(String type:DomainTransfer.TYPES.values()) for(var value:store.list(type)) {assertEquals("b",value.path("ownerId").asText());assertFalse(sourceIds.contains(value.path("id").asText()));}
            assertTrue(store.list("IMPORT_JOB").isEmpty());assertEquals(1,store.list("ARCHIVE_AUDIT").size());assertEquals(1,store.list("ARCHIVE_IMPORT").size());assertTrue(store.list(AccountUploads.CHUNK).isEmpty());return null;
        });
        var saved=work();var replay=store.transaction("b",()->importer().apply(id,true,review.etag()));assertEquals(imported.body().toString(),replay.body().toString());assertEquals(saved,work());
        assertEquals("b",tokens.authenticate("Bearer test-b").owner());
        UUID reselected=stage(archive);var duplicate=store.transaction("b",()->importer().preview(reselected,false));assertTrue(duplicate.body().path("alreadyImported").asBoolean());assertFalse(duplicate.body().path("canImport").asBoolean());assertEquals(saved,work());
        var changed=archive.deepCopy();((ObjectNode)changed.path("work").path("projects").get(0)).put("name","Changed after restore");UUID changedId=stage(changed);
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction("b",()->importer().preview(changedId,false))).status);assertEquals(saved,work());
    }
    @Test void providerListsRoundTripThroughAccountZipWithNewOwnedIds() throws Exception {
        source();
        UUID sourceConnection=UUID.randomUUID(),sourceList=UUID.randomUUID();
        store.transaction("a",()->{
            store.save("TASK_CONNECTION",new TaskConnection(sourceConnection,"microsoft","account-1","Personal",true,true,true,0));
            store.save("TASK_LIST",new TaskList(sourceList,sourceConnection,"remote-list","Flagged emails",TaskListKind.BUILT_IN,
                    true,false,false,true,true,0,0));return null;
        });
        ObjectNode archive;try(var file=archives.download(tokens.authenticate("Bearer test-a"))){archive=AccountArchiveInput.read(file.file(),"application/zip");}
        assertEquals(1,archive.path("work").path("taskConnections").size());
        assertEquals(1,archive.path("work").path("taskLists").size());
        UUID id=stage(archive);var review=store.transaction("b",()->importer().preview(id,false));
        assertTrue(review.body().path("canImport").asBoolean());
        store.transaction("b",()->importer().apply(id,false,review.etag()));
        store.transaction("b",()->{
            var connection=store.list("TASK_CONNECTION").getFirst();var list=store.list("TASK_LIST").getFirst();
            assertNotEquals(sourceConnection.toString(),connection.path("id").asText());
            assertNotEquals(sourceList.toString(),list.path("id").asText());
            assertEquals(connection.path("id"),list.path("connectionId"));
            assertEquals("Flagged emails",list.path("name").asText());return null;
        });
    }
    @Test void olderAccountArchiveWithoutProviderCollectionsStillRestores() throws Exception {
        var archive=source();var work=(ObjectNode)archive.path("work");
        work.remove("taskConnections");work.remove("taskLists");
        inputs.validate("AccountArchiveSnapshot",archive);
        UUID id=stage(archive);var review=store.transaction("b",()->importer().preview(id,false));
        assertTrue(review.body().path("canImport").asBoolean());
        store.transaction("b",()->importer().apply(id,false,review.etag()));
        store.transaction("b",()->{assertEquals(1,store.list("TASK").size());assertTrue(store.list("TASK_CONNECTION").isEmpty());return null;});
    }
    @Test void previewShowsValidatedLinksAndRestoreKeepsTaskHierarchyAndSessionLabels() throws Exception {
        var archive=source();var work=(ObjectNode)archive.path("work");var root=work.path("tasks").get(0);
        var child=((ObjectNode)root).deepCopy().put("id",UUID.randomUUID().toString()).put("parentTaskId",root.path("id").asText()).put("summary","Archive child");
        ((com.fasterxml.jackson.databind.node.ArrayNode)work.path("tasks")).add(child);
        ((ObjectNode)work.path("workSessions").get(0)).putArray("labelIds").add(work.path("activities").get(0).path("labelIds").get(0));
        UUID id=stage(archive);var before=work();var review=store.transaction("b",()->importer().preview(id,false));
        assertTrue(review.body().path("canImport").asBoolean());inputs.validate("AccountArchiveReview",review.body());
        assertEquals(object().put("projectClients",1L).put("taskProjects",2L).put("parentTasks",1L)
                .put("activityTasks",1L).put("activityProjects",1L).put("activityLabels",1L)
                .put("workSessionClients",1L).put("workSessionProjects",1L).put("workSessionLabels",1L).put("listConnections",0L)
                .put("externalReferenceLists",0L),review.body().path("relationships"));
        assertEquals(before,work());
        store.transaction("b",()->importer().apply(id,false,review.etag()));
        store.transaction("b",()->{
            var tasks=store.list("TASK");var restoredRoot=tasks.stream().filter(task->task.path("summary").asText().equals("Archive task")).findFirst().orElseThrow();
            var restoredChild=tasks.stream().filter(task->task.path("summary").asText().equals("Archive child")).findFirst().orElseThrow();
            assertNotEquals(root.path("id"),restoredRoot.path("id"));assertEquals(restoredRoot.path("id"),restoredChild.path("parentTaskId"));
            assertEquals(restoredRoot.path("projectId"),restoredChild.path("projectId"));
            assertEquals(store.list("ACTIVITY").getFirst().path("labelIds"),store.list("WORK_SESSION").getFirst().path("labelIds"));return null;
        });
    }
    @Test void staleReviewInvalidGraphsAndSourcePrivilegeFieldsCannotPartlyRestoreData() throws Exception {
        var archive=source();UUID id=stage(archive);var review=store.transaction("b",()->importer().preview(id,false));
        store.transaction("b",()->{store.service().createProject(new CreateProject("Peer edit"));return null;});var peer=work();
        assertEquals(412,assertThrows(ApiFailure.class,()->store.transaction("b",()->importer().apply(id,false,review.etag()))).status);assertEquals(peer,work());
        var newer=store.transaction("b",()->importer().preview(id,false));
        assertEquals(412,assertThrows(ApiFailure.class,()->store.transaction("b",()->importer().apply(id,false,review.etag()))).status);
        store.transaction("b",()->importer().apply(id,false,newer.etag()));assertEquals("UTC",work().path("profile").path("zoneId").asText());
        var invalid=archive.deepCopy().put("archiveId",UUID.randomUUID().toString());((ObjectNode)invalid.path("work").path("workSessions").get(0)).put("projectId",UUID.randomUUID().toString());UUID invalidId=stage(invalid);var unchanged=work();
        assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction("b",()->importer().preview(invalidId,false))).status);assertEquals(unchanged,work());
        var injected=archive.deepCopy();((ObjectNode)injected.path("account")).put("password_hash","must never become authority");UUID injectedId=stage(injected);
        assertEquals(400,assertThrows(ApiFailure.class,()->store.transaction("b",()->importer().preview(injectedId,false))).status);assertEquals(unchanged,work());
    }
    @Test void runningWorkSessionConflictsAreReviewedWithoutStoppingEitherTimer() throws Exception {
        var archive=source();((ObjectNode)archive.path("work").path("workSessions").get(0)).putNull("end");UUID id=stage(archive);
        var current=store.transaction("b",()->store.workSessionService().startWorkSession(new StartWorkSession(new OwnerId(store.owner()),"Current running session")));var before=work();
        var review=store.transaction("b",()->importer().preview(id,false));assertFalse(review.body().path("canImport").asBoolean());assertEquals(1,review.body().path("runningWorkSessions").asInt());
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction("b",()->importer().apply(id,false,review.etag()))).status);assertEquals(before,work());
        store.transaction("b",()->{assertEquals(current,store.workSessionService().activeWorkSession(new OwnerId(store.owner())).orElseThrow());return null;});
    }
}
