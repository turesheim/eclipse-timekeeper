package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Civil-date labels have their own record, version and lifetime, outside Markdown. */
final class DayLabels {
    static final String TYPE="DAY_LABEL";
    private final ServerStore store;
    DayLabels(ServerStore store){this.store=store;}
    ObjectNode get(LocalDate date){
        var value=store.find(TYPE,DayNotes.id(date)).map(record->((ObjectNode)record).deepCopy()).orElseGet(()->object()
                .put("id",DayNotes.id(date).toString()).put("ownerId",store.owner()).put("date",date.toString()).put("version",0).put("text",store.find(DayNotes.TYPE,DayNotes.id(date)).map(note->note.path("text").asText()).orElse("")));
        value.remove("modifiedAt");return value;
    }
    ObjectNode list(LocalDate first,LocalDate last){
        long days=ChronoUnit.DAYS.between(first,last);if(days<0||days>=62)throw ApiFailure.validation();
        var result=object().put("revision",store.revision());var items=result.putArray("items");
        for(var value:store.list(TYPE,DayNotes.id(first),DayNotes.id(last)))if(!value.path("text").asText().isEmpty())items.add(get(DayNotes.date(value.path("date").asText())));
        return result;
    }
    ObjectNode save(LocalDate date,String match,String text){
        var previous=get(date);ApiReplies.match(previous,match);return write(date,previous,text);
    }
    ObjectNode write(LocalDate date,ObjectNode previous,String text){
        text=text.strip();if(!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text)||text.codePointCount(0,text.length())>80||text.codePoints().anyMatch(Character::isISOControl))throw ApiFailure.validation();
        if(previous.path("version").asLong()>0&&previous.path("text").asText().equals(text))return previous;
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        var historyBefore=previous.deepCopy();
        historyBefore.set("modifiedAt",store.find(TYPE,DayNotes.id(date)).map(old->old.get("modifiedAt")).orElse(null));
        var value=previous.deepCopy().put("version",version+1).put("text",text).put("modifiedAt",store.now().toString());
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,1,?)",store.owner(),TYPE,DayNotes.id(date),value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),TYPE,DayNotes.id(date),version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        new DayNoteHistory(store).label(date,historyBefore,value);
        store.change("PROFILE","UPDATED",store.owner());value.remove("modifiedAt");return value;
    }
    /** Called during the authorized model upgrade, in an owner transaction. */
    void migrate(){for(var note:store.list(DayNotes.TYPE))migrateDate(DayNotes.date(note.path("date").asText()));}
    void migrateDate(LocalDate date){
        if(store.find(TYPE,DayNotes.id(date)).isPresent())return;
        var old=store.find(DayNotes.TYPE,DayNotes.id(date));if(old.isEmpty())return;
        var note=old.orElseThrow();if(note.path("labelIndependent").asBoolean())return;
            var label=object().put("id",DayNotes.id(date).toString()).put("ownerId",store.owner()).put("date",date.toString()).put("version",1).put("text",note.path("text").asText());
            if(note.has("modifiedAt"))label.set("modifiedAt",note.get("modifiedAt"));
            store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,1,?)",store.owner(),TYPE,DayNotes.id(date),label.toString());
            // Materialize the old virtual document once; later label edits must
            // not change its bytes, metadata, note version or modification time.
            if(!note.has("markdown")){
                var copy=((ObjectNode)note).deepCopy();
                copy.put("markdown",new String(JournalMarkdown.render(date+".md",null,note.path("text").asText(),note.path("body").asText()),java.nio.charset.StandardCharsets.UTF_8));
                store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=?",copy.toString(),store.owner(),DayNotes.TYPE,DayNotes.id(date));
            }
    }
    void restore(JsonNode labels){
        var seen=new HashSet<LocalDate>();
        for(var label:labels){
            if(!label.isObject()||!label.path("text").isTextual())throw ApiFailure.validation();
            var fields=new HashSet<String>();label.fieldNames().forEachRemaining(fields::add);if(!fields.equals(java.util.Set.of("date","text")))throw ApiFailure.validation();
            var date=DayNotes.date(label.path("date").asText());if(!seen.add(date))throw ApiFailure.validation();
            var previous=get(date);String text=label.path("text").asText().strip();
            if(previous.path("version").asLong()>0&&!previous.path("text").asText().equals(text))throw ApiFailure.conflict();
            write(date,previous,text);
        }
    }
}
