package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/** Bounded, owner-private prospective evidence; never a second note-body store. */
final class DayNoteHistory {
    static final String TYPE="DAY_NOTE_HISTORY";
    static final int RETAINED=1000;
    enum Source {REST_LEGACY,REST_NOTE,REST_LABEL,REST_ENTRY,WEBDAV,ARCHIVE_RESTORE,INTERNAL}
    private final ServerStore store;
    DayNoteHistory(ServerStore store){this.store=store;}

    void label(LocalDate date,JsonNode before,JsonNode after){
        var note=new DayNotes(store).getContent(date);
        var fields=new ArrayList<String>();
        if(!before.path("text").asText().equals(after.path("text").asText()))fields.add("text");
        if(before.path("version").asLong()==0)fields.add("presence");
        append(date,"LABEL",after.path("text").asText().isEmpty()?"CLEAR":before.path("version").asLong()==0?"CREATE":"UPDATE",
                before,after,note,note,fields);
    }
    void note(LocalDate date,JsonNode before,JsonNode after){
        var label=new DayLabels(store).get(date);
        var fields=new ArrayList<String>();
        if(!before.path("body").asText().equals(after.path("body").asText()))fields.add("body");
        if(!Objects.equals(before.get("markdown"),after.get("markdown")))fields.add("markdown");
        if(before.path("deleted").asBoolean()!=after.path("deleted").asBoolean())fields.add("deleted");
        boolean present=before.path("version").asLong()>0&&!before.path("deleted").asBoolean();
        if(present==after.path("deleted").asBoolean())fields.add("presence");
        String operation=after.path("deleted").asBoolean()?"DELETE":before.path("deleted").asBoolean()?"RECREATE":
                before.path("version").asLong()==0?"CREATE":after.path("body").asText().isEmpty()&&!before.path("body").asText().isEmpty()?"CLEAR":"UPDATE";
        if(store.journalSource()==Source.WEBDAV&&(operation.equals("UPDATE")||operation.equals("CLEAR")))operation="REPLACE";
        append(date,"NOTE",operation,label,label,before,after,fields);
    }
    private void append(LocalDate date,String resource,String operation,JsonNode oldLabel,JsonNode label,JsonNode oldNote,JsonNode note,java.util.List<String> fields){
        if(store.journalSource()==Source.ARCHIVE_RESTORE)operation="RESTORE";
        var prior=store.find(TYPE,DayNotes.id(date));
        var history=prior.map(value->((ObjectNode)value).deepCopy()).orElseGet(()->{
            var value=object().put("id",DayNotes.id(date).toString()).put("ownerId",store.owner()).put("date",date.toString()).put("version",0)
                    .put("recordingStartedAt",store.now().toString()).put("legacyGap",oldLabel.path("version").asLong()>0||oldNote.path("version").asLong()>0||store.journalSource()==Source.ARCHIVE_RESTORE);
            value.putArray("events");return value;
        });
        long previous=history.path("version").asLong();if(previous>=9007199254740991L)throw ApiFailure.conflict();
        var event=object().put("id",UUID.randomUUID().toString()).put("sequence",previous+1).put("occurredAt",store.now().toString())
                .put("resource",resource).put("operation",operation).put("source",store.journalSource().name())
                .put("previousNoteVersion",oldNote.path("version").asLong()).put("noteVersion",note.path("version").asLong())
                .put("previousLabelVersion",oldLabel.path("version").asLong()).put("labelVersion",label.path("version").asLong())
                .put("previousLabel",oldLabel.path("text").asText()).put("newLabel",label.path("text").asText())
                .put("hadBody",!oldNote.path("body").asText().isEmpty()).put("hasBody",!note.path("body").asText().isEmpty()).put("noteDeleted",note.path("deleted").asBoolean());
        event.set("previousModifiedAt",(resource.equals("LABEL")?oldLabel:oldNote).get("modifiedAt"));
        fields.forEach(event.putArray("changedFields")::add);event.set("client",store.journalClient());event.set("build",store.journalBuild());
        var events=(ArrayNode)history.get("events");events.add(event);while(events.size()>RETAINED)events.remove(0);
        history.put("version",previous+1);
        if(prior.isEmpty())store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,1,?)",store.owner(),TYPE,DayNotes.id(date),history.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",previous+1,history.toString(),store.owner(),TYPE,DayNotes.id(date),previous)!=1)throw new ApiFailure(412,"STALE_VERSION");
    }
    ObjectNode page(LocalDate date,int limit,String cursor,Pages pages){
        var history=store.find(TYPE,DayNotes.id(date));var values=new ArrayList<JsonNode>();
        history.ifPresent(value->value.path("events").forEach(values::add));
        var result=pages.page(TYPE,values,object().put("date",date.toString()),limit,cursor);
        var note=new DayNotes(store).getContent(date);var label=new DayLabels(store).get(date);
        var current=object().put("noteVersion",note.path("version").asLong()).put("labelVersion",label.path("version").asLong())
                .put("label",label.path("text").asText()).put("noteDeleted",note.path("deleted").asBoolean()).put("hasBody",note.path("hasBody").asBoolean());
        current.set("noteModifiedAt",store.find(DayNotes.TYPE,DayNotes.id(date)).map(value->value.path("modifiedAt")).orElse(null));
        current.set("labelModifiedAt",store.find(DayLabels.TYPE,DayNotes.id(date)).map(value->value.path("modifiedAt")).orElse(null));
        result.set("current",current);result.put("date",date.toString()).put("retentionLimit",RETAINED);
        result.put("historyVersion",history.map(value->value.path("version").asLong()).orElse(0L));
        result.put("legacyGap",history.map(value->value.path("legacyGap").asBoolean()).orElse(note.path("version").asLong()>0||label.path("version").asLong()>0));
        result.put("retentionTruncated",!values.isEmpty()&&values.getFirst().path("sequence").asLong()>1);
        result.set("recordingStartedAt",history.map(value->value.path("recordingStartedAt")).orElse(null));
        return result;
    }
}
