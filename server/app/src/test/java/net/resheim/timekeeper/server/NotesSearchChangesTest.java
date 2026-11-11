package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(NotesSearchChangesTest.Profile.class)
class NotesSearchChangesTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("searchchanges",
                Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:notes_search_changes_test"));}
    }
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private VaultCollection vault(){var vault=new VaultCollection(store,limits);vault.initialize();return vault;}
    private NotesSearchChanges changes(){return new NotesSearchChanges(store);}
    private List<JsonNode> since(NotesSearchChanges.Snapshot before){var now=changes().snapshot();return changes().page(now,before.generation(),NotesSearchChanges.MAX_BATCH);}
    private void upserted(List<JsonNode> events,UUID id){assertTrue(events.stream().anyMatch(event->event.path("entryId").asText().equals(id.toString())&&event.path("operation").asText().equals("UPSERT")));}
    @Test void choosingATitleWithoutMovingTheFileRefreshesSearchAndAnIdenticalRenameDoesNot(){
        store.transaction("search-explicit-title",()->{
            var vault=vault();byte[] body=bytes("# Imported heading\n");var entry=vault.put(new VaultPath("Chosen title.md"),null,true,body);var before=changes().snapshot();String tag=vault.tag(entry);
            var renamed=vault.renameTitle(entry,"Chosen title",vault.metadataTag(entry));upserted(since(before),VaultCollection.id(entry));
            assertEquals(VaultCollection.path(entry),VaultCollection.path(renamed));assertEquals(tag,vault.tag(renamed));assertArrayEquals(body,vault.read(renamed));
            before=changes().snapshot();vault.renameTitle(renamed,"Chosen title",vault.metadataTag(renamed));assertEquals(before,changes().snapshot());return null;
        });
    }

    @Test void ordinaryEqualSizeEditsMovesDeletionAndRecoveryAreDurableAndContentFree(){
        String owner="search-ordinary";
        UUID id=store.transaction(owner,()->{
            var vault=vault();vault.mkdir(new VaultPath("Notes"));vault.mkdir(new VaultPath("Archive"));
            var before=changes().snapshot();byte[] original=bytes("# OWNER_ONLY_SECRET_SENTINEL\n");
            var entry=vault.put(new VaultPath("Notes/Secret.md"),null,true,original);UUID identifier=VaultCollection.id(entry);upserted(since(before),identifier);
            String tag=vault.tag(entry);before=changes().snapshot();entry=vault.put(VaultCollection.path(entry),tag,false,original);
            assertEquals(before,changes().snapshot());assertEquals(tag,vault.tag(entry));
            before=changes().snapshot();entry=vault.put(VaultCollection.path(entry),tag,false,bytes("# OWNER_ONLY_SECRET_REPLACED\n"));upserted(since(before),identifier);
            before=changes().snapshot();tag=vault.tag(entry);entry=vault.move(entry,new VaultPath("Archive/Secret.md"),tag);
            upserted(since(before),identifier);assertEquals(tag,vault.tag(entry));
            before=changes().snapshot();vault.delete(entry,vault.tag(entry));
            assertTrue(since(before).stream().anyMatch(event->event.path("entryId").asText().equals(identifier.toString())&&event.path("operation").asText().equals("DELETE")));
            before=changes().snapshot();entry=vault.get(identifier);vault.restore(entry,vault.tag(entry));upserted(since(before),identifier);
            for(var event:store.list(NotesSearchChanges.CHANGE))assertFalse(event.toString().contains("OWNER_ONLY_SECRET"));
            return identifier;
        });
        store.transaction("search-other-owner",()->{
            vault();assertThrows(ApiFailure.class,()->new VaultIndex(store).get(id));
            assertTrue(store.list(NotesSearchChanges.CHANGE).stream().noneMatch(event->event.path("entryId").asText().equals(id.toString())));return null;
        });
    }
    @Test void rolledBackWritesAndPreviewSavepointsCannotAdvanceCommittedSearchState(){
        String owner="search-rollback";
        var before=store.transaction(owner,()->{var vault=vault();vault.mkdir(new VaultPath("Notes"));return changes().snapshot();});
        assertThrows(ApiFailure.class,()->store.transaction(owner,()->{vault().put(new VaultPath("Notes/Rollback.md"),null,true,bytes("rolled back"));throw ApiFailure.validation();}));
        store.transaction(owner,()->{
            assertEquals(before,changes().snapshot());assertNull(new VaultIndex(store).at(new VaultPath("Notes/Rollback.md"),false));
            try{
                var connection=store.currentConnection();var point=connection.setSavepoint();
                new VaultState(store,limits).resetForImport();vault().put(new VaultPath("Notes/Preview.md"),null,true,bytes("preview"));
                assertNotEquals(before.epoch(),changes().snapshot().epoch());connection.rollback(point);connection.releaseSavepoint(point);
            }catch(java.sql.SQLException failure){throw new AssertionError(failure);}
            assertEquals(before,changes().snapshot());assertNull(new VaultIndex(store).at(new VaultPath("Notes/Preview.md"),false));return null;
        });
    }
    @Test void nativeJournalWritersArchivesAndRootMovesUseTheSameReplayStream(){
        store.transaction("search-native",()->{
            var vault=vault();var before=changes().snapshot();var day=new DayNotes(store);var date=LocalDate.of(2026,11,2);
            day.saveContent(date,etag(day.getContent(date)),object().put("body","Day content"));
            var dayEntry=vault.get(new VaultPath("journal/2026-11-02.md"));upserted(since(before),VaultCollection.id(dayEntry));
            before=changes().snapshot();day.restore(array().add(object().put("date","2026-11-03").put("text","").put("body","Restored day body")));
            upserted(since(before),VaultCollection.id(vault.get(new VaultPath("journal/2026-11-03.md"))));
            var weeks=new WeekNotes(store);var week=WeekNotes.week("2026-W45");before=changes().snapshot();
            weeks.save(week,etag(weeks.get(week)),object().put("body","Weekly content"));
            upserted(since(before),VaultCollection.id(vault.get(new VaultPath("journal/2026-W45.md"))));
            var events=new EventNotes(store);var key=new EventNotes.Key(UUID.randomUUID(),"a".repeat(64));
            var event=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event());
            event.set("event",object().put("uid","fixture").put("recurrenceId","").put("title","Event title"));before=changes().snapshot();
            events.saveDocument(key,etag(events.get(key)),bytes("---\ntimekeeper-event: "+canonical(event)+"\n---\nEvent body\n"));
            upserted(since(before),VaultCollection.id(vault.get(new VaultPath("events/"+key.filename()))));
            before=changes().snapshot();var settings=new VaultSettings(store);
            vault.configure(etag(settings.get()),object().put("journalFolder","Journal"));
            upserted(since(before),VaultCollection.id(dayEntry));assertTrue(since(before).stream().anyMatch(value->value.path("operation").asText().equals("SCOPE")));
            before=changes().snapshot();var labels=new DayLabels(store);labels.save(date,etag(labels.get(date)),"Independent label");assertEquals(before,changes().snapshot());
            return null;
        });
    }
    @Test void folderSubtreesAndVisibilitySettingsInvalidateWithoutIndexingBinaryFiles(){
        store.transaction("search-subtree",()->{
            var vault=vault();vault.mkdir(new VaultPath("Notes"));vault.mkdir(new VaultPath("Notes/Nested"));
            var first=vault.put(new VaultPath("Notes/One.md"),null,true,bytes("one"));var second=vault.put(new VaultPath("Notes/Nested/Two.md"),null,true,bytes("two"));
            var before=changes().snapshot();vault.put(new VaultPath("Notes/Nested/file.bin"),null,true,new byte[]{1,2,3});assertEquals(before,changes().snapshot());
            var folder=vault.get(new VaultPath("Notes"));vault.move(folder,new VaultPath("Moved"),vault.metadataTag(folder));var moved=since(before);
            upserted(moved,VaultCollection.id(first));upserted(moved,VaultCollection.id(second));
            var settings=new VaultSettings(store);before=changes().snapshot();
            settings.save(etag(settings.get()),object().put("hideJournalFolder",true));assertTrue(changes().snapshot().generation()>before.generation());
            before=changes().snapshot();settings.save(etag(settings.get()),object().put("hideJournalFolder",true));assertEquals(before,changes().snapshot());
            assertThrows(ApiFailure.class,()->vault.put(new VaultPath("Moved/One.md"),"invalid-etag",false,bytes("rejected")));
            assertEquals(before,changes().snapshot());return null;
        });
    }
    @Test void boundedIndependentReplayRejectsExpiredHistoryAndImportEpochs(){
        store.transaction("search-history",()->{
            var history=new NotesSearchChanges(store,3);var start=history.snapshot();
            for(int i=0;i<5;i++)history.scopeChanged();
            var current=history.snapshot();assertThrows(NotesSearchChanges.HistoryExpired.class,()->history.page(current,start.generation(),2));
            var page=history.page(current,current.historyFloor(),2);assertEquals(2,page.size());
            assertEquals(current.historyFloor()+1,page.getFirst().path("generation").asLong());
            // Reading a replay page consumes nothing: another replica gets it too.
            assertEquals(page,new NotesSearchChanges(store).page(current,current.historyFloor(),2));
            var last=history.page(current,page.getLast().path("generation").asLong(),2);assertEquals(1,last.size());
            history.reset();assertNotEquals(current.epoch(),history.snapshot().epoch());assertTrue(store.list(NotesSearchChanges.CHANGE).isEmpty());
            assertThrows(NotesSearchChanges.HistoryExpired.class,()->history.page(current,current.generation(),1));return null;
        });
    }
    @Test void sqlReplayOrderingCrossesUuidSequenceBoundaries(){
        store.transaction("search-ordering",()->{
            var history=changes();history.snapshot();
            for(long start:List.of(65_533L,4_294_967_293L,281_474_976_710_653L)){
                var state=(ObjectNode)store.find(NotesSearchChanges.STATE,NotesSearchChanges.ID).orElseThrow();state.put("generation",start).put("historyFloor",start);
                store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=?",state.toString(),store.owner(),NotesSearchChanges.STATE,NotesSearchChanges.ID);
                for(int i=0;i<4;i++)history.scopeChanged();
                var snapshot=history.snapshot();var first=history.page(snapshot,start,2);var second=history.page(snapshot,start+2,2);
                assertEquals(List.of(start+1,start+2,start+3,start+4),java.util.stream.Stream.concat(first.stream(),second.stream()).map(value->value.path("generation").asLong()).toList());
            }
            return null;
        });
    }
}
