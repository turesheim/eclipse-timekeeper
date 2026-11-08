package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.uuid;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded, idempotent replica work. No canonical transaction encloses Lucene work. */
final class NotesSearchReplay implements AutoCloseable {
    record Budget(int records,long bytes,long milliseconds){
        Budget{if(records<1||records>256||bytes<1||bytes>64_000_000||milliseconds<1||milliseconds>5000)throw new IllegalArgumentException("Invalid search replay budget");}
    }
    record Step(boolean ready,long generation,long target,int records){}
    private final NotesLuceneIndex index;
    private final NotesSearchSources sources;
    private final NotesLuceneIndex.Identity identity;
    private NotesSearchReplay(NotesLuceneIndex index,NotesSearchSources sources){this.index=index;this.sources=sources;identity=index.identity();}
    static NotesSearchReplay open(Path path,NotesSearchSources sources,NotesSearchSources.State initial)throws IOException{
        return new NotesSearchReplay(new NotesLuceneIndex(path,initial.identity(),new NotesLuceneIndex.Watermark(initial.changes().generation(),false,null)),sources);
    }
    NotesLuceneIndex index(){return index;}
    boolean caughtUp(NotesSearchSources.State state){var saved=index.watermark();return identity.equals(state.identity())&&saved.ready()&&saved.generation()==state.changes().generation();}
    synchronized Step advance(Budget budget)throws IOException{
        long started=System.nanoTime(),spent=0;int records=0;var state=sources.state(identity.owner());var saved=index.watermark();long target=state.changes().generation();
        if(!identity.equals(state.identity())||saved.generation()>target||saved.generation()<state.changes().historyFloor())throw new NotesLuceneIndex.RebuildRequired();
        if(saved.ready()&&saved.generation()==target)return new Step(true,target,target,0);
        if(!saved.ready()){
            var entries=sources.entries(identity.owner(),saved.afterEntry(),budget.records());UUID after=saved.afterEntry();
            for(var entry:entries){
                if(Thread.currentThread().isInterrupted())throw new NotesLuceneIndex.Unavailable();
                UUID id=uuid(entry.path("id"));
                if(NotesSearchChanges.searchable(entry)){var source=sources.load(identity.owner(),id);if(source==null)index.remove(id);else{index.put(source);spent+=source.byteLength();}}
                after=id;records++;
                if(records>=budget.records()||spent>=budget.bytes()||expired(started,budget))break;
            }
            if(!entries.isEmpty()){
                index.commit(new NotesLuceneIndex.Watermark(saved.generation(),false,after));return new Step(false,saved.generation(),target,records);
            }
        }
        long applied=saved.generation();
        try{
            var changes=sources.changes(identity.owner(),state.changes(),applied,budget.records());
            for(var change:changes){
                if(Thread.currentThread().isInterrupted())throw new NotesLuceneIndex.Unavailable();
                long next=change.path("generation").asLong();if(next!=applied+1)throw new NotesLuceneIndex.RebuildRequired();
                String operation=change.path("operation").asText();
                if(!Set.of("UPSERT","DELETE","SCOPE").contains(operation))throw new NotesLuceneIndex.RebuildRequired();
                if(!operation.equals("SCOPE")){
                    UUID id=uuid(change.path("entryId"));var source=sources.load(identity.owner(),id);
                    if(source==null)index.remove(id);else{index.put(source);spent+=source.byteLength();}
                }
                applied=next;records++;
                if(records>=budget.records()||spent>=budget.bytes()||expired(started,budget))break;
            }
            if(changes.isEmpty()&&applied!=target)throw new NotesLuceneIndex.RebuildRequired();
        }catch(NotesSearchChanges.HistoryExpired expired){throw new NotesLuceneIndex.RebuildRequired();}
        boolean ready=saved.ready()||applied==target;
        index.commit(new NotesLuceneIndex.Watermark(applied,ready,saved.afterEntry()));return new Step(ready&&applied==target,applied,target,records);
    }
    private static boolean expired(long started,Budget budget){return System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(budget.milliseconds());}
    @Override public void close()throws IOException{index.close();}
}
