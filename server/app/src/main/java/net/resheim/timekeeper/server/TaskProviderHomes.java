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
    void validate(Optional<Task> current,Optional<UUID> home){
        if(home.isEmpty()||current.isPresent()&&current.get().syncListId().equals(home))return;
        var list=store.find("TASK_LIST",home.get()).orElseThrow(ApiFailure::notFound);
        if(store.observingProvider())return;
        if(!eligible(list))throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
        if(current.isPresent()){
            var task=current.get();
            for(var ref:task.externalReferences())if(ref.taskListId().isPresent()){
                var prior=store.find("TASK_LIST",ref.taskListId().get());
                if(prior.isPresent()&&!prior.get().path("connectionId").equals(list.path("connectionId")))throw new ApiFailure(422,"TASK_PROVIDER_ACCOUNT_TRANSFER_UNSUPPORTED");
            }
            if(task.syncListId().isPresent()){
                var prior=store.find("TASK_LIST",task.syncListId().get()).orElseThrow(ApiFailure::notFound);
                if(!prior.path("connectionId").equals(list.path("connectionId")))throw new ApiFailure(422,"TASK_PROVIDER_ACCOUNT_TRANSFER_UNSUPPORTED");
            }
        }
    }
}
