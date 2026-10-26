package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Account-scoped civil-date labels and Markdown, independent of timed work. */
final class DayNotes {
    static final String TYPE="DAY_NOTE";
    static final int BODY_BYTES=200000;
    private final ServerStore store;
    DayNotes(ServerStore store){this.store=store;}
    static LocalDate date(String text){
        try {if(text==null || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || text.startsWith("0000"))throw new IllegalArgumentException();return LocalDate.parse(text);}
        catch(RuntimeException invalid){throw ApiFailure.validation();}
    }
    // Ordered UUIDs allow bounded date reads through the existing owner/type/id index.
    static UUID id(LocalDate date){return UUID.fromString("00000000-0000-0000-0000-"+String.format(Locale.ROOT,"%012d",date.getYear()*10000+date.getMonthValue()*100+date.getDayOfMonth()));}
    ObjectNode get(LocalDate date){
        var value=store.find(TYPE,id(date)).map(record->((ObjectNode)record).deepCopy()).orElseGet(()->object().put("id",id(date).toString()).put("ownerId",store.owner()).put("version",0).put("date",date.toString()).put("text",""));
        if(!value.has("body"))value.put("body","");
        // Old replicas preserve unknown DAY_NOTE payload fields when saving.
        // Bind private tombstones to a precise version so an older replica's
        // later save cannot retain a stale deletion marker.
        boolean deleted=store.find(JournalState.Kind.JOURNAL_DELETE.name(),id(date))
                .map(marker->marker.path("noteVersion").asLong()==value.path("version").asLong()&&!hasContent(value)).orElse(false);
        value.put("deleted",deleted);
        return value.put("hasBody",!value.path("body").asText().isEmpty());
    }
    static boolean hasContent(JsonNode note){return !note.path("text").asText().isEmpty()||!note.path("body").asText().isEmpty();}
    ObjectNode list(LocalDate first,LocalDate last){
        long days=ChronoUnit.DAYS.between(first,last);if(days<0 || days>=62)throw ApiFailure.validation();
        var result=object().put("revision",store.revision());var items=result.putArray("items");
        for(var value:store.list(TYPE,id(first),id(last)))if(hasContent(value)){
            var summary=((ObjectNode)value).deepCopy();summary.remove("body");summary.put("hasBody",!value.path("body").asText().isEmpty());items.add(summary);
        }
        return result;
    }
    ObjectNode save(LocalDate date,String match,String text){
        return save(date,match,object().put("text",text));
    }
    ObjectNode save(LocalDate date,String match,JsonNode input){
        return write(date,match,input,false,false);
    }
    ObjectNode present(LocalDate date,String match,JsonNode input){
        return write(date,match,input,false,true);
    }
    ObjectNode delete(LocalDate date,String match){
        return write(date,match,object().put("text","").put("body",""),true,true);
    }
    private ObjectNode write(LocalDate date,String match,JsonNode input,boolean deleted,boolean materialize){
        var previous=get(date);ApiReplies.match(previous,match);
        String text=input.has("text")?input.get("text").asText().strip():previous.path("text").asText();
        String body=input.has("body")?input.get("body").asText():previous.path("body").asText();
        if(text.codePointCount(0,text.length())>80 || text.codePoints().anyMatch(Character::isISOControl))throw ApiFailure.validation();
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(body)||body.getBytes(StandardCharsets.UTF_8).length>BODY_BYTES)throw ApiFailure.validation();
        if(previous.path("text").asText().equals(text)&&previous.path("body").asText().equals(body)&&previous.path("deleted").asBoolean()==deleted&&(!materialize||previous.path("version").asLong()>0))return previous;
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=previous.deepCopy().put("version",version+1).put("text",text).put("body",body).put("hasBody",!body.isEmpty()).put("deleted",deleted);
        var stored=value.deepCopy();stored.remove(List.of("hasBody","deleted"));
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,1,?)",store.owner(),TYPE,id(date),stored.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,stored.toString(),store.owner(),TYPE,id(date),version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        if(deleted){
            var state=new JournalState(store);var tombstone=state.find(JournalState.Kind.JOURNAL_DELETE,id(date));
            if(tombstone.isEmpty())state.insert(JournalState.Kind.JOURNAL_DELETE,id(date),object().put("date",date.toString()).put("noteVersion",version+1));
            else state.update(JournalState.Kind.JOURNAL_DELETE,tombstone.get().put("noteVersion",version+1));
        }
        // Empty values retain their version so deleting and recreating cannot
        // make an older editor's ETag valid again. Existing clients already
        // understand profile invalidations; no Eclipse/domain type is added.
        store.change("PROFILE","UPDATED",store.owner());return value;
    }
    void restore(JsonNode notes){
        var seen=new HashSet<LocalDate>();
        for(var note:notes){
            LocalDate date=date(note.path("date").asText());if(!seen.add(date))throw ApiFailure.validation();
            var current=get(date);String text=note.path("text").asText().strip();String body=note.has("body")?note.path("body").asText():current.path("body").asText();
            if(text.isEmpty()&&note.path("body").asText().isEmpty())throw ApiFailure.validation();
            if(hasContent(current)&&(!current.path("text").asText().equals(text)||!current.path("body").asText().equals(body)))throw ApiFailure.conflict();
            var input=object().put("text",text);if(note.has("body"))input.put("body",body);
            save(date,etag(current),input);
        }
    }
}
