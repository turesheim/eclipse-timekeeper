package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.UUID;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Owner-atomic usage and namespace generation, including equal-size content changes. */
final class VaultState {
    static final String TYPE="VAULT_STATE";
    static final UUID ID=ImportLedger.identifier("vault","state");
    private final ServerStore store;
    private final VaultLimits limits;
    VaultState(ServerStore store,VaultLimits limits){this.store=store;this.limits=limits;}
    ObjectNode get(){return store.find(TYPE,ID).map(value->((ObjectNode)value).deepCopy()).orElseGet(()->object()
            .put("id",ID.toString()).put("ownerId",store.owner()).put("version",0).put("totalBytes",0).put("entries",0).put("initialized",false));}
    void check(long bytes,int entries){check(get(),bytes,entries,false);}
    private void check(ObjectNode current,long bytes,int entries,boolean backfill){
        final long nextBytes,nextEntries;
        try{nextBytes=Math.addExact(current.path("totalBytes").asLong(),bytes);nextEntries=Math.addExact(current.path("entries").asLong(),entries);}
        catch(ArithmeticException overflow){throw new ApiFailure(507,"VAULT_QUOTA");}
        if(nextBytes<0||nextEntries<0)throw new IllegalStateException("Invalid vault usage counters");
        // Existing data may predate deployment limits. Reductions and metadata
        // operations remain possible; only further growth is refused.
        if(!backfill&&(bytes>0&&nextBytes>limits.totalBytes()||entries>0&&nextEntries>limits.entries()))throw new ApiFailure(507,"VAULT_QUOTA");
    }
    ObjectNode changed(long bytes,int entries,boolean backfill){
        var current=get();check(current,bytes,entries,backfill);
        return persist(current,current.deepCopy().put("totalBytes",current.path("totalBytes").asLong()+bytes)
                .put("entries",current.path("entries").asLong()+entries));
    }
    ObjectNode initialized(){var current=get();return current.path("initialized").asBoolean()?current:persist(current,current.deepCopy().put("initialized",true));}
    void ensureIdentity(){var current=get();if(current.path("version").asLong()>0&&!current.hasNonNull("collectionId"))persist(current,current.deepCopy());}
    void resetForImport(){var current=get();persist(current,current.deepCopy().put("totalBytes",0).put("entries",0).put("initialized",false));}
    private ObjectNode persist(ObjectNode previous,ObjectNode value){
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        if(!value.hasNonNull("collectionId"))value.put("collectionId",UUID.randomUUID().toString());
        value.put("version",version+1).put("modifiedAt",store.now().toString());
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),TYPE,ID,version+1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),TYPE,ID,version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        return value;
    }
}
