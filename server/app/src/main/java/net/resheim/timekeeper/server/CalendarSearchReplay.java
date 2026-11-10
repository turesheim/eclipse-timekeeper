package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.uuid;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A bounded rebuild of finite feed definitions followed by actual saved event notes. */
final class CalendarSearchReplay implements NotesIndexReplay {
    private final NotesLuceneIndex index;
    private final CalendarSearchSources sources;
    private final NotesSearchSources.State target;
    private final String owner;
    private final LinkedHashMap<UUID,CalendarSearchSources.Feed> feeds=new LinkedHashMap<>(8,.75f,true);
    private List<UUID> subscriptions;
    private ZoneId zone;
    private int subscription;
    private CalendarSearchSources.Feed current;
    private Iterator<ObjectNode> definitions;
    private UUID afterNote;
    private CalendarSearchReplay(NotesLuceneIndex index,CalendarSearchSources sources,NotesSearchSources.State state){this.index=index;this.sources=sources;target=state;owner=state.identity().owner();}
    static CalendarSearchReplay open(Path path,CalendarSearchSources sources,NotesSearchSources.State state)throws IOException{
        return new CalendarSearchReplay(new NotesLuceneIndex(path,state.identity(),new NotesLuceneIndex.Watermark(state.changes().generation(),false,null)),sources,state);
    }
    public NotesLuceneIndex index(){return index;}
    public boolean caughtUp(NotesSearchSources.State state){var saved=index.watermark();return index.identity().equals(state.identity())&&saved.ready()&&saved.generation()==state.changes().generation();}
    private void current()throws IOException{
        var state=sources.state(owner);if(!target.identity().equals(state.identity())||target.changes().generation()!=state.changes().generation()||index.watermark().generation()!=target.changes().generation())throw new NotesLuceneIndex.RebuildRequired();
    }
    private CalendarSearchSources.Feed feed(UUID id){
        if(feeds.containsKey(id))return feeds.get(id);
        var value=sources.feed(owner,id);feeds.put(id,value);if(feeds.size()>4)feeds.remove(feeds.keySet().iterator().next());return value;
    }
    public synchronized NotesSearchReplay.Step advance(NotesSearchReplay.Budget budget)throws IOException{
        current();if(caughtUp(target))return new NotesSearchReplay.Step(true,target.changes().generation(),target.changes().generation(),0);
        if(subscriptions==null){subscriptions=sources.subscriptions(owner);zone=sources.zone(owner);}
        long started=System.nanoTime(),bytes=0;int records=0;boolean ready=false;
        while(records<budget.records()&&bytes<budget.bytes()&&System.nanoTime()-started<TimeUnit.MILLISECONDS.toNanos(budget.milliseconds())){
            if(Thread.currentThread().isInterrupted())throw new NotesLuceneIndex.Unavailable();
            if(definitions!=null&&definitions.hasNext()){
                var source=sources.definition(current,definitions.next(),zone,target.changes().generation());index.put(source);bytes+=source.byteLength();records++;continue;
            }
            if(subscription<subscriptions.size()){
                current=feed(subscriptions.get(subscription++));definitions=current==null?Collections.emptyIterator():current.calendar().eventDefinitions(zone).iterator();records++;continue;
            }
            var rows=sources.notes(owner,afterNote,Math.min(budget.records()-records,NotesSearchChanges.MAX_BATCH));
            if(rows.isEmpty()){ready=true;break;}
            for(var row:rows){
                var source=sources.note(owner,row,feed(uuid(row.path("subscriptionId"))),zone);
                if(source!=null){
                    index.put(source);bytes+=source.byteLength();var event=source.calendar().path("event");
                    if(!event.path("seriesDefinition").asBoolean())index.remove(CalendarSearchSources.resultId(uuid(event.path("subscriptionId")),event.path("definitionId").asText()));
                }
                afterNote=uuid(row.path("id"));records++;
                if(records>=budget.records()||bytes>=budget.bytes()||System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(budget.milliseconds()))break;
            }
        }
        current();index.commit(new NotesLuceneIndex.Watermark(target.changes().generation(),ready,null));
        return new NotesSearchReplay.Step(ready,target.changes().generation(),target.changes().generation(),records);
    }
    public void close()throws IOException{index.close();}
}
