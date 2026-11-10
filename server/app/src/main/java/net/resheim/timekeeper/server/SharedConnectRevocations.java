package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Local disconnect is immediate; a durable private queue revokes unreachable shared grants later. */
final class SharedConnectRevocations {
    static final String KIND="CONNECT_REVOKE";
    private final ServerStore store;
    SharedConnectRevocations(ServerStore store){this.store=store;}
    void enqueue(ObjectNode credentials){
        if(!credentials.hasNonNull("brokerEndpoint"))return;
        new TaskProviderConnections(store).insert(KIND,UUID.randomUUID(),object().put("endpoint",credentials.path("brokerEndpoint").asText()).put("credential",credentials.path("refreshToken").asText()).put("nextAttempt",store.now().toString()).put("attempts",0));
    }
    ObjectNode claim(UUID id){
        var found=store.find(KIND,id);if(found.isEmpty())return null;var value=((ObjectNode)found.get()).deepCopy();
        if(value.hasNonNull("leaseUntil")&&Instant.parse(value.path("leaseUntil").asText()).isAfter(store.now())||Instant.parse(value.path("nextAttempt").asText()).isAfter(store.now()))return null;
        value.put("leaseUntil",store.now().plusSeconds(60).toString()).put("lease",UUID.randomUUID().toString());return new TaskProviderConnections(store).save(KIND,value);
    }
    void finish(ObjectNode claimed,boolean complete){
        var found=store.find(KIND,uuid(claimed.get("id")));if(found.isEmpty()||!found.get().path("lease").equals(claimed.path("lease")))return;var value=((ObjectNode)found.get()).deepCopy();
        if(complete){store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=? AND version=?",store.owner(),KIND,uuid(value.get("id")),value.path("version").asLong());return;}
        int attempts=Math.min(10,value.path("attempts").asInt()+1);value.put("attempts",attempts).put("nextAttempt",store.now().plusSeconds(Math.min(3600,30L<<attempts)).toString()).putNull("lease").putNull("leaseUntil");new TaskProviderConnections(store).save(KIND,value);
    }
}
