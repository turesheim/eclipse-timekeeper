package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.LocalDate;
import java.time.Instant;
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
    private ObjectNode record(LocalDate date){
        var value=store.find(TYPE,id(date)).map(record->((ObjectNode)record).deepCopy()).orElseGet(()->object().put("id",id(date).toString()).put("ownerId",store.owner()).put("version",0).put("date",date.toString()).put("text",""));
        if(!value.has("body"))value.put("body","");
        // Old replicas preserve unknown DAY_NOTE payload fields when saving.
        // Bind private tombstones to a precise version so an older replica's
        // later save cannot retain a stale deletion marker.
        boolean deleted=store.find(JournalState.Kind.JOURNAL_DELETE.name(),id(date))
                .map(marker->marker.path("noteVersion").asLong()==value.path("version").asLong()&&emptyForDeletion(value)).orElse(false);
        value.put("deleted",deleted);
        return value.put("hasBody",!value.path("body").asText().isEmpty());
    }
    /** Legacy combined read model; labelVersion protects its composite ETag. */
    ObjectNode get(LocalDate date){
        var result=getContent(date);var label=new DayLabels(store).get(date);
        return result.put("text",label.path("text").asText()).put("labelVersion",label.path("version").asLong());
    }
    ObjectNode getContent(LocalDate date){var result=publicValue(record(date));result.remove("text");return result;}
    Instant modifiedAt(LocalDate date){
        var value=record(date);
        return value.hasNonNull("modifiedAt")?Instant.parse(value.path("modifiedAt").asText()):Instant.EPOCH;
    }
    List<LocalDate> presentDates(){
        var deleted=new HashMap<UUID,Long>();
        for(var marker:store.list(JournalState.Kind.JOURNAL_DELETE.name()))deleted.put(uuid(marker.path("id")),marker.path("noteVersion").asLong());
        var dates=new ArrayList<LocalDate>();
        for(var value:store.list(TYPE)){
            if(value.path("version").asLong()>0&&!(deleted.getOrDefault(uuid(value.path("id")),-1L)==value.path("version").asLong()&&emptyForDeletion(value)))
                dates.add(date(value.path("date").asText()));
        }
        return dates;
    }
    /** Complete document for an existing note, including legacy read-time reconstruction. */
    byte[] document(LocalDate date){
        var value=record(date);
        if(value.path("version").asLong()==0||value.path("deleted").asBoolean())return null;
        return document(date,value);
    }
    private static byte[] document(LocalDate date,ObjectNode value){
        if(value.has("markdown"))return value.path("markdown").asText().getBytes(StandardCharsets.UTF_8);
        return JournalMarkdown.render(date+".md",null,value.path("text").asText(),value.path("body").asText());
    }
    static boolean hasContent(JsonNode note){return !note.path("text").asText().isEmpty()||!note.path("body").asText().isEmpty();}
    static boolean emptyForDeletion(JsonNode note){return note.path("labelIndependent").asBoolean()?note.path("body").asText().isEmpty():!hasContent(note);}
    ObjectNode list(LocalDate first,LocalDate last){
        long days=ChronoUnit.DAYS.between(first,last);if(days<0||days>=62)throw ApiFailure.validation();
        var dates=new TreeSet<LocalDate>();
        for(String type:List.of(TYPE,DayLabels.TYPE))for(var value:store.list(type,id(first),id(last)))dates.add(date(value.path("date").asText()));
        var result=object().put("revision",store.revision());var items=result.putArray("items");
        for(var day:dates){var value=get(day);if(hasContent(value)){value.remove("body");items.add(value);}}
        return result;
    }
    ObjectNode save(LocalDate date,String match,String text){return save(date,match,object().put("text",text));}
    /** Compatibility adapter; each supplied field updates its own resource. */
    ObjectNode save(LocalDate date,String match,JsonNode input){return saveLegacy(date,match,input,false);}
    ObjectNode present(LocalDate date,String match,JsonNode input){return saveLegacy(date,match,input,true);}
    private ObjectNode saveLegacy(LocalDate date,String match,JsonNode input,boolean materialize){
        ApiReplies.match(get(date),match);var labels=new DayLabels(store);labels.migrateDate(date);
        if(input.has("text"))labels.save(date,etag(labels.get(date)),input.path("text").asText());
        if(input.has("body"))writeBody(date,input.path("body").asText(),false,materialize);
        return get(date);
    }
    ObjectNode delete(LocalDate date,String match){
        ApiReplies.match(get(date),match);deleteContent(date,etag(getContent(date)));return get(date);
    }
    ObjectNode saveContent(LocalDate date,String match,JsonNode input){
        ApiReplies.match(getContent(date),match);writeBody(date,input.path("body").asText(),false,true);return getContent(date);
    }
    ObjectNode deleteContent(LocalDate date,String match){
        ApiReplies.match(getContent(date),match);writeBody(date,"",true,true);return getContent(date);
    }
    /** External documents never change an independent Calendar label. */
    ObjectNode saveDocument(LocalDate date,String match,byte[] bytes){
        ApiReplies.match(getContent(date),match);new DayLabels(store).migrateDate(date);
        final JournalMarkdown.Note parsed;
        try{parsed=JournalMarkdown.parseOpaque(date+".md",bytes);}catch(JournalMarkdown.Invalid invalid){throw ApiFailure.validation();}
        var previous=record(date);
        if(!previous.path("deleted").asBoolean()&&previous.path("version").asLong()>0&&Arrays.equals(document(date,previous),bytes))return getContent(date);
        persist(date,previous,previous.path("text").asText(),parsed.body(),new String(bytes,StandardCharsets.UTF_8),false);return getContent(date);
    }
    private void writeBody(LocalDate date,String body,boolean deleted,boolean materialize){
        new DayLabels(store).migrateDate(date);var previous=record(date);
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(body)||body.getBytes(StandardCharsets.UTF_8).length>BODY_BYTES)throw ApiFailure.validation();
        if(previous.path("body").asText().equals(body)&&previous.path("deleted").asBoolean()==deleted&&(!materialize||previous.path("version").asLong()>0))return;
        byte[] original=previous.path("deleted").asBoolean()||previous.path("version").asLong()==0?null:document(date,previous);
        final String markdown;
        try{
            // Reserved metadata in an old document is now opaque. Keep its
            // original value; new independent labels never enter Markdown.
            markdown=deleted?"":new String(JournalMarkdown.replaceBody(date+".md",original,body),StandardCharsets.UTF_8);
        }catch(JournalMarkdown.Invalid invalid){throw ApiFailure.validation();}
        persist(date,previous,previous.path("text").asText(),body,markdown,deleted);
    }
    private static ObjectNode publicValue(ObjectNode value){
        var result=value.deepCopy();result.remove(List.of("markdown","modifiedAt","labelIndependent"));return result;
    }
    private ObjectNode persist(LocalDate date,ObjectNode previous,String text,String body,String markdown,boolean deleted){
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=previous.deepCopy().put("version",version+1).put("text",text).put("body",body).put("markdown",markdown).put("modifiedAt",store.now().toString()).put("hasBody",!body.isEmpty()).put("deleted",deleted).put("labelIndependent",true);
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
        new DayNoteHistory(store).note(date,previous,value);
        store.change("PROFILE","UPDATED",store.owner());return get(date);
    }
    void restoreDocument(LocalDate date,byte[] bytes){
        var current=getContent(date);
        if(current.path("version").asLong()>0&&!current.path("deleted").asBoolean()){
            if(Arrays.equals(document(date),bytes))return;throw ApiFailure.conflict();
        }
        saveDocument(date,etag(current),bytes);
    }
    void restore(JsonNode notes){
        var seen=new HashSet<LocalDate>();
        for(var note:notes){
            LocalDate date=date(note.path("date").asText());if(!seen.add(date))throw ApiFailure.validation();
            var current=get(date);String text=note.path("text").asText().strip();String body=note.has("body")?note.path("body").asText():current.path("body").asText();
            if(note.has("markdown")){
                if(!note.path("markdown").isTextual())throw ApiFailure.validation();
                if(!StandardCharsets.UTF_8.newEncoder().canEncode(note.path("markdown").asText()))throw ApiFailure.validation();
                byte[] bytes=note.path("markdown").asText().getBytes(StandardCharsets.UTF_8);
                final JournalMarkdown.Note parsed;
                try{parsed=JournalMarkdown.parse(date+".md",bytes);}catch(JournalMarkdown.Invalid invalid){throw ApiFailure.validation();}
                if(!parsed.label().equals(text)||note.has("body")&&!parsed.body().equals(body))throw ApiFailure.validation();
                if(current.path("version").asLong()>0&&!current.path("deleted").asBoolean()){
                    if(Arrays.equals(document(date),bytes))continue;
                    throw ApiFailure.conflict();
                }
                var labels=new DayLabels(store);var label=labels.get(date);
                if(label.path("version").asLong()>0&&!label.path("text").asText().equals(text))throw ApiFailure.conflict();
                labels.save(date,etag(label),text);saveDocument(date,etag(getContent(date)),bytes);continue;
            }
            if(text.isEmpty()&&note.path("body").asText().isEmpty())throw ApiFailure.validation();
            if(hasContent(current)&&(!current.path("text").asText().equals(text)||!current.path("body").asText().equals(body)))throw ApiFailure.conflict();
            var input=object().put("text",text);if(note.has("body"))input.put("body",body);
            save(date,etag(current),input);
        }
    }
}
