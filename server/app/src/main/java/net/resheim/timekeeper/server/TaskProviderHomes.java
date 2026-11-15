package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import net.resheim.eclipse.timekeeper.domain.*;

/** Owned, explicitly subscribed task homes. Project mappings never establish membership. */
final class TaskProviderHomes {
    private final ServerStore store;
    TaskProviderHomes(ServerStore store){this.store=store;}
    List<TaskList> lists(){
        var result=new ArrayList<TaskList>();
        for(var row:store.list("TASK_LIST"))if(eligible(row))result.add((TaskList)decode("TASK_LIST",row));
        return List.copyOf(result);
    }
    boolean eligible(JsonNode list){
        var connection=store.find("TASK_CONNECTION",uuid(list.get("connectionId")));if(connection.isEmpty())return false;
        var auth=store.find(TaskProviderConnections.AUTH,uuid(connection.get().get("id")));if(auth.isEmpty())return false;
        return connection.get().path("enabled").asBoolean()&&TaskProviderConnections.writable(auth.get(),connection.get())
                &&list.path("present").asBoolean()&&list.path("writable").asBoolean()&&auth.get().path("automatic").asBoolean()
                &&java.util.stream.StreamSupport.stream(auth.get().path("selected").spliterator(),false).anyMatch(id->id.equals(list.path("remoteListId")));
    }
    /** Only the assigned home (or one unambiguous source while moving) may drive a task. */
    JsonNode reference(JsonNode task){
        if(!task.hasNonNull("syncListId"))return null;
        var home=store.find("TASK_LIST",uuid(task.get("syncListId")));if(home.isEmpty())return null;
        var candidates=references(task,uuid(home.get().get("connectionId")));
        var atHome=candidates.stream().filter(ref->ref.path("taskListId").equals(home.get().get("id"))).toList();
        if(atHome.size()==1)return atHome.getFirst();return atHome.isEmpty()&&candidates.size()==1?candidates.getFirst():null;
    }
    private List<JsonNode> references(JsonNode task,UUID connectionId){
        var connection=store.find("TASK_CONNECTION",connectionId);if(connection.isEmpty())return List.of();var result=new ArrayList<JsonNode>();
        for(var ref:task.path("externalReferences"))if(ref.hasNonNull("taskListId")){
            var list=store.find("TASK_LIST",uuid(ref.get("taskListId")));if(list.isEmpty()||!list.get().path("connectionId").asText().equals(connectionId.toString())||!ref.path("providerId").equals(connection.get().path("providerId")))continue;
            if(Set.of("google","microsoft").contains(ref.path("providerId").asText())&&!ref.path("repositoryId").asText().equals(TaskCloudPull.repository(connection.get().path("remoteAccountId").asText(),list.get().path("remoteListId").asText())))continue;
            result.add(ref);
        }return result;
    }
    void validate(Optional<Task> current,Optional<UUID> home){
        if(home.isEmpty()||current.isPresent()&&current.get().syncListId().equals(home))return;
        var list=store.find("TASK_LIST",home.get()).orElseThrow(ApiFailure::notFound);
        if(store.observingProvider())return;
        if(!eligible(list))throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
        if(current.isPresent()){
            var task=current.get();
            if(task.syncListId().isPresent()){
                var prior=store.find("TASK_LIST",task.syncListId().get()).orElseThrow(ApiFailure::notFound);
                if(!prior.path("connectionId").equals(list.path("connectionId")))throw new ApiFailure(422,"TASK_PROVIDER_ACCOUNT_TRANSFER_UNSUPPORTED");
                if(references(snapshot(store.owner(),task),uuid(list.get("connectionId"))).size()>1)throw new ApiFailure(409,"TASK_PROVIDER_IDENTITY_REQUIRED");
            }else{
                boolean sameAccount=false,otherAccount=false;
                for(var ref:task.externalReferences())if(ref.taskListId().isPresent()&&Set.of("google","microsoft").contains(ref.providerId())){
                    var prior=store.find("TASK_LIST",ref.taskListId().get());if(prior.isPresent()){if(prior.get().path("connectionId").equals(list.path("connectionId")))sameAccount=true;else otherAccount=true;}
                }
                if(otherAccount&&!sameAccount)throw new ApiFailure(422,"TASK_PROVIDER_ACCOUNT_TRANSFER_UNSUPPORTED");
            }
        }
    }
}
