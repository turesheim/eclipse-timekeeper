package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A recovery snapshot is never a second live version of a journal resource. */
final class VaultRecycle {
    static final String TYPE="VAULT_RECYCLE";
    private final ServerStore store;
    VaultRecycle(ServerStore store){this.store=store;}
    void retain(UUID entry,long deletedVersion,byte[] bytes){
        var previous=store.find(TYPE,entry).orElse(null);long version=previous==null?0:previous.path("version").asLong();
        var value=object().put("id",entry.toString()).put("ownerId",store.owner()).put("version",version+1)
                .put("deletedVersion",deletedVersion).put("byteLength",bytes.length)
                .put("sha256",ArchiveJournalFiles.digest(bytes)).put("data",Base64.getEncoder().encodeToString(bytes));
        if(previous==null)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),TYPE,entry,version+1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),TYPE,entry,version)!=1)throw ApiFailure.conflict();
    }
    byte[] read(UUID entry,long deletedVersion){
        var value=store.find(TYPE,entry).orElseThrow(ApiFailure::notFound);
        if(value.path("deletedVersion").asLong()!=deletedVersion)throw ApiFailure.conflict();
        final byte[] bytes;
        try{bytes=Base64.getDecoder().decode(value.path("data").asText());}catch(IllegalArgumentException invalid){throw new IllegalStateException("Invalid vault recovery data");}
        if(bytes.length!=value.path("byteLength").asLong()||!ArchiveJournalFiles.digest(bytes).equals(value.path("sha256").asText()))throw new IllegalStateException("Invalid vault recovery data");
        return bytes;
    }
    void discard(UUID entry){store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),TYPE,entry);}
}
