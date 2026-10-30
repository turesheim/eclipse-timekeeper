package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** All methods run in the caller's owner transaction, including first indexing. */
final class VaultCatalog {
    private final ServerStore store;
    private final VaultLimits limits;
    private final VaultIndex index;
    private final VaultState state;
    VaultCatalog(ServerStore store,VaultLimits limits){this.store=store;this.limits=limits;index=new VaultIndex(store);state=new VaultState(store,limits);}
    void initialize(){
        if(state.get().path("initialized").asBoolean())return;
        index.root();var settings=new VaultSettings(store).get();
        for(String field:List.of("journalFolder","eventsFolder","assetsFolder"))folders(new VaultPath(settings.path(field).asText()),true);
        for(String type:VaultSources.LEGACY)store.scan(type,value->{
            var ref=VaultSources.fromStored(type,value);var source=new VaultSources(store,limits,ref);var snapshot=source.snapshot();
            if(snapshot.deleted())return;
            var entry=index.referenced(ref.type(),ref.id());
            if(entry==null){entry=createBinding(ref);state.changed(snapshot.byteLength(),1,true);}
            index.content(uuid(entry.path("id")),snapshot,snapshot.byteLength());
        });
        state.initialized();
    }
    ObjectNode folders(VaultPath path,boolean backfill){
        var current=index.root();VaultPath cursor=VaultPath.root();
        for(String name:path.segments()){
            cursor=cursor.resolve(name);var next=index.at(cursor,false);
            if(next==null){if(!backfill)state.check(0,1);next=index.create(cursor,VaultIndex.Kind.FOLDER,null,null,null);state.changed(0,1,backfill);}
            if(!next.path("kind").asText().equals(VaultIndex.Kind.FOLDER.name()))throw ApiFailure.conflict();current=next;
        }
        return current;
    }
    VaultPath path(VaultSources.Ref ref){
        var settings=new VaultSettings(store).get();String folder=switch(ref.type()){
            case DayNotes.TYPE,WeekNotes.TYPE->"journalFolder";case EventNotes.TYPE->"eventsFolder";case JournalAssets.TYPE->"assetsFolder";
            default->throw new IllegalArgumentException("Not a bound journal resource");
        };
        String filename=switch(ref.type()){case DayNotes.TYPE,WeekNotes.TYPE->ref.key()+".md";default->ref.key();};
        return new VaultPath(settings.path(folder).asText()).resolve(filename);
    }
    private ObjectNode createBinding(VaultSources.Ref ref){var target=path(ref);return index.create(target,VaultSources.kind(ref),ref.type(),ref.id(),ref.key());}

    /** Called before changing canonical bytes; inactive until the collection exists. */
    static Mutation before(ServerStore store,VaultSources.Ref ref,byte[] next){
        var catalog=new VaultCatalog(store,store.vaultLimits());
        if(!catalog.state.get().path("initialized").asBoolean())return null;
        var entry=catalog.index.referenced(ref.type(),ref.id());
        var source=new VaultSources(store,store.vaultLimits(),ref);var previous=source.snapshot();
        long retained=entry==null?0:entry.path("retainedBytes").asLong();
        int maximum=ref.type().equals(JournalAssets.TYPE)?catalog.limits.fileBytes():Math.min(catalog.limits.fileBytes(),catalog.limits.markdownBytes());
        if(next!=null&&next.length>maximum&&next.length>retained)throw new ApiFailure(413,"VAULT_FILE_TOO_LARGE");
        // Deletion retains the original bytes, so trash still consumes the quota.
        long length=next==null?retained:next.length;
        catalog.state.check(length-retained,entry==null?1:0);
        if(entry==null){var conflict=catalog.index.at(catalog.path(ref),false);if(conflict!=null)throw ApiFailure.conflict();}
        else if(entry.path("deleted").asBoolean()&&next!=null){
            var conflict=catalog.index.at(new VaultPath(entry.path("path").asText()),false);
            if(conflict!=null&&!conflict.path("id").equals(entry.path("id")))throw ApiFailure.conflict();
        }
        byte[] recovery=next==null&&!previous.deleted()?source.read():null;
        return new Mutation(catalog,ref,entry,previous,retained,length,recovery);
    }
    record Mutation(VaultCatalog catalog,VaultSources.Ref ref,ObjectNode entry,VaultSources.Snapshot previous,long retained,long length,byte[] recovery){
        void complete(){
            var next=new VaultSources(catalog.store,catalog.limits,ref).snapshot();
            if(next.tag().equals(previous.tag()))return;
            var target=entry==null?catalog.createBinding(ref):entry;UUID id=uuid(target.path("id"));
            if(next.deleted()){
                if(recovery!=null)new VaultRecycle(catalog.store).retain(id,next.version(),recovery);
                catalog.index.tombstone(id,UUID.randomUUID());
            }else{
                if(target.path("deleted").asBoolean())catalog.index.restore(id);
                new VaultRecycle(catalog.store).discard(id);
            }
            catalog.index.content(id,next,length);catalog.state.changed(length-retained,entry==null?1:0,false);
        }
    }
    static void complete(Mutation mutation){if(mutation!=null)mutation.complete();}
}
