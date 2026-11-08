package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(NotesSearchSourcesTest.Profile.class)
class NotesSearchReplayTest {
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @TempDir Path temporary;
    private final NotesSearchReplay.Budget one=new NotesSearchReplay.Budget(1,8_000_000,1000);
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private VaultCollection vault(){var vault=new VaultCollection(store,limits);vault.initialize();new NotesSearchChanges(store).snapshot();return vault;}
    private NotesSearchSources sources(){return new NotesSearchSources(store);}
    private void create(String owner,int files){store.transaction(owner,()->{var vault=vault();for(int i=0;i<files;i++)vault.put(new VaultPath("File-"+i+".md"),null,true,bytes("# File "+i+"\n\nneedle body"));return null;});}
    private int catchUp(NotesSearchReplay replay)throws Exception{for(int step=1;step<=1000;step++){var progress=replay.advance(one);assertTrue(progress.records()<=1);assertNull(store.currentConnection());if(progress.ready())return step;}throw new AssertionError("Bounded replay did not finish");}
    private List<NotesLuceneIndex.Hit> search(NotesSearchReplay replay,String owner,String text)throws Exception{return replay.index().search(NotesSearchQuery.parse(text),sources().state(owner).changes().generation(),200,null,false,null,5000).items();}

    @Test void boundedBackfillReplaysConcurrentWritesIndependentlyOnTwoReplicas()throws Exception{
        String owner="search-replay-two-replicas";create(owner,12);var initial=sources().state(owner);
        try(var a=NotesSearchReplay.open(temporary.resolve("a"),sources(),initial);var b=NotesSearchReplay.open(temporary.resolve("b"),sources(),initial)){
            assertFalse(a.advance(one).ready());assertThrows(NotesLuceneIndex.Unavailable.class,()->search(a,owner,"needle"));
            store.transaction(owner,()->{var vault=vault();vault.put(new VaultPath("Added.md"),null,true,bytes("needle added during backfill"));var day=new DayNotes(store);var date=DayNotes.date("2026-11-08");day.saveContent(date,etag(day.getContent(date)),object().put("body","needle native writer"));return null;});
            assertTrue(catchUp(a)>1);assertTrue(catchUp(b)>1);assertEquals(14,search(a,owner,"needle").size());
            assertEquals(search(a,owner,"needle").stream().map(NotesLuceneIndex.Hit::id).toList(),search(b,owner,"needle").stream().map(NotesLuceneIndex.Hit::id).toList());
            store.transaction(owner,()->{var vault=vault();var entry=vault.get(new VaultPath("File-0.md"));vault.put(VaultCollection.path(entry),vault.tag(entry),false,bytes("replacement phrase"));return null;});
            assertFalse(a.caughtUp(sources().state(owner)));catchUp(a);catchUp(b);assertEquals(13,search(a,owner,"needle").size());assertEquals(1,search(b,owner,"\"replacement phrase\"").size());
        }
    }
    @Test void restartResumesCommittedBackfillAndRolledBackWritesCannotAppear()throws Exception{
        String owner="search-replay-restart";create(owner,9);var initial=sources().state(owner);Path path=temporary.resolve("resume");NotesLuceneIndex.Watermark checkpoint;
        try(var replay=NotesSearchReplay.open(path,sources(),initial)){assertFalse(replay.advance(one).ready());checkpoint=replay.index().watermark();assertNotNull(checkpoint.afterEntry());}
        assertThrows(ApiFailure.class,()->store.transaction(owner,()->{vault().put(new VaultPath("Rollback.md"),null,true,bytes("rollback private text"));throw ApiFailure.validation();}));
        try(var replay=NotesSearchReplay.open(path,sources(),sources().state(owner))){
            assertEquals(checkpoint,replay.index().watermark());catchUp(replay);assertEquals(9,search(replay,owner,"needle").size());assertTrue(search(replay,owner,"rollback").isEmpty());
            assertEquals(0,replay.advance(one).records());
        }
    }
    @Test void expiredHistoryAndNewEpochRequireFreshCaches()throws Exception{
        String owner="search-replay-expired";create(owner,3);var initial=sources().state(owner);
        try(var old=NotesSearchReplay.open(temporary.resolve("old"),sources(),initial)){
            catchUp(old);store.transaction(owner,()->{var history=new NotesSearchChanges(store,1);history.scopeChanged();history.scopeChanged();history.scopeChanged();return null;});
            assertThrows(NotesLuceneIndex.RebuildRequired.class,()->old.advance(one));
            try(var rebuilt=NotesSearchReplay.open(temporary.resolve("new"),sources(),sources().state(owner))){
                catchUp(rebuilt);assertEquals(3,search(rebuilt,owner,"needle").size());
                store.transaction(owner,()->{new NotesSearchChanges(store).reset();return null;});assertThrows(NotesLuceneIndex.RebuildRequired.class,()->rebuilt.advance(one));
            }
        }
    }
}
