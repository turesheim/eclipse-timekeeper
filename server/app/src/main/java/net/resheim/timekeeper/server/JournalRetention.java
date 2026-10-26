package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JournalState.Kind.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounds transient copies and metadata; accepted baselines and unresolved candidates survive. */
final class JournalRetention {
    private final ServerStore store;
    private final JournalState state;
    JournalRetention(ServerStore store){this.store=store;state=new JournalState(store);}
    void cleanup(JournalConfiguration.Deployment deployment){
        var leases=state.list(JOURNAL_LEASE);
        for(var lease:leases)if(lease.path("state").asText().equals("RECEIVING")&&!Instant.parse(lease.path("expiresAt").asText()).isAfter(store.now()))
            state.update(JOURNAL_LEASE,lease.put("state","ABORTED"));
        clearStages(deployment,leases);
        var items=state.list(JOURNAL_ITEM);
        var disposable=items.stream().filter(item->!item.has("sourceId")&&!item.path("state").asText().equals("PENDING")).toList();
        trim(JOURNAL_ITEM,disposable,200,"processedAt");
        var batchIds=new HashSet<String>();state.list(JOURNAL_ITEM).forEach(item->batchIds.add(item.path("batchId").asText()));
        state.list(JOURNAL_OFFER).forEach(offer->batchIds.add(offer.path("batchId").asText()));
        trim(JOURNAL_BATCH,state.list(JOURNAL_BATCH).stream().filter(batch->!batchIds.contains(batch.path("id").asText())).toList(),200,"receivedAt");
        var retainedBatches=new HashSet<String>();state.list(JOURNAL_BATCH).forEach(batch->retainedBatches.add(batch.path("id").asText()));
        trim(JOURNAL_LEASE,state.list(JOURNAL_LEASE).stream().filter(lease->!lease.path("state").asText().equals("RECEIVING")&&!retainedBatches.contains(lease.path("id").asText())).toList(),200,"createdAt");
        trim(JOURNAL_AUDIT,state.list(JOURNAL_AUDIT),2000,"occurredAt");
    }
    private void trim(JournalState.Kind kind,List<ObjectNode> records,int retained,String time){
        records.stream().sorted(Comparator.comparing((ObjectNode value)->Instant.parse(value.path(time).asText("1970-01-01T00:00:00Z"))).reversed()).skip(retained).forEach(value->state.remove(kind,value));
    }
    private void clearStages(JournalConfiguration.Deployment deployment,List<ObjectNode> leases){
        try{
            JournalConfiguration.privatePath(deployment.incoming(),true);Path account=deployment.incoming().resolve(hash(store.owner()));
            if(!Files.exists(account,LinkOption.NOFOLLOW_LINKS))return;JournalConfiguration.privatePath(account,true);
            var live=new HashSet<String>();for(var lease:leases)if(lease.path("state").asText().equals("RECEIVING")&&Instant.parse(lease.path("expiresAt").asText()).isAfter(store.now()))live.add(lease.path("id").asText());
            try(var folders=Files.list(account)){
                for(var folder:folders.toList()){
                    String name=folder.getFileName().toString();if(!name.matches("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}"))throw new IOException();
                    if(live.contains(name))continue;
                    // Owner serialization means a folder with no committed live lease is orphaned.
                    JournalConfiguration.privatePath(folder,true);
                    try(var files=Files.list(folder)){
                        for(var file:files.toList()){
                            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IOException();
                            Files.delete(file);
                        }
                    }
                    Files.delete(folder);
                }
            }
        }catch(IOException unsafe){throw new ApiFailure(503,"JOURNAL_PRIVATE_STORAGE_UNAVAILABLE");}
    }
}
