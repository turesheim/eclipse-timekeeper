package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultArchiveTest.Profile.class)
class VaultArchiveTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("vault","jdbc:h2:mem:vault_archive_test");}}
    @Inject ServerStore store;
    @Inject AccountArchive archives;
    @Inject TokenRegistry tokens;
    @Inject ContractInputs inputs;
    @Inject VaultLimits limits;
    @Test void completeCollectionAndDeletedCanonicalNotesRoundTripWithRebuiltOwnerPrivateIndexes()throws Exception{
        tokens.authenticate("Bearer test-b");var principal=tokens.authenticate("Bearer test-a");
        byte[] document="---\r\nunknown: [x, true]\r\n---\r\n[[Wiki]]\r\n![[image.bin]]  \r\n".getBytes(StandardCharsets.UTF_8);byte[] binary={0,(byte)255,4};
        var identities=new HashMap<String,UUID>();var event=new EventNotes.Key(UUID.randomUUID(),"ab".repeat(32));
        store.transaction("a",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();vault.mkdir(new VaultPath("Notes"));
            var entry=vault.put(new VaultPath("Notes/Exact.md"),null,true,document);identities.put("note",VaultCollection.id(entry));
            entry=vault.put(new VaultPath("Notes/image.bin"),null,true,binary);identities.put("binary",VaultCollection.id(entry));
            entry=vault.put(new VaultPath("Notes/Deleted.md"),null,true,document);identities.put("trash",VaultCollection.id(entry));vault.delete(entry,vault.tag(entry));
            entry=vault.put(new VaultPath("journal/2026-10-30.md"),null,true,document);identities.put("day",VaultCollection.id(entry));
            var labels=new DayLabels(store);var date=DayNotes.date("2026-10-30");labels.save(date,etag(labels.get(date)),"Independent label");vault.delete(entry,vault.tag(entry));
            vault.put(new VaultPath("journal/2026-W44.md"),null,true,document);
            var events=new EventNotes(store);var value=object().put("subscriptionId",event.subscription().toString()).put("eventKey",event.event()).put("body","Event body");value.set("event",object().put("uid","uid").put("recurrenceId","2026-10-30T10:00:00Z").put("title","Meeting"));events.saveDocument(event,etag(events.get(event)),EventNotes.document(value));
            new JournalAssets(store).save("legacy.bin",null,binary);
            vault.configure(etag(new VaultSettings(store).get()),object().put("journalFolder","Private/Journal"));return null;
        });
        try(var download=archives.download(principal);var zip=new ZipFile(download.file().toFile())){
            var snapshot=AccountArchiveInput.read(download.file(),"application/zip");inputs.validate("AccountArchiveSnapshot",snapshot);
            assertEquals(AccountArchive.FORMAT,snapshot.path("format").asText());assertTrue(zip.getEntry("archive.json").getSize()<20000);
            assertFalse(snapshot.path("vault").toString().contains("ownerId"));assertFalse(snapshot.path("vault").toString().contains("data\""));
            store.transaction("b",()->{
                VaultArchive.prepare(snapshot.path("vault"),store);new DayLabels(store).restore(snapshot.path("dayLabels"));ArchiveJournalFiles.restore(download.file(),snapshot.path("journalFiles"),store,true);VaultArchive.restore(download.file(),snapshot.path("vault"),store);
                var vault=new VaultCollection(store,limits);assertArrayEquals(document,vault.read(vault.get(identities.get("note"))));assertArrayEquals(binary,vault.read(vault.get(identities.get("binary"))));
                assertArrayEquals(binary,JournalAssets.bytes(new JournalAssets(store).get("legacy.bin")));assertEquals("Private/Journal",new VaultSettings(store).get().path("journalFolder").asText());
                var deleted=vault.get(identities.get("trash"));assertTrue(deleted.path("deleted").asBoolean());vault.restore(deleted,vault.tag(deleted));assertArrayEquals(document,vault.read(vault.get(identities.get("trash"))));
                var day=vault.get(identities.get("day"));assertTrue(day.path("deleted").asBoolean());vault.restore(day,vault.tag(day));assertArrayEquals(document,new DayNotes(store).document(DayNotes.date("2026-10-30")));
                assertEquals("Independent label",new DayLabels(store).get(DayNotes.date("2026-10-30")).path("text").asText());assertEquals("Event body",new EventNotes(store).get(event).path("body").asText());return null;
            });
            assertThrows(ApiFailure.class,()->store.transaction("b",()->{VaultArchive.prepare(snapshot.path("vault"),store);return null;}));
        }
    }
}
