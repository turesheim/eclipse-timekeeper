package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class NotesSearchCursorTest {
    private final byte[] key=new byte[32];
    private final Instant now=Instant.parse("2026-11-08T14:00:00Z");
    private final NotesSearchQuery.Parsed query=NotesSearchQuery.parse("private query");
    private final UUID collection=UUID.randomUUID(),epoch=UUID.randomUUID();
    private NotesSearchSources.State state(String owner,long generation,boolean configuration,String journal,String restore){return new NotesSearchSources.State(new NotesLuceneIndex.Identity(owner,collection,epoch,restore),new NotesSearchChanges.Snapshot(epoch,generation,0),configuration,journal);}
    private void stale(Runnable work){var failure=assertThrows(ApiFailure.class,work::run);assertEquals(412,failure.status);assertEquals("NOTES_SEARCH_SNAPSHOT_EXPIRED",failure.code);assertFalse(failure.getMessage().contains("private query"));}

    @Test void signedPositionsContinueOnAnotherReplicaWithoutPhysicalDocumentIds(){
        var state=state("alice",7,false,null,"restore-one");var a=new NotesSearchCursor(key,"alice",now);var b=new NotesSearchCursor(key,"alice",now.plusSeconds(3));
        var first=a.read(null,state,query);String snapshot=a.write(first,state,query);assertNull(b.read(snapshot,state,query).after());
        var position=new NotesLuceneIndex.Position(6.4f,"notes/øving.md",UUID.randomUUID());String next=a.write(first.position(position),state,query);
        assertEquals(first.position(position),b.read(next,state,query));assertFalse(next.contains("private query"));assertFalse(first.toString().contains("øving"));
        assertEquals(first.position(position),new NotesSearchCursor(key,"alice",now.minusSeconds(3)).read(next,state,query));
        assertEquals(snapshot,b.write(b.read(next,state,query).position(null),state,query));
    }
    @Test void ownerQueryGenerationVisibilityAndRestoreChangesExpireContinuations(){
        var original=state("alice",7,false,null,"restore-one");var cursor=new NotesSearchCursor(key,"alice",now);String token=cursor.write(cursor.read(null,original,query),original,query);
        for(var state:List.of(state("alice",8,false,null,"restore-one"),state("alice",7,true,null,"restore-one"),state("alice",7,false,"journal","restore-one"),state("alice",7,false,null,"restore-two")))stale(()->cursor.read(token,state,query));
        stale(()->cursor.read(token,original,NotesSearchQuery.parse("another query")));
        var changedCollection=new NotesSearchSources.State(new NotesLuceneIndex.Identity("alice",UUID.randomUUID(),epoch,"restore-one"),original.changes(),false,null);
        var changedEpoch=new NotesSearchSources.State(new NotesLuceneIndex.Identity("alice",collection,UUID.randomUUID(),"restore-one"),original.changes(),false,null);
        stale(()->cursor.read(token,changedCollection,query));stale(()->cursor.read(token,changedEpoch,query));
        stale(()->new NotesSearchCursor(key,"bob",now).read(token,state("bob",7,false,null,"restore-one"),query));
        byte[] other=key.clone();other[0]=1;stale(()->new NotesSearchCursor(other,"alice",now).read(token,original,query));
    }
    @Test void malformedTamperedAndExpiredTokensFailBeforeSearch(){
        var state=state("alice",7,false,null,null);var cursor=new NotesSearchCursor(key,"alice",now);String token=cursor.write(cursor.read(null,state,query),state,query);
        for(String invalid:List.of("","x".repeat(4097),"not-a-token","e30.invalid",token.substring(0,12)+(token.charAt(12)=='A'?'B':'A')+token.substring(13)))stale(()->cursor.read(invalid,state,query));
        assertNotNull(new NotesSearchCursor(key,"alice",now.plusSeconds(599)).read(token,state,query));
        stale(()->new NotesSearchCursor(key,"alice",now.plusSeconds(600)).read(token,state,query));
    }
    @Test void scoreAndUnicodeSortKeysRoundTripExactlyWithinThePathBudget(){
        var state=state("alice",3,false,null,null);var cursor=new NotesSearchCursor(key,"alice",now);var frame=cursor.read(null,state,query);
        String path=("ø".repeat(120)+"/").repeat(4)+"note.md";var position=new NotesLuceneIndex.Position(Float.intBitsToFloat(0x40ccefff),path,UUID.randomUUID());
        String token=cursor.write(frame.position(position),state,query);assertTrue(token.length()<=4096);assertEquals(position,cursor.read(token,state,query).after());
        assertThrows(IllegalArgumentException.class,()->new NotesLuceneIndex.Position(Float.NaN,path,UUID.randomUUID()));
    }
}
