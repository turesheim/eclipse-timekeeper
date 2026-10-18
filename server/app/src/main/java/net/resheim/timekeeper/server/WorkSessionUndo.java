package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;

/** Short-lived, owner-scoped snapshots; recording and restoring share the mutation transaction. */
final class WorkSessionUndo {
    static final long SECONDS=600;
    private final ServerStore store;
    WorkSessionUndo(ServerStore store) {this.store=store;}
    static boolean requested(String header,String type) {
        if(header==null)return false;
        if(!header.equals("true") || !type.equals("WORK_SESSION"))throw ApiFailure.badRequest();
        return true;
    }
    ApiReplies offer(JsonNode before,JsonNode after,ApiReplies reply) {
        var actions=new ArrayList<>(store.list("UNDO"));actions.sort(Comparator.comparing(value->value.path("expiresAt").asText()));
        for(var action:actions)if(Instant.parse(action.path("expiresAt").asText()).compareTo(store.now())<=0 || actions.indexOf(action)<actions.size()-49)
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type='UNDO' AND id=?",store.owner(),uuid(action.get("id")));
        UUID id=UUID.randomUUID();var action=object().put("ownerId",store.owner()).put("id",id.toString()).put("version",0)
                .put("expiresAt",store.now().plusSeconds(SECONDS).toString());
        action.set("before",before.deepCopy());action.set("after",after==null ? com.fasterxml.jackson.databind.node.NullNode.instance:after.deepCopy());
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'UNDO',?,0,?)",store.owner(),id,action.toString());
        return reply.withUndo(object().put("id",id.toString()).put("seconds",SECONDS));
    }
    ApiReplies apply(UUID id) {
        var action=(ObjectNode)store.find("UNDO",id).orElseThrow(ApiFailure::notFound);
        if(action.has("result"))return new ApiReplies(200,action.get("result"),null,null);
        if(!store.now().isBefore(Instant.parse(action.path("expiresAt").asText())))throw new ApiFailure(410,"UNDO_EXPIRED");
        var before=action.get("before");var after=action.get("after");UUID resource=uuid(before.get("id"));
        var current=store.find("WORK_SESSION",resource).orElse(null);
        if(after.isNull() ? current!=null : !after.equals(current))throw new ApiFailure(409,"UNDO_CONFLICT");
        var session=(WorkSession)decode("WORK_SESSION",before);
        if(!store.owner().equals(session.ownerId().value()) || session.start().isAfter(store.now()))throw new ApiFailure(409,"UNDO_CONFLICT");
        if(session.end().isEmpty() && store.list("WORK_SESSION").stream().anyMatch(value->!value.get("id").equals(before.get("id")) && value.path("end").isNull()))throw new ApiFailure(409,"UNDO_CONFLICT");
        if(store.find("CLIENT",uuid(before.get("clientId"))).isEmpty())throw new ApiFailure(409,"UNDO_CONFLICT");
        if(before.hasNonNull("projectId")) {
            var project=store.find("PROJECT",uuid(before.get("projectId"))).orElseThrow(()->new ApiFailure(409,"UNDO_CONFLICT"));
            if(!project.path("clientId").equals(before.path("clientId")))throw new ApiFailure(409,"UNDO_CONFLICT");
        }
        for(var label:before.path("labelIds"))if(store.find("LABEL",uuid(label)).isEmpty())throw new ApiFailure(409,"UNDO_CONFLICT");
        long version=(current==null ? before:current).get("version").asLong();
        if(version>=9007199254740991L)throw new ApiFailure(409,"UNDO_CONFLICT");
        var restored=((ObjectNode)before).deepCopy().put("version",version+1);
        if(current==null)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'WORK_SESSION',?,?,?)",store.owner(),resource,version+1,restored.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type='WORK_SESSION' AND id=? AND version=?",version+1,restored.toString(),store.owner(),resource,version)!=1)throw new ApiFailure(409,"UNDO_CONFLICT");
        store.update("DELETE FROM tk_relation WHERE owner_id=? AND from_type='WORK_SESSION' AND from_id=?",store.owner(),resource);
        link(resource,"client","CLIENT",before.get("clientId"));
        if(before.hasNonNull("projectId"))link(resource,"project","PROJECT",before.get("projectId"));
        for(var label:before.path("labelIds"))link(resource,"label","LABEL",label);
        store.change("WORK_SESSION",current==null ? "CREATED":"UPDATED",resource.toString());
        var result=object().put("revision",store.revision());result.set("session",restored);
        action.set("result",result.deepCopy());action.remove(List.of("before","after"));
        store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type='UNDO' AND id=?",action.toString(),store.owner(),id);
        return new ApiReplies(200,result,null,null);
    }
    private void link(UUID id,String kind,String type,JsonNode target) {
        store.update("INSERT INTO tk_relation(owner_id,from_type,from_id,kind,to_type,to_id) VALUES(?,'WORK_SESSION',?,?,?,?)",store.owner(),id,kind,type,uuid(target));
    }
}
