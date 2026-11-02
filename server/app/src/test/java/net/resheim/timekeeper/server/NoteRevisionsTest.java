package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(NoteRevisionsTest.Profile.class)
class NoteRevisionsTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("revisions",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:note_revisions_test"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Test void allNativeAndVaultWritesRetainExactContentWithoutRestoringIndependentLabels(){
        store.transaction("revision-owner",()->{
            var history=new NoteRevisions(store);var notes=new DayNotes(store);var day=DayNotes.date("2026-11-02");
            byte[] original="---\r\nunknown: keep\r\n---\r\nBefore.\r\n".getBytes(StandardCharsets.UTF_8);
            notes.saveDocument(day,etag(notes.getContent(day)),original);notes.saveContent(day,etag(notes.getContent(day)),object().put("body","After."));
            var labels=new DayLabels(store);labels.save(day,etag(labels.get(day)),"Independent label");
            var ref=history.reference(DayNotes.TYPE,DayNotes.id(day));var revisions=history.list(ref).path("items");assertEquals(1,revisions.size());
            var saved=history.get(ref,uuid(revisions.get(0).path("id")));assertEquals(new String(original,StandardCharsets.UTF_8),saved.path("markdown").asText());assertEquals("Before.\r\n",saved.path("body").asText());assertEquals("Independent label",labels.get(day).path("text").asText());
            var weeks=new WeekNotes(store);var week=WeekNotes.week("2026-W45");weeks.save(week,etag(weeks.get(week)),object().put("body","Week one"));weeks.save(week,etag(weeks.get(week)),object().put("body","Week two"));assertEquals(1,history.list(history.reference(WeekNotes.TYPE,WeekNotes.id(week))).path("items").size());
            var vault=new VaultCollection(store,limits);vault.initialize();vault.mkdir(new VaultPath("Notes"));var file=vault.put(new VaultPath("Notes/History.md"),null,true,"First\r\n".getBytes(StandardCharsets.UTF_8));
            String stale=vault.tag(file);file=vault.put(new VaultPath("Notes/History.md"),stale,false,"Second\n".getBytes(StandardCharsets.UTF_8));var source=VaultSources.fromEntry(file);assertEquals(1,history.list(source).path("items").size());
            assertEquals(412,assertThrows(ApiFailure.class,()->vault.put(new VaultPath("Notes/History.md"),stale,false,"Stale".getBytes(StandardCharsets.UTF_8))).status);assertEquals(1,history.list(source).path("items").size());
            for(int index=0;index<25;index++)file=vault.put(new VaultPath("Notes/History.md"),vault.tag(file),false,("Version "+index).getBytes(StandardCharsets.UTF_8));
            var retained=history.list(source).path("items");assertEquals(NoteRevisions.PER_NOTE,retained.size());assertTrue(retained.get(0).path("sequence").asLong()>retained.get(1).path("sequence").asLong());return null;
        });
        store.transaction("other-owner",()->{assertEquals(404,assertThrows(ApiFailure.class,()->new NoteRevisions(store).reference(DayNotes.TYPE,DayNotes.id(DayNotes.date("2026-11-02")))).status);return null;});
    }
}
