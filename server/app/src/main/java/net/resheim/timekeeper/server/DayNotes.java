package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Web-only civil-date labels, stored independently of timed work records. */
final class DayNotes {
    static final String TYPE="DAY_NOTE";
    private final ServerStore store;
    DayNotes(ServerStore store){this.store=store;}
    static LocalDate date(String text){
        try {if(text==null || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || text.startsWith("0000"))throw new IllegalArgumentException();return LocalDate.parse(text);}
        catch(RuntimeException invalid){throw ApiFailure.validation();}
    }
    // Ordered UUIDs allow bounded date reads through the existing owner/type/id index.
    static UUID id(LocalDate date){return UUID.fromString("00000000-0000-0000-0000-"+String.format(Locale.ROOT,"%012d",date.getYear()*10000+date.getMonthValue()*100+date.getDayOfMonth()));}
    ObjectNode get(LocalDate date){
        return store.find(TYPE,id(date)).map(value->((ObjectNode)value).deepCopy()).orElseGet(()->object().put("id",id(date).toString()).put("ownerId",store.owner()).put("version",0).put("date",date.toString()).put("text",""));
    }
    ObjectNode list(LocalDate first,LocalDate last){
        long days=ChronoUnit.DAYS.between(first,last);if(days<0 || days>=62)throw ApiFailure.validation();
        var result=object().put("revision",store.revision());var items=result.putArray("items");
        for(var value:store.list(TYPE,id(first),id(last)))if(!value.path("text").asText().isEmpty())items.add(value);
        return result;
    }
    ObjectNode save(LocalDate date,String match,String text){
        var previous=get(date);ApiReplies.match(previous,match);text=text.strip();
        if(text.codePointCount(0,text.length())>80 || text.codePoints().anyMatch(Character::isISOControl))throw ApiFailure.validation();
        if(previous.path("text").asText().equals(text))return previous;
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=previous.deepCopy().put("version",version+1).put("text",text);
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,1,?)",store.owner(),TYPE,id(date),value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),TYPE,id(date),version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        // Empty values retain their version so deleting and recreating cannot
        // make an older editor's ETag valid again. Existing clients already
        // understand profile invalidations; no Eclipse/domain type is added.
        store.change("PROFILE","UPDATED",store.owner());return value;
    }
    void restore(JsonNode notes){
        var seen=new HashSet<LocalDate>();
        for(var note:notes){
            LocalDate date=date(note.path("date").asText());if(!seen.add(date))throw ApiFailure.validation();
            var current=get(date);String text=note.path("text").asText().strip();if(text.isEmpty())throw ApiFailure.validation();
            if(!current.path("text").asText().isEmpty() && !current.path("text").asText().equals(text))throw ApiFailure.conflict();
            save(date,etag(current),text);
        }
    }
}
