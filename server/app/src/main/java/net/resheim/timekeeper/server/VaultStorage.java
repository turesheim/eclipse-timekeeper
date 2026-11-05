package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Read-only, owner-private logical usage. Opening Storage never initializes a namespace. */
final class VaultStorage {
    private final ServerStore store;
    private final VaultLimits limits;
    private final Map<String,long[]> categories=new LinkedHashMap<>();
    private long bytes,entries,files,folders,largestMarkdown,largestAttachment;
    VaultStorage(ServerStore store,VaultLimits limits){this.store=store;this.limits=limits;for(String kind:List.of("NOTES","DAY","WEEK","EVENT","ATTACHMENTS","TRASH"))categories.put(kind,new long[2]);}
    private void add(String kind,boolean deleted,long retained){
        entries++;bytes=Math.addExact(bytes,retained);
        if(kind.equals("FOLDER")){folders++;if(!deleted)return;}else files++;
        String category=deleted?"TRASH":switch(kind){case "MARKDOWN"->"NOTES";case "DAY","WEEK","EVENT"->kind;default->"ATTACHMENTS";};
        var usage=categories.get(category);usage[0]=Math.addExact(usage[0],retained);usage[1]++;
        if(Set.of("MARKDOWN","DAY","WEEK","EVENT").contains(kind))largestMarkdown=Math.max(largestMarkdown,retained);
        else if(!kind.equals("FOLDER"))largestAttachment=Math.max(largestAttachment,retained);
    }
    ObjectNode get(){
        var seen=new HashSet<String>();
        store.scan(VaultIndex.ENTRY,value->{
            if(value.path("id").asText().equals(VaultIndex.ROOT.toString()))return;
            if(value.has("sourceId"))seen.add(value.path("sourceType").asText()+"/"+value.path("sourceId").asText());
            add(value.path("kind").asText(),value.path("deleted").asBoolean(),value.path("retainedBytes").asLong());
        });
        var state=new VaultState(store,limits).get();
        if(!state.path("initialized").asBoolean()){
            // Legacy journal/event content is still real storage before its first Notes visit.
            for(String type:VaultSources.LEGACY)store.scan(type,value->{
                if(seen.contains(type+"/"+value.path("id").asText()))return;
                var ref=VaultSources.fromStored(type,value);var snapshot=new VaultSources(store,limits,ref).snapshot();
                if(!snapshot.deleted())add(VaultSources.kind(ref).name(),false,snapshot.byteLength());
            });
        }
        var result=object().put("ownerId",store.owner()).put("largestMarkdownBytes",largestMarkdown).put("largestAttachmentBytes",largestAttachment);
        result.set("limits",limits.snapshot());
        result.set("usage",object().put("bytes",state.path("initialized").asBoolean()?state.path("totalBytes").asLong():bytes)
                .put("entries",state.path("initialized").asBoolean()?state.path("entries").asLong():entries).put("files",files).put("folders",folders));
        var values=result.putArray("categories");categories.forEach((kind,usage)->values.add(object().put("kind",kind).put("bytes",usage[0]).put("entries",usage[1])));return result;
    }
}
