package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Durable completed-batch importer. No filesystem reads or transport authorization here. */
final class JournalImport {
    static final int BATCH_FILES=100;
    static final int RETAINED_CANDIDATES=500;
    private final ServerStore store;
    private final JournalState state;
    JournalImport(ServerStore store){this.store=store;state=new JournalState(store);}
    private static UUID baselineId(UUID device,String filename){return ImportLedger.identifier("journal-file",device.toString(),filename);}
    private static UUID itemId(UUID batch,String filename){return ImportLedger.identifier("journal-item",batch.toString(),filename);}

    /** Invoked only after the transport has authenticated, sealed and verified the whole batch. */
    ObjectNode seal(UUID batch,UUID device,long sequence,Map<String,byte[]> files){
        if(sequence<1||sequence>=9007199254740991L||files.size()>BATCH_FILES)throw ApiFailure.validation();
        var manifest=object();
        for(var file:new TreeMap<>(files).entrySet()){
            // Bad date/YAML is a per-file result; unsafe names are a transport failure.
            if(!file.getKey().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")||file.getValue().length>JournalMarkdown.FILE_BYTES)throw ApiFailure.validation();
            manifest.put(file.getKey(),JournalMarkdown.hash(file.getValue()));
        }
        String fingerprint=hash(device+"\n"+sequence+"\n"+manifest);
        var existing=state.find(JOURNAL_BATCH,batch);
        if(existing.isPresent()){
            if(!existing.get().path("fingerprint").asText().equals(fingerprint))throw ApiFailure.conflict();
            return existing.get();
        }
        if(state.list(JOURNAL_ITEM).stream().filter(item->item.has("source")).count()+files.size()>RETAINED_CANDIDATES)throw new ApiFailure(409,"JOURNAL_PENDING_LIMIT");
        var value=state.insert(JOURNAL_BATCH,batch,object().put("deviceId",device.toString()).put("sequence",sequence)
                .put("fingerprint",fingerprint).put("state",files.isEmpty()?"PROCESSED":"PENDING").put("receivedAt",store.now().toString()).put("files",files.size()));
        for(var file:files.entrySet())state.insert(JOURNAL_ITEM,itemId(batch,file.getKey()),object().put("batchId",batch.toString()).put("deviceId",device.toString())
                .put("sequence",sequence).put("filename",file.getKey()).put("hash",manifest.path(file.getKey()).asText()).put("state","PENDING")
                .put("source",Base64.getEncoder().encodeToString(file.getValue())));
        return value;
    }
    List<UUID> pending(){return state.list(JOURNAL_ITEM).stream().filter(item->item.path("state").asText().equals("PENDING")).map(item->uuid(item.get("id"))).toList();}

    /** Caller revalidates account, sync epoch and device before EACH owner-locked invocation. */
    ObjectNode process(UUID id){
        var item=state.required(JOURNAL_ITEM,id);if(!item.path("state").asText().equals("PENDING"))return summary(item);
        UUID device=uuid(item.get("deviceId"));String filename=item.path("filename").asText();long sequence=item.path("sequence").asLong();
        var baseline=state.find(JOURNAL_FILE,baselineId(device,filename)).orElse(null);
        if(baseline!=null&&sequence<=baseline.path("sequence").asLong())return finish(item,"SUPERSEDED",null,true);
        final JournalMarkdown.Note note;
        try{note=JournalMarkdown.parse(filename,Base64.getDecoder().decode(item.path("source").asText()));}
        catch(JournalMarkdown.Invalid failure){return finish(item,"ERROR",failure.code,true);}
        var current=new DayNotes(store).get(note.date());
        boolean identical=current.path("text").asText().equals(note.label())&&current.path("body").asText().equals(note.body());
        boolean sameSource=baseline!=null&&baseline.path("hash").asText().equals(note.hash());
        if(sameSource){
            // An unchanged external file cannot undo a later calendar edit.
            baseline.put("sequence",sequence);state.update(JOURNAL_FILE,baseline);
            return finish(item,"UNCHANGED",null,true);
        }
        if(!identical&&(baseline==null?current.path("version").asLong()>0:baseline.path("noteVersion").asLong(-1)!=current.path("version").asLong()))
            return finish(item,"CONFLICT","CALENDAR_CHANGED",false);
        if(!identical)current=new DayNotes(store).save(note.date(),etag(current),object().put("text",note.label()).put("body",note.body()));
        accepted(device,filename,sequence,note.hash(),current.path("version").asLong(),baseline);
        return finish(item,identical?"UNCHANGED":"IMPORTED",null,true);
    }
    private void accepted(UUID device,String filename,long sequence,String hash,long noteVersion,ObjectNode baseline){
        var value=baseline==null?object().put("deviceId",device.toString()).put("filename",filename):baseline;
        value.put("sequence",sequence).put("hash",hash).put("noteVersion",noteVersion);
        if(baseline==null)state.insert(JOURNAL_FILE,baselineId(device,filename),value);else state.update(JOURNAL_FILE,value);
    }
    ObjectNode candidate(UUID id){
        var item=state.required(JOURNAL_ITEM,id);if(!item.path("state").asText().equals("CONFLICT"))throw ApiFailure.conflict();
        var note=JournalMarkdown.parse(item.path("filename").asText(),Base64.getDecoder().decode(item.path("source").asText()));
        var result=summary(item);result.put("text",note.label()).put("body",note.body());
        result.set("current",new DayNotes(store).get(note.date()));return result;
    }
    /** Explicit resolution is conditional on both the candidate and current calendar version. */
    ObjectNode resolve(UUID id,String candidateMatch,String noteMatch,boolean incoming){
        var item=state.required(JOURNAL_ITEM,id);ApiReplies.match(item,candidateMatch);
        if(!item.path("state").asText().equals("CONFLICT"))throw ApiFailure.conflict();
        UUID device=uuid(item.get("deviceId"));String filename=item.path("filename").asText();long sequence=item.path("sequence").asLong();
        var baseline=state.find(JOURNAL_FILE,baselineId(device,filename)).orElse(null);
        if(baseline!=null&&sequence<=baseline.path("sequence").asLong())throw new ApiFailure(409,"JOURNAL_SUPERSEDED");
        // A newer completed candidate also takes precedence, even while it is pending/conflicting.
        if(state.list(JOURNAL_ITEM).stream().anyMatch(other->other.path("deviceId").asText().equals(device.toString())&&other.path("filename").asText().equals(filename)&&other.path("sequence").asLong()>sequence))throw new ApiFailure(409,"JOURNAL_SUPERSEDED");
        var note=JournalMarkdown.parse(filename,Base64.getDecoder().decode(item.path("source").asText()));
        var current=new DayNotes(store).get(note.date());ApiReplies.match(current,noteMatch);
        if(incoming)current=new DayNotes(store).save(note.date(),noteMatch,object().put("text",note.label()).put("body",note.body()));
        accepted(device,filename,sequence,note.hash(),current.path("version").asLong(),baseline);
        return finish(item,incoming?"IMPORTED":"KEPT_CALENDAR",null,true);
    }
    private ObjectNode finish(ObjectNode item,String status,String error,boolean discard){
        item.put("state",status).put("processedAt",store.now().toString());if(error!=null)item.put("error",error);else item.remove("error");
        if(discard)item.remove("source");item=state.update(JOURNAL_ITEM,item);
        var batch=state.required(JOURNAL_BATCH,uuid(item.get("batchId")));
        String batchId=item.path("batchId").asText();
        boolean pending=state.list(JOURNAL_ITEM).stream().anyMatch(other->other.path("batchId").asText().equals(batchId)&&other.path("state").asText().equals("PENDING"));
        if(!pending&&batch.path("state").asText().equals("PENDING"))state.update(JOURNAL_BATCH,batch.put("state","PROCESSED").put("processedAt",store.now().toString()));
        return summary(item);
    }
    static ObjectNode summary(ObjectNode item){
        var result=object().put("candidateEtag",etag(item));for(String field:List.of("id","version","batchId","deviceId","filename","state","error","processedAt"))if(item.has(field))result.set(field,item.get(field));return result;
    }
}
