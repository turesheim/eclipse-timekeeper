package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Transactional outbox for automatic subscriptions. No provider I/O runs here. */
final class TaskProviderIntents {
    static final String KIND="TASK_SYNC_INTENT";
    private static final String ISSUES="TASK_SYNC_ISSUE";
    private TaskProviderIntents(){}

    static void changed(ServerStore store,String taskId){
        var found=store.find("TASK",UUID.fromString(taskId));if(found.isEmpty())return;var task=found.get();
        var homes=new LinkedHashSet<UUID>();if(task.hasNonNull("syncListId"))homes.add(uuid(task.get("syncListId")));
        for(var reference:task.path("externalReferences"))if(reference.hasNonNull("taskListId"))homes.add(uuid(reference.get("taskListId")));
        for(UUID home:homes){
            var list=store.find("TASK_LIST",home);if(list.isEmpty())continue;
            UUID connectionId=uuid(list.get().get("connectionId"));var auth=store.find(TaskProviderConnections.AUTH,connectionId);if(auth.isEmpty())continue;
            // Record while paused, disconnected or unsubscribed too. Consent controls execution, not durability.
            UUID id=UUID.nameUUIDFromBytes(("dagsverk-task-intent:"+taskId+":"+home).getBytes(StandardCharsets.UTF_8));
            JsonNode linked=null;for(var ref:task.path("externalReferences"))if(ref.path("taskListId").asText().equals(home.toString()))linked=ref;
            boolean pending=linked==null?task.path("syncListId").asText().equals(home.toString())&&!task.path("deleted").asBoolean():task.path("deleted").asBoolean()?!linked.path("providerData").path("payload").path("observation").path("deleted").asBoolean()&&!linked.path("providerData").path("payload").path("observation").has("@removed"):task.hasNonNull("syncListId")&&(!task.path("syncListId").asText().equals(home.toString())||TaskProviderWrites.differs(task,linked));
            if(!pending){clear(store,UUID.fromString(taskId),home);continue;}
            var state=new TaskProviderConnections(store);var before=store.find(KIND,id);
            var intent=before.map(value->((ObjectNode)value).deepCopy()).orElseGet(JsonValues::object);
            intent.put("connectionId",connectionId.toString()).put("listId",home.toString()).put("taskId",taskId)
                .put("taskVersion",task.path("version").asLong()).put("operationId",UUID.randomUUID().toString())
                .put("generation",auth.get().path("generation").asText()).put("changedAt",store.now().toString());
            intent.set("desired",task.deepCopy());
            if(before.isEmpty())state.insert(KIND,id,intent);else state.save(KIND,intent);
            if(store.observingProvider())continue;
            var credentials=((ObjectNode)auth.get()).deepCopy();credentials.put("requested",true).put("intentRevision",credentials.path("intentRevision").asLong()+1);
            if(!credentials.path("paused").asBoolean()&&!credentials.path("blocked").asBoolean()&&!credentials.hasNonNull("lease"))credentials.put("nextRefresh",store.now().toString());
            state.save(TaskProviderConnections.AUTH,credentials);
        }
    }

    static void clear(ServerStore store,UUID task,UUID list){
        UUID id=UUID.nameUUIDFromBytes(("dagsverk-task-intent:"+task+":"+list).getBytes(StandardCharsets.UTF_8));
        store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),KIND,id);
    }
    static void warnings(ServerStore store,UUID connection,UUID list,JsonNode task,JsonNode warnings){
        var relevant=array();for(var warning:warnings)if(Set.of("TEXT_ONLY","FIELD_LIMIT","STATUS_UNSUPPORTED","ASSIGNED_TASK_NOTES","INVALID_DATE","INVALID_TIME","HTML_ONLY","TIMED_DATE_REQUIRED","PRIORITY_UNSUPPORTED","MOVE_UNSUPPORTED","SPECIAL_TASK_MOVE","DELETE_AT_ASSIGNMENT_SOURCE","TASK_DEPENDENCY").contains(warning.path("reason").asText()))relevant.add(warning.deepCopy());
        UUID id=UUID.nameUUIDFromBytes(("dagsverk-task-issue:"+task.path("id").asText()+":"+list).getBytes(StandardCharsets.UTF_8));var old=store.find(ISSUES,id);
        if(relevant.isEmpty()){if(old.isPresent())store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),ISSUES,id);return;}
        var value=old.map(row->((ObjectNode)row).deepCopy()).orElseGet(JsonValues::object);
        if(old.isPresent()&&value.path("warnings").equals(relevant)&&value.path("title").equals(task.path("summary")))return;
        value.put("connectionId",connection.toString()).put("listId",list.toString()).put("taskId",task.path("id").asText()).put("title",task.path("summary").asText());value.set("warnings",relevant);
        var state=new TaskProviderConnections(store);if(old.isEmpty())state.insert(ISSUES,id,value);else state.save(ISSUES,value);
    }
    static void clearDependency(ServerStore store,UUID list,UUID task){
        UUID id=UUID.nameUUIDFromBytes(("dagsverk-task-issue:"+task+":"+list).getBytes(StandardCharsets.UTF_8));var found=store.find(ISSUES,id);if(found.isEmpty())return;var warnings=array();for(var warning:found.get().path("warnings"))if(!warning.path("reason").asText().equals("TASK_DEPENDENCY"))warnings.add(warning.deepCopy());
        if(warnings.isEmpty())store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),ISSUES,id);else{var row=((ObjectNode)found.get()).deepCopy();row.set("warnings",warnings);new TaskProviderConnections(store).save(ISSUES,row);}
    }
    static com.fasterxml.jackson.databind.node.ArrayNode issues(ServerStore store,JsonNode auth){
        var result=array();for(var row:store.list(ISSUES))if(row.path("connectionId").equals(auth.path("id"))&&store.find("TASK",uuid(row.get("taskId"))).isPresent()){
            var list=store.find("TASK_LIST",uuid(row.get("listId")));if(list.isEmpty())continue;boolean selected=false;for(var remote:auth.path("selected"))if(remote.equals(list.get().path("remoteListId")))selected=true;
            if(selected){var issue=object();for(String field:List.of("taskId","title","warnings"))issue.set(field,row.path(field).deepCopy());result.add(issue);}
        }return result;
    }
}
