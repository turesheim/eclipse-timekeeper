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
        var task=store.find("TASK",UUID.fromString(taskId));if(task.isEmpty())return;
        for(var reference:task.get().path("externalReferences")){
            if(!reference.hasNonNull("taskListId"))continue;
            var list=store.find("TASK_LIST",uuid(reference.get("taskListId")));if(list.isEmpty())continue;
            var connection=store.find("TASK_CONNECTION",uuid(list.get().get("connectionId")));if(connection.isEmpty())continue;
            var auth=store.find(TaskProviderConnections.AUTH,uuid(connection.get().get("id")));
            if(auth.isEmpty()||!auth.get().path("automatic").asBoolean()||!TaskProviderConnections.writable(auth.get(),connection.get())
                    ||!reference.path("providerId").equals(connection.get().path("providerId"))
                    ||!reference.path("repositoryId").asText().equals(TaskCloudPull.repository(connection.get().path("remoteAccountId").asText(),list.get().path("remoteListId").asText())))continue;
            boolean selected=false;for(var value:auth.get().path("selected"))if(value.equals(list.get().path("remoteListId")))selected=true;if(!selected)continue;
            // Import/acknowledgement events are not outbound edits. Compare the final committed projection.
            var baseline=reference.path("baseline").path("fields");boolean differs=false;
            for(String field:List.of("summary","body","status","completedAt","scheduledDate"))if(baseline.has(field)&&!task.get().path(field).equals(baseline.get(field)))differs=true;
            if(!differs){clear(store,UUID.fromString(taskId),uuid(list.get().get("id")));continue;}
            UUID id=UUID.nameUUIDFromBytes(("dagsverk-task-intent:"+taskId+":"+list.get().path("id").asText()).getBytes(StandardCharsets.UTF_8));
            var state=new TaskProviderConnections(store);var before=store.find(KIND,id);
            var intent=before.map(value->((ObjectNode)value).deepCopy()).orElseGet(JsonValues::object);
            intent.put("connectionId",connection.get().path("id").asText()).put("listId",list.get().path("id").asText())
                    .put("taskId",taskId).put("taskVersion",task.get().path("version").asLong()).put("operationId",UUID.randomUUID().toString())
                    .put("generation",auth.get().path("generation").asText()).put("changedAt",store.now().toString());
            var desired=intent.putObject("desired");for(String field:List.of("summary","body","status","completedAt","scheduledDate"))desired.set(field,task.get().path(field).deepCopy());
            intent.set("reference",reference.deepCopy());
            if(before.isEmpty())state.insert(KIND,id,intent);else state.save(KIND,intent);
            // A live lease keeps its deadline; finish() notices this durable request.
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
        var relevant=array();for(var warning:warnings)if(Set.of("TEXT_ONLY","FIELD_LIMIT","STATUS_UNSUPPORTED","ASSIGNED_TASK_NOTES","INVALID_DATE","INVALID_TIME").contains(warning.path("reason").asText()))relevant.add(warning.deepCopy());
        UUID id=UUID.nameUUIDFromBytes(("dagsverk-task-issue:"+task.path("id").asText()+":"+list).getBytes(StandardCharsets.UTF_8));var old=store.find(ISSUES,id);
        if(relevant.isEmpty()){if(old.isPresent())store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),ISSUES,id);return;}
        var value=old.map(row->((ObjectNode)row).deepCopy()).orElseGet(JsonValues::object);
        if(old.isPresent()&&value.path("warnings").equals(relevant)&&value.path("title").equals(task.path("summary")))return;
        value.put("connectionId",connection.toString()).put("listId",list.toString()).put("taskId",task.path("id").asText()).put("title",task.path("summary").asText());value.set("warnings",relevant);
        var state=new TaskProviderConnections(store);if(old.isEmpty())state.insert(ISSUES,id,value);else state.save(ISSUES,value);
    }
    static com.fasterxml.jackson.databind.node.ArrayNode issues(ServerStore store,JsonNode auth){
        var result=array();for(var row:store.list(ISSUES))if(row.path("connectionId").equals(auth.path("id"))&&store.find("TASK",uuid(row.get("taskId"))).isPresent()){
            var list=store.find("TASK_LIST",uuid(row.get("listId")));if(list.isEmpty())continue;boolean selected=false;for(var remote:auth.path("selected"))if(remote.equals(list.get().path("remoteListId")))selected=true;
            if(selected){var issue=object();for(String field:List.of("taskId","title","warnings"))issue.set(field,row.path(field).deepCopy());result.add(issue);}
        }return result;
    }
}
