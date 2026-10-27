package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Account-private ISO-week Markdown, independent of daily notes and timed work. */
final class WeekNotes {
    static final String TYPE="WEEK_NOTE";
    record Week(String name,LocalDate monday) {String filename(){return name+".md";}}
    private final ServerStore store;
    WeekNotes(ServerStore store){this.store=store;}
    static Week week(String text){
        try {
            if(text==null||!text.matches("[0-9]{4}-W[0-9]{2}"))throw new IllegalArgumentException();
            int year=Integer.parseInt(text.substring(0,4)),number=Integer.parseInt(text.substring(6));
            if(year<1||number<1||number>53)throw new IllegalArgumentException();
            LocalDate monday=LocalDate.of(year,1,4).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(number-1);
            if(monday.get(WeekFields.ISO.weekBasedYear())!=year||monday.get(WeekFields.ISO.weekOfWeekBasedYear())!=number)throw new IllegalArgumentException();
            return new Week(text,monday);
        }catch(RuntimeException invalid){throw ApiFailure.validation();}
    }
    static Week containing(LocalDate date){return week(String.format(Locale.ROOT,"%04d-W%02d",date.get(WeekFields.ISO.weekBasedYear()),date.get(WeekFields.ISO.weekOfWeekBasedYear())));}
    static Week filename(String filename){
        if(filename==null||!filename.matches("[0-9]{4}-W[0-9]{2}\\.md"))throw ApiFailure.validation();
        return week(filename.substring(0,8));
    }
    private static UUID id(Week week){return DayNotes.id(week.monday());}
    private ObjectNode record(Week week){
        var value=store.find(TYPE,id(week)).map(record->((ObjectNode)record).deepCopy()).orElseGet(()->object()
                .put("id",id(week).toString()).put("ownerId",store.owner()).put("version",0).put("week",week.name()).put("body",""));
        boolean deleted=store.find(JournalState.Kind.WEEK_NOTE_DELETE.name(),id(week))
                .map(marker->marker.path("noteVersion").asLong()==value.path("version").asLong()&&value.path("body").asText().isEmpty()).orElse(false);
        return value.put("deleted",deleted).put("hasBody",!value.path("body").asText().isEmpty());
    }
    ObjectNode get(Week week){return publicValue(record(week));}
    byte[] document(Week week){var value=record(week);return value.path("version").asLong()==0||value.path("deleted").asBoolean()?null:value.path("body").asText().getBytes(StandardCharsets.UTF_8);}
    Instant modifiedAt(Week week){var value=record(week);return value.hasNonNull("modifiedAt")?Instant.parse(value.path("modifiedAt").asText()):Instant.EPOCH;}
    List<Week> presentWeeks(){
        var deleted=new HashMap<UUID,Long>();for(var marker:store.list(JournalState.Kind.WEEK_NOTE_DELETE.name()))deleted.put(uuid(marker.path("id")),marker.path("noteVersion").asLong());
        var result=new ArrayList<Week>();for(var value:store.list(TYPE))if(value.path("version").asLong()>0
                &&!(deleted.getOrDefault(uuid(value.path("id")),-1L)==value.path("version").asLong()&&value.path("body").asText().isEmpty()))result.add(week(value.path("week").asText()));
        return result;
    }
    ObjectNode list(Week first,Week last){
        long weeks=ChronoUnit.WEEKS.between(first.monday(),last.monday());if(weeks<0||weeks>=9)throw ApiFailure.validation();
        var result=object().put("revision",store.revision());var items=result.putArray("items");
        for(var value:store.list(TYPE,id(first),id(last)))if(value.path("version").asLong()>0){
            var item=get(week(value.path("week").asText()));if(!item.path("deleted").asBoolean()){item.remove("body");items.add(item);}
        }
        return result;
    }
    ObjectNode save(Week week,String match,JsonNode input){return write(week,match,input.path("body").asText(),false);}
    ObjectNode delete(Week week,String match){return write(week,match,"",true);}
    ObjectNode saveDocument(Week week,String match,byte[] bytes){
        if(bytes.length>DayNotes.BODY_BYTES)throw ApiFailure.validation();
        final String body;
        try{body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw ApiFailure.validation();}
        return write(week,match,body,false);
    }
    private ObjectNode write(Week week,String match,String body,boolean deleted){
        var previous=record(week);ApiReplies.match(publicValue(previous),match);
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(body)||body.getBytes(StandardCharsets.UTF_8).length>DayNotes.BODY_BYTES)throw ApiFailure.validation();
        long version=previous.path("version").asLong();
        if(version>0&&previous.path("body").asText().equals(body)&&previous.path("deleted").asBoolean()==deleted)return publicValue(previous);
        if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=previous.deepCopy().put("version",version+1).put("body",body).put("modifiedAt",store.now().toString())
                .put("hasBody",!body.isEmpty()).put("deleted",deleted);
        var stored=value.deepCopy();stored.remove(List.of("hasBody","deleted"));
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,1,?)",store.owner(),TYPE,id(week),stored.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,stored.toString(),store.owner(),TYPE,id(week),version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        if(deleted){
            var state=new JournalState(store);var marker=state.find(JournalState.Kind.WEEK_NOTE_DELETE,id(week));
            if(marker.isEmpty())state.insert(JournalState.Kind.WEEK_NOTE_DELETE,id(week),object().put("week",week.name()).put("noteVersion",version+1));
            else state.update(JournalState.Kind.WEEK_NOTE_DELETE,marker.get().put("noteVersion",version+1));
        }
        store.change("PROFILE","UPDATED",store.owner());return publicValue(value);
    }
    private static ObjectNode publicValue(ObjectNode value){var result=value.deepCopy();result.remove("modifiedAt");return result;}
    void restore(JsonNode notes){
        var seen=new HashSet<String>();for(var note:notes){
            Week week=week(note.path("week").asText());if(!seen.add(week.name())||!week.filename().equals(note.path("filename").asText())||!note.path("markdown").isTextual())throw ApiFailure.validation();
            byte[] bytes=note.path("markdown").asText().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>DayNotes.BODY_BYTES)throw ApiFailure.validation();
            var current=get(week);if(current.path("version").asLong()>0&&!current.path("deleted").asBoolean()){
                if(Arrays.equals(document(week),bytes))continue;throw ApiFailure.conflict();
            }
            saveDocument(week,etag(current),bytes);
        }
    }
}
