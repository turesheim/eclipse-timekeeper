package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Calendar snapshots copy owned SQL data; feed parsing and text extraction run after close. */
final class CalendarSearchSources extends NotesSearchSources {
    static final String SOURCE="CALENDAR_EVENT";
    static final NotesSearchService.Backend BACKEND=new NotesSearchService.Backend(){
        public String key(){return "calendar";}
        public NotesSearchSources sources(ServerStore store){return new CalendarSearchSources(store);}
        public NotesIndexReplay open(Path path,NotesSearchSources sources,State state)throws java.io.IOException{return CalendarSearchReplay.open(path,(CalendarSearchSources)sources,state);}
    };
    private final ServerStore store;
    CalendarSearchSources(ServerStore store){super(store);this.store=store;}
    @Override State state(){
        var notes=super.state();String zone=store.profile().path("zoneId").asText();
        UUID epoch=ImportLedger.identifier("calendar-search-epoch",notes.identity().epoch().toString(),zone);
        long generation=new CalendarSearchState(store).generation();
        return new State(new NotesLuceneIndex.Identity(store.owner(),notes.identity().collection(),epoch,store.restoreGeneration()),new NotesSearchChanges.Snapshot(epoch,generation,generation),notes.configuration(),notes.hiddenJournal());
    }
    @Override State state(String owner){return store.readSnapshot(owner,this::state);}
    ZoneId zone(String owner){return store.readSnapshot(owner,()->ZoneId.of(store.profile().path("zoneId").asText()));}
    List<UUID> subscriptions(String owner){return store.readSnapshot(owner,()->store.list(CalendarSubscriptions.SUB).stream().filter(CalendarSearchSources::visible).map(value->uuid(value.path("id"))).sorted().toList());}
    List<JsonNode> notes(String owner,UUID after,int limit){return store.readSnapshot(owner,()->store.resourcePage(EventNotes.TYPE,after,limit));}
    private static boolean visible(JsonNode subscription){return subscription.path("visible").asBoolean()&&subscription.path("includeEvents").asBoolean(true);}
    private static ObjectNode settings(JsonNode subscription){
        var value=object();for(String field:List.of("id","name","clientId","visible"))value.set(field,subscription.path(field));value.put("includeEvents",subscription.path("includeEvents").asBoolean(true));return value;
    }
    private record Raw(ObjectNode settings,String body){@Override public String toString(){return "CalendarSearchSources.Raw[]";}}
    record Feed(UUID id,ObjectNode settings,String bodyHash,CalendarFeed calendar){@Override public String toString(){return "CalendarSearchSources.Feed[id="+id+"]";}}
    Feed feed(String owner,UUID id){
        Raw raw=store.readSnapshot(owner,()->{
            var subscription=store.find(CalendarSubscriptions.SUB,id).orElse(null);if(subscription==null||!visible(subscription))return null;
            var feed=store.find(CalendarSubscriptions.FEED,id).orElse(null);if(feed==null||!feed.path("body").isTextual())return null;
            return new Raw(settings(subscription),feed.path("body").asText());
        });
        return raw==null?null:new Feed(id,raw.settings(),ArchiveJournalFiles.digest(raw.body().getBytes(StandardCharsets.UTF_8)),new CalendarFeed(raw.body(),true,false));
    }
    static UUID resultId(UUID subscription,String definition){return ImportLedger.identifier("calendar-search-result",subscription.toString(),definition);}
    private static String binding(ObjectNode settings,String bodyHash,ZoneId zone){return etag(object().set("settings",settings)).concat(":"+bodyHash+":"+zone.getId());}
    private static ObjectNode envelope(Feed feed,ObjectNode definition,ZoneId zone,UUID result){
        var event=definition.deepCopy();event.put("definitionId",definition.path("id").asText()).put("id",result.toString()).put("subscriptionId",feed.id().toString())
                .put("calendarName",feed.settings().path("name").asText()).put("clientId",feed.settings().path("clientId").asText());
        return object().put("binding",binding(feed.settings(),feed.bodyHash(),zone)).put("feedHash",feed.bodyHash()).set("event",event);
    }
    private static NotesSearchText.Projection text(ObjectNode definition,NotesLuceneIndex.Source note){
        var metadata=NotesSearchText.extract((definition.path("description").asText()+"\n\n"+definition.path("location").asText()).getBytes(StandardCharsets.UTF_8));
        var blocks=new ArrayList<>(metadata.blocks());var tags=new ArrayList<>(metadata.tags());
        if(note!=null){if(!note.title().isBlank())blocks.add(note.title());blocks.addAll(note.text().blocks());tags.addAll(note.text().tags());}
        return new NotesSearchText.Projection("",blocks,tags);
    }
    NotesLuceneIndex.Source definition(Feed feed,ObjectNode definition,ZoneId zone,long generation){
        UUID id=resultId(feed.id(),definition.path("id").asText());var text=text(definition,null);var envelope=envelope(feed,definition,zone,id);
        return new NotesLuceneIndex.Source(id,"calendar-search/"+feed.id()+"/"+definition.path("id").asText()+".ics","EVENT",SOURCE,id,generation,envelope.path("binding").asText(),feed.bodyHash(),text.body().getBytes(StandardCharsets.UTF_8).length,definition.path("title").asText(),text,envelope);
    }
    NotesLuceneIndex.Source note(String owner,JsonNode row,Feed feed,ZoneId zone){
        if(feed==null||!EventNotes.present(row))return null;
        var definition=feed.calendar().eventDefinition(row.path("event").path("uid").asText(),row.path("event").path("recurrenceId").asText(),zone).orElse(null);if(definition==null)return null;
        var entry=store.readSnapshot(owner,()->new VaultIndex(store).referenced(EventNotes.TYPE,uuid(row.path("id"))));if(entry==null||entry.path("deleted").asBoolean())return null;
        var note=new NotesSearchSources(store).load(owner,uuid(entry.path("id")));if(note==null)return null;
        var envelope=envelope(feed,definition,zone,note.id()).put("noteFence",NotesLuceneIndex.fence(note));((ObjectNode)envelope.path("event")).put("noteEntryId",note.id().toString());
        var text=text(definition,note);
        return new NotesLuceneIndex.Source(note.id(),note.path(),note.kind(),note.sourceType(),note.sourceId(),note.sourceVersion(),note.sourceTag(),note.sourceHash(),note.byteLength()+text.body().getBytes(StandardCharsets.UTF_8).length,definition.path("title").asText(),text,envelope,note.bookmarked());
    }
    /** Caller holds the final authorized transaction and has rechecked both source snapshots. */
    ObjectNode currentCalendar(NotesLuceneIndex.Hit hit,Map<UUID,String> bindings){
        var envelope=hit.calendar();if(envelope==null||!envelope.path("event").isObject())throw unavailable();
        var event=((ObjectNode)envelope.path("event")).deepCopy();UUID subscription=uuid(event.path("subscriptionId"));
        String current=bindings.computeIfAbsent(subscription,id->{
            var value=store.find(CalendarSubscriptions.SUB,id).orElseThrow(CalendarSearchSources::unavailable);if(!visible(value))throw unavailable();
            var feed=store.find(CalendarSubscriptions.FEED,id).orElseThrow(CalendarSearchSources::unavailable);if(!feed.path("body").isTextual())throw unavailable();
            return binding(settings(value),ArchiveJournalFiles.digest(feed.path("body").asText().getBytes(StandardCharsets.UTF_8)),ZoneId.of(store.profile().path("zoneId").asText()));
        });
        if(!current.equals(envelope.path("binding").asText())||!hit.id().toString().equals(event.path("id").asText()))throw unavailable();
        if(hit.sourceType().equals(SOURCE)&&(!resultId(subscription,event.path("definitionId").asText()).equals(hit.id())||!hit.sourceId().equals(hit.id())||hit.sourceVersion()!=new CalendarSearchState(store).generation()||!hit.sourceTag().equals(current)||!hit.sourceHash().equals(envelope.path("feedHash").asText())))throw unavailable();
        var client=store.find("CLIENT",uuid(event.path("clientId"))).orElseThrow(CalendarSearchSources::unavailable);
        event.put("clientName",client.path("name").asText()).put("color",client.path("color").asText());return event;
    }
    private static ApiFailure unavailable(){return new ApiFailure(503,"NOTES_SEARCH_INDEXING");}
}
