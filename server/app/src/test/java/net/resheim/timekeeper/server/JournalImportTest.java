package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

@QuarkusTest @TestProfile(JournalImportTest.Profile.class)
class JournalImportTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("journal","jdbc:h2:mem:journal_import");}
    }
    @Inject ServerStore store;
    private final UUID device=UUID.randomUUID();
    private static final String FILE="2026-10-25.md";
    @BeforeEach void reset(){
        io.quarkus.arc.ClientProxy.unwrap(store).stopMaintenance();
        for(String owner:List.of("a","b"))store.transaction(owner,()->{
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND (entity_type LIKE 'JOURNAL_%' OR entity_type='DAY_NOTE')",owner);return null;
        });
    }
    private UUID seal(long sequence,Map<String,String> texts){
        UUID batch=UUID.randomUUID();var files=new LinkedHashMap<String,byte[]>();texts.forEach((name,text)->files.put(name,text.getBytes(StandardCharsets.UTF_8)));
        store.transaction("a",()->new JournalImport(store).seal(batch,device,sequence,files));return batch;
    }
    private ObjectNode process(UUID batch){return store.transaction("a",()->{
        var importer=new JournalImport(store);ObjectNode result=null;
        for(var item:new JournalState(store).list(JOURNAL_ITEM))if(item.path("batchId").asText().equals(batch.toString()))result=importer.process(uuid(item.get("id")));
        return result;
    });}
    private ObjectNode note(){return store.transaction("a",()->new DayNotes(store).get(DayNotes.date("2026-10-25")));}
    private long revision(){return store.transaction("a",()->store.revision());}
    private void calendar(String body){store.transaction("a",()->{var notes=new DayNotes(store);var day=DayNotes.date("2026-10-25");return notes.save(day,etag(notes.get(day)),object().put("body",body));});}

    @Test void sealedWorkSurvivesNewImporterAndNoopMissingAndEmptyFilesAreSafe(){
        String body="\r\n# Søndag\r\n\r\n  - [ ] Task  \r\n";String source="---\r\ntimekeeper-label: Planning\r\nunknown: preserved at source\r\n---\r\n"+body;
        UUID batch=seal(1,Map.of(FILE,source));assertEquals("",note().path("body").asText());
        assertEquals(1,store.transaction("a",()->new JournalImport(store).pending().size()));
        long before=revision();assertEquals("IMPORTED",process(batch).path("state").asText());assertEquals(body,note().path("body").asText());assertEquals("Planning",note().path("text").asText());assertEquals(before+1,revision());
        long version=note().path("version").asLong(),after=revision();assertEquals("IMPORTED",process(batch).path("state").asText());
        assertEquals("UNCHANGED",process(seal(2,Map.of(FILE,source))).path("state").asText());assertEquals(version,note().path("version").asLong());assertEquals(after,revision());
        seal(3,Map.of());assertEquals(body,note().path("body").asText());
        assertEquals("IMPORTED",process(seal(4,Map.of(FILE,""))).path("state").asText());assertEquals("",note().path("text").asText());assertEquals("",note().path("body").asText());assertEquals(version+1,note().path("version").asLong());
        assertEquals("",store.transaction("b",()->new DayNotes(store).get(DayNotes.date("2026-10-25")).path("body").asText()));
        store.transaction("a",()->{assertTrue(store.list("WORK_SESSION").isEmpty());assertTrue(store.list("ACTIVITY").isEmpty());return null;});
    }
    @Test void firstCollisionIsPrivateAndResolutionRequiresBothFreshVersions(){
        calendar("private calendar");UUID batch=seal(1,Map.of(FILE,"private incoming"));long before=revision();
        var conflict=process(batch);assertEquals("CONFLICT",conflict.path("state").asText());assertFalse(conflict.toString().contains("private"));assertEquals(before,revision());assertEquals("private calendar",note().path("body").asText());
        UUID id=uuid(conflict.get("id"));var review=store.transaction("a",()->new JournalImport(store).candidate(id));assertEquals("private incoming",review.path("body").asText());
        assertEquals(404,assertThrows(ApiFailure.class,()->store.transaction("b",()->new JournalImport(store).candidate(id))).status);
        calendar("new calendar");assertEquals(412,assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalImport(store).resolve(id,conflict.path("candidateEtag").asText(),etag(review.path("current")),true))).status);
        var current=note();store.transaction("a",()->new JournalImport(store).resolve(id,conflict.path("candidateEtag").asText(),etag(current),false));assertEquals("new calendar",note().path("body").asText());
        assertEquals("UNCHANGED",process(seal(2,Map.of(FILE,"private incoming"))).path("state").asText());assertEquals("new calendar",note().path("body").asText());
        assertEquals("IMPORTED",process(seal(3,Map.of(FILE,"deliberate subsequent external edit"))).path("state").asText());
    }
    @Test void calendarEditsAreNotUndoneByUnchangedFilesAndLaterExternalEditsConflict(){
        process(seal(1,Map.of(FILE,"initial")));calendar("calendar edit");long before=revision();
        assertEquals("UNCHANGED",process(seal(2,Map.of(FILE,"initial"))).path("state").asText());assertEquals("calendar edit",note().path("body").asText());assertEquals(before,revision());
        var conflict=process(seal(3,Map.of(FILE,"external edit")));assertEquals("CONFLICT",conflict.path("state").asText());
        var current=note();store.transaction("a",()->new JournalImport(store).resolve(uuid(conflict.get("id")),conflict.path("candidateEtag").asText(),etag(current),true));assertEquals("external edit",note().path("body").asText());assertEquals(before+1,revision());
    }
    @Test void devicesHaveIndependentBaselinesAndLateOlderBatchesCannotReplay(){
        UUID older=seal(1,Map.of(FILE,"older")),newer=seal(2,Map.of(FILE,"newer"));process(newer);assertEquals("SUPERSEDED",process(older).path("state").asText());assertEquals("newer",note().path("body").asText());
        UUID batch=UUID.randomUUID(),other=UUID.randomUUID();store.transaction("a",()->new JournalImport(store).seal(batch,other,1,Map.of(FILE,"second device".getBytes(StandardCharsets.UTF_8))));
        assertEquals("CONFLICT",process(batch).path("state").asText());assertEquals("newer",note().path("body").asText());
        UUID identical=UUID.randomUUID();store.transaction("a",()->new JournalImport(store).seal(identical,other,2,Map.of(FILE,"newer".getBytes(StandardCharsets.UTF_8))));assertEquals("UNCHANGED",process(identical).path("state").asText());
        UUID update=UUID.randomUUID();store.transaction("a",()->new JournalImport(store).seal(update,other,3,Map.of(FILE,"accepted second device edit".getBytes(StandardCharsets.UTF_8))));assertEquals("IMPORTED",process(update).path("state").asText());
    }
    @Test void invalidFilesAreIndependentAndNeverChangeTheCorrespondingNote(){
        calendar("keep");UUID batch=seal(1,Map.of(FILE,"---\nprivate: [invalid\n---\n","2026-02-30.md","invalid date","2026-10-26.md","good"));
        store.transaction("a",()->{
            var importer=new JournalImport(store);var outcomes=importer.pending().stream().map(importer::process).toList();
            assertEquals(1,outcomes.stream().filter(item->item.path("state").asText().equals("IMPORTED")).count());assertEquals(2,outcomes.stream().filter(item->item.path("state").asText().equals("ERROR")).count());
            assertFalse(outcomes.toString().contains("private"));assertEquals("PROCESSED",new JournalState(store).required(JOURNAL_BATCH,batch).path("state").asText());
            assertEquals("good",new DayNotes(store).get(DayNotes.date("2026-10-26")).path("body").asText());return null;
        });assertEquals("keep",note().path("body").asText());
    }
    @Test void batchHandoffIsIdempotentAndUnsafeNamesCannotPartlySeal(){
        UUID batch=UUID.randomUUID();var files=Map.of(FILE,"body".getBytes(StandardCharsets.UTF_8));
        var first=store.transaction("a",()->new JournalImport(store).seal(batch,device,1,files));assertEquals(first.toString(),store.transaction("a",()->new JournalImport(store).seal(batch,device,1,files)).toString());
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalImport(store).seal(batch,device,2,files))).status);
        UUID unsafe=UUID.randomUUID();assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalImport(store).seal(unsafe,device,3,Map.of(FILE,new byte[0],"../2026-10-26.md",new byte[0])))).status);
        store.transaction("a",()->{assertTrue(new JournalState(store).find(JOURNAL_BATCH,unsafe).isEmpty());return null;});
    }
    @Test void anExplicitlyClearedCalendarNoteIsStillProtectedOnFirstImport(){
        calendar("previous");calendar("");long before=revision();assertEquals("CONFLICT",process(seal(1,Map.of(FILE,"must not restore"))).path("state").asText());
        assertEquals("",note().path("body").asText());assertEquals(before,revision());
    }
    @Test void rolledBackImportsLeaveBothPendingWorkAndCalendarVersionUntouched(){
        UUID batch=seal(1,Map.of(FILE,"recoverable"));long before=revision();
        assertThrows(IllegalStateException.class,()->store.transaction("a",()->{var importer=new JournalImport(store);importer.pending().forEach(importer::process);throw new IllegalStateException("simulate failure before commit");}));
        assertEquals("",note().path("body").asText());assertEquals(before,revision());assertEquals(1,store.transaction("a",()->new JournalImport(store).pending().size()));
        assertEquals("IMPORTED",process(batch).path("state").asText());assertEquals("recoverable",note().path("body").asText());assertEquals(before+1,revision());
    }
}
