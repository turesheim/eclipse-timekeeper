package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.TaskAttachment;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;

@QuarkusTest @TestProfile(AccountArchiveFilesTest.Profile.class)
class AccountArchiveFilesTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("files","jdbc:h2:mem:account_archive_files");}
    }
    @Inject ServerStore store;
    @Inject AccountArchive archives;
    @Inject TokenRegistry tokens;
    @Inject ContractInputs inputs;

    @Test void largeNotesAndAttachmentsStayOutsideJsonAndRetainExactFiles() throws Exception {
        var principal=tokens.authenticate("Bearer test-a");String day="ø".repeat(100000),week="w".repeat(200000);
        byte[] asset=new byte[JournalAssets.MAX_BYTES],taskBytes=new byte[TaskAttachment.MAX_BYTES];new Random(467).nextBytes(asset);new Random(468).nextBytes(taskBytes);
        UUID attachmentId=UUID.randomUUID();
        store.transaction("a",()->{
            var notes=new DayNotes(store);var date=DayNotes.date("2026-10-27");notes.saveDocument(date,etag(notes.getContent(date)),day.getBytes(StandardCharsets.UTF_8));
            var empty=DayNotes.date("2026-10-28");notes.saveDocument(empty,etag(notes.getContent(empty)),new byte[0]);
            var removed=DayNotes.date("2026-10-29");notes.saveDocument(removed,etag(notes.getContent(removed)),"deleted".getBytes(StandardCharsets.UTF_8));notes.delete(removed,etag(notes.get(removed)));
            var weeks=new WeekNotes(store);var key=WeekNotes.week("2026-W44");weeks.saveDocument(key,etag(weeks.get(key)),week.getBytes(StandardCharsets.UTF_8));
            new JournalAssets(store).save("large.bin",null,asset);
            new JournalAssets(store).save("tasks",null,new byte[]{1});
            var task=store.service().createTask(new CreateTask(Optional.empty(),Optional.empty(),"Task attachment",Optional.empty(),Set.of()));
            store.save("TASK_ATTACHMENT",new TaskAttachment(attachmentId,task.id().value(),"large task.bin","application/octet-stream",taskBytes.length,ArchiveJournalFiles.digest(taskBytes),Base64.getEncoder().encodeToString(taskBytes),Optional.empty(),Optional.empty(),0));return null;
        });
        try(var download=archives.download(principal);var zip=new ZipFile(download.file().toFile())){
            assertTrue(zip.getEntry("archive.json").getSize()<20000);
            assertEquals(day,new String(zip.getInputStream(zip.getEntry("2026-10-27.md")).readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(0,zip.getEntry("2026-10-28.md").getSize());assertNull(zip.getEntry("2026-10-29.md"));
            assertEquals(week,new String(zip.getInputStream(zip.getEntry("2026-W44.md")).readAllBytes(),StandardCharsets.UTF_8));
            assertArrayEquals(asset,zip.getInputStream(zip.getEntry("assets/large.bin")).readAllBytes());
            assertArrayEquals(taskBytes,zip.getInputStream(zip.getEntry(ArchiveJournalFiles.taskPath(attachmentId.toString()))).readAllBytes());
            assertArrayEquals(new byte[]{1},zip.getInputStream(zip.getEntry("assets/tasks")).readAllBytes());
            var snapshot=AccountArchiveInput.read(download.file(),"application/zip");assertEquals(6,snapshot.path("journalFiles").size());inputs.validate("AccountArchiveSnapshot",snapshot);
        }
    }
    @Test void eventNotesExportAndImportWithSafeStableNamesAndExactMarkdown() throws Exception {
        var key=new EventNotes.Key(UUID.randomUUID(),"ab".repeat(32));String body="# Ø\r\n\r\n<https://example.org/a?q=1>  \r\n---\r\n";
        var value=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event()).put("body",body);
        value.set("event",object().put("uid","../CON: meeting?*").put("recurrenceId","2026-10-27T10:00:00Z").put("title","../CON: Meeting / \"?*"));
        byte[] bytes=EventNotes.document(value);assertTrue(key.filename().matches("[a-z0-9-]+\\.md"));assertEquals(body,EventNotes.parse(key.filename(),bytes).body());
        store.transaction("a",()->{var notes=new EventNotes(store);notes.saveDocument(key,etag(notes.get(key)),bytes);return null;});
        try(var download=archives.download(tokens.authenticate("Bearer test-a"));var zip=new ZipFile(download.file().toFile())){
            assertArrayEquals(bytes,zip.getInputStream(zip.getEntry(key.filename())).readAllBytes());
            var snapshot=AccountArchiveInput.read(download.file(),"application/zip");inputs.validate("AccountArchiveSnapshot",snapshot);assertEquals(1,AccountArchiveInput.counts(snapshot).path("eventNotes").asInt());
            store.transaction("b",()->{ArchiveJournalFiles.restore(download.file(),snapshot.path("journalFiles"),store,true);assertArrayEquals(bytes,new EventNotes(store).document(key));return null;});
            store.transaction("b",()->{ArchiveJournalFiles.restore(download.file(),snapshot.path("journalFiles"),store,true);return null;});
            store.transaction("b",()->{var notes=new EventNotes(store);notes.saveDocument(key,etag(notes.get(key)),EventNotes.document(value.deepCopy().put("body","Keep newer work")));return null;});
            assertThrows(ApiFailure.class,()->store.transaction("b",()->{ArchiveJournalFiles.restore(download.file(),snapshot.path("journalFiles"),store,true);return null;}));
        }
        assertThrows(ApiFailure.class,()->EventNotes.parse("../"+key.filename(),bytes));
        assertThrows(ApiFailure.class,()->EventNotes.parse(key.filename(),new byte[]{(byte)0xff}));
        assertThrows(ApiFailure.class,()->EventNotes.parse(new EventNotes.Key(UUID.randomUUID(),key.event()).filename(),bytes));
    }

}
