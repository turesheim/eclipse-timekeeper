package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultCatalogTest.Profile.class)
class VaultCatalogTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("catalog",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_catalog_test",
            "timekeeper.notes.max-file-bytes","1000","timekeeper.notes.max-markdown-bytes","1000","timekeeper.notes.max-total-bytes","1000","timekeeper.notes.max-entries","8"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Test void existingAndNewJournalInterfacesShareTheIndexAndRetainedDeletionBytes(){
        store.transaction("journal",()->{
            var date=DayNotes.date("2026-10-30");var notes=new DayNotes(store);var labels=new DayLabels(store);
            labels.save(date,etag(labels.get(date)),"Day label");
            byte[] original="---\r\nunknown: true\r\n---\r\nBody  \r\n".getBytes(StandardCharsets.UTF_8);
            notes.saveDocument(date,etag(notes.getContent(date)),original);
            var catalog=new VaultCatalog(store,limits);catalog.initialize();
            var index=new VaultIndex(store);var entry=index.at(new VaultPath("journal/2026-10-30.md"),false);UUID id=uuid(entry.path("id"));
            var state=new VaultState(store,limits);assertEquals(original.length,state.get().path("totalBytes").asLong());
            assertEquals(4,state.get().path("entries").asInt());String generation=etag(state.get());
            catalog.initialize();assertEquals(generation,etag(state.get()));
            labels.save(date,etag(labels.get(date)),"Renamed label");assertEquals(generation,etag(state.get()));
            notes.saveDocument(date,etag(notes.getContent(date)),original);assertEquals(generation,etag(state.get()));
            notes.deleteContent(date,etag(notes.getContent(date)));
            var deleted=index.get(id);assertTrue(deleted.path("deleted").asBoolean());assertNotEquals(generation,etag(state.get()));
            assertEquals(original.length,state.get().path("totalBytes").asLong());
            assertArrayEquals(original,new VaultRecycle(store).read(id,notes.getContent(date).path("version").asLong()));
            assertEquals("Renamed label",labels.get(date).path("text").asText());
            notes.saveDocument(date,etag(notes.getContent(date)),original);
            assertEquals(id.toString(),index.at(new VaultPath("journal/2026-10-30.md"),false).path("id").asText());
            assertFalse(index.get(id).path("deleted").asBoolean());assertTrue(store.find(VaultRecycle.TYPE,id).isEmpty());
            var tomorrow=date.plusDays(1);notes.saveContent(tomorrow,etag(notes.getContent(tomorrow)),object().put("body","New note"));
            assertNotNull(index.at(new VaultPath("journal/2026-10-31.md"),false));return null;
        });
    }
    @Test void deletingNeverCreatedNativeNotesDoesNotInventUnrecoverableTrash(){
        store.transaction("missing",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();var date=DayNotes.date("2026-11-03");var notes=new DayNotes(store);notes.deleteContent(date,etag(notes.getContent(date)));var weeks=new WeekNotes(store);var week=WeekNotes.week("2026-W45");weeks.delete(week,etag(weeks.get(week)));
            assertEquals(4,store.list(VaultIndex.ENTRY).size());assertTrue(store.list(VaultRecycle.TYPE).isEmpty());return null;
        });
    }
    @Test void aggregateQuotaRejectsExistingInterfaceWritesAtomically(){
        var date=DayNotes.date("2026-11-01");
        store.transaction("quota",()->{var notes=new DayNotes(store);notes.saveContent(date,etag(notes.getContent(date)),object().put("body","x".repeat(900)));new VaultCatalog(store,limits).initialize();return null;});
        var failure=assertThrows(ApiFailure.class,()->store.transaction("quota",()->{var notes=new DayNotes(store);var next=date.plusDays(1);return notes.saveContent(next,etag(notes.getContent(next)),object().put("body","x".repeat(200)));}));
        assertEquals(507,failure.status);
        store.transaction("quota",()->{assertEquals(900,new DayNotes(store).getContent(date).path("body").asText().length());assertEquals(900,new VaultState(store,limits).get().path("totalBytes").asLong());return null;});
    }
    @Test void originalPathReuseCannotOverwriteOrHideACanonicalNote(){
        var date=DayNotes.date("2026-11-02");
        store.transaction("collision",()->{
            var notes=new DayNotes(store);notes.saveContent(date,etag(notes.getContent(date)),object().put("body","Original"));new VaultCatalog(store,limits).initialize();
            notes.deleteContent(date,etag(notes.getContent(date)));
            var index=new VaultIndex(store);index.create(new VaultPath("journal/2026-11-02.md"),VaultIndex.Kind.MARKDOWN,VaultContent.TYPE,UUID.randomUUID(),"general");return null;
        });
        assertThrows(ApiFailure.class,()->store.transaction("collision",()->{var notes=new DayNotes(store);return notes.saveContent(date,etag(notes.getContent(date)),object().put("body","Recreate"));}));
        store.transaction("collision",()->{assertTrue(new DayNotes(store).getContent(date).path("deleted").asBoolean());return null;});
    }
}
