package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Private day-note deletion markers; callers hold the account's owner transaction. */
final class JournalState {
    enum Kind { JOURNAL_DELETE }
    private final ServerStore store;
    JournalState(ServerStore store){this.store=store;}
    Optional<ObjectNode> find(Kind kind,UUID id){return store.find(kind.name(),id).map(value->((ObjectNode)value).deepCopy());}
    ObjectNode insert(Kind kind,UUID id,ObjectNode fields){
        var value=fields.deepCopy().put("id",id.toString()).put("ownerId",store.owner()).put("version",0);
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),kind.name(),id,0,value.toString());return value;
    }
    ObjectNode update(Kind kind,ObjectNode fields){
        if(!store.owner().equals(fields.path("ownerId").asText()))throw ApiFailure.notFound();
        long version=fields.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=fields.deepCopy().put("version",version+1);
        if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),kind.name(),uuid(value.get("id")),version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        return value;
    }
}
