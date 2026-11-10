package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteTask;

/** Incoming decisions use actual staged observations, never the truncated connection-status summaries. */
final class TaskProviderReviews {
    private final ServerStore store;
    private final CommandReceipts receipts;
    private final ContractInputs inputs;
    private final TaskCloudPull pull;
    TaskProviderReviews(ServerStore store,CommandReceipts receipts,ContractInputs inputs){this.store=store;this.receipts=receipts;this.inputs=inputs;this.pull=new TaskCloudPull(store,receipts,inputs);}
    private TaskProviderScans scans(){return new TaskProviderScans(store,receipts,inputs);}
    private TaskCloudPull pull(){return pull;}
    private TaskCloudPull.Scan scan(JsonNode value){return new TaskCloudPull.Scan(value.path("providerId").asText(),value.path("accountId").asText(),value.path("remoteListId").asText(),scans().tasks(value),Instant.parse(value.path("startedAt").asText()),value.path("full").asBoolean());}
    ObjectNode list(UUID connection,UUID list,String query){
        var value=scans().ready(connection,list);var scan=scan(value);String filter=Objects.toString(query,"").strip().toLowerCase(Locale.ROOT);if(filter.length()>1024)throw ApiFailure.badRequest();
        var result=object().put("observedAt",scan.observedAt().toString()).put("count",0);var items=result.putArray("items");
        for(var remote:scan.tasks()){
            ObjectNode row;try{row=review(connection,list,value,scan,remote);}catch(ApiFailure failure){if(failure.status!=409)throw failure;row=object().put("externalId",remote.id()).put("status","DEFERRED").put("canResolve",false);}
            if(Set.of("CREATE","UPDATE","KEEP_LOCAL","UNCHANGED","IGNORE_DELETED").contains(row.path("status").asText()))continue;
            if(!filter.isEmpty()&&!(remote.id()+" "+remote.fields().path("summary").asText()+" "+row.path("localFields").path("summary").asText()).toLowerCase(Locale.ROOT).contains(filter))continue;
            result.put("count",result.path("count").asInt()+1);if(items.size()<100){row.put("reviewEtag",etag(row));items.add(row);}
        }
        return result;
    }
    private ObjectNode review(UUID connection,UUID list,JsonNode value,TaskCloudPull.Scan scan,TaskCloudProvider.RemoteTask remote){
        var item=pull().observation(scan,remote);item.put("operationId",UUID.nameUUIDFromBytes(("dagsverk-incoming-preview:"+value.path("scanId").asText()+":"+remote.id()).getBytes(StandardCharsets.UTF_8)).toString());
        var input=object().put("connectionId",connection.toString()).put("listId",list.toString()).put("sourceState","HEALTHY").put("scanComplete",true);input.putArray("items").add(item);inputs.validate("TaskSyncPreviewInput",input);
        var row=((ObjectNode)new TaskSync(store,receipts).preview(input,true).body().path("items").get(0)).deepCopy();
        row.put("version",value.path("version").asLong()).put("generation",hash(value.path("generation").asText())).put("scanId",value.path("scanId").asText()).put("observedAt",scan.observedAt().toString());
        row.set("remoteFields",item.path("fields").deepCopy());row.put("deleted",remote.deleted());row.set("localFields",row.hasNonNull("taskId")?store.find("TASK",uuid(row.get("taskId"))).orElseThrow(ApiFailure::notFound):object());
        boolean allowed=row.hasNonNull("taskId")&&!Set.of("OTHER_LIST","IDENTITY_ALIAS").contains(row.path("reason").asText());
        for(var decision:row.path("decisions"))if(Set.of("INCOMPLETE_GROUP","OTHER_LIST").contains(decision.path("outcome").asText()))allowed=false;
        row.put("canResolve",allowed&&Set.of("CONFLICT","DELETE_REVIEW","DELETE_CONFLICT").contains(row.path("status").asText()));
        row.put("canDelete",row.path("canResolve").asBoolean()&&remote.deleted()&&row.path("localFields").path("externalReferences").size()==1);return row;
    }
    ApiReplies resolve(UUID connection,UUID list,UUID task,String match,JsonNode input){
        // Ownership is checked before receipt replay; mutable review/task preconditions are checked inside it.
        new TaskProviderConnections(store).required(connection);
        String path="/task-providers/"+connection+"/reviews/"+list+"/"+task;
        String operation=UUID.nameUUIDFromBytes(("dagsverk-incoming-resolution:"+store.owner()+":"+path+":"+match+":"+canonical(input)).getBytes(StandardCharsets.UTF_8)).toString();
        return receipts.execute(operation,"POST",path,match,input,()->{
            var value=scans().ready(connection,list);var scan=scan(value);TaskCloudProvider.RemoteTask remote=null;ObjectNode row=null;
            for(var candidate:scan.tasks()){
                var found=store.byReference(new ExternalTaskReferenceKey(scan.provider(),TaskCloudPull.repository(scan.account(),scan.list()),candidate.id()));
                if(found.isPresent()&&found.get().path("id").asText().equals(task.toString())){if(remote!=null)throw ApiFailure.conflict();remote=candidate;row=review(connection,list,value,scan,candidate);}
            }
            if(row==null)throw ApiFailure.notFound();ApiReplies.match(row,match);if(!row.path("canResolve").asBoolean())throw ApiFailure.conflict();
            var current=store.find("TASK",task).orElseThrow(ApiFailure::notFound);ApiReplies.match(current,input.path("taskEtag").asText());String choice=input.path("decision").asText();
            if(remote.deleted()){
                if(choice.equals("DELETE")){
                    if(!row.path("canDelete").asBoolean())throw ApiFailure.conflict();store.service().deleteTask(new DeleteTask(new TaskId(task),current.path("version").asLong()));
                }else if(choice.equals("LOCAL")){
                    pull().remember(current,scan.provider(),TaskCloudPull.repository(scan.account(),scan.list()),remote);
                    var saved=((ObjectNode)store.find("TASK",task).orElseThrow()).deepCopy();
                    for(var ref:saved.path("externalReferences"))if(ref.path("providerId").asText().equals(scan.provider())&&ref.path("repositoryId").asText().equals(TaskCloudPull.repository(scan.account(),scan.list()))&&ref.path("externalId").asText().equals(remote.id()))((ObjectNode)ref.path("providerData").path("payload")).put("deletionReview",true);
                    saved.put("version",saved.path("version").asLong()+1);store.save("TASK",(Task)decode("TASK",saved));store.change("TASK","UPDATED",task.toString());
                }else throw ApiFailure.conflict();
            }else{
                if(!Set.of("LOCAL","REMOTE").contains(choice))throw ApiFailure.conflict();var item=pull().observation(scan,remote);item.put("operationId",UUID.nameUUIDFromBytes((operation+":apply").getBytes(StandardCharsets.UTF_8)).toString());
                var proposal=object().put("connectionId",connection.toString()).put("listId",list.toString()).put("sourceState","HEALTHY");proposal.set("item",item);inputs.validate("TaskSyncApplyInput",proposal);
                var applied=new TaskSync(store,receipts).resolve(proposal,etag(current),choice.equals("REMOTE"));pull().remember(applied.body(),scan.provider(),TaskCloudPull.repository(scan.account(),scan.list()),remote);
            }
            scans().applyReviewed(value);
            var auth=new TaskProviderConnections(store).required(connection);auth.put("requested",true).put("nextRefresh",store.now().toString());new TaskProviderConnections(store).save(TaskProviderConnections.AUTH,auth);
            var result=object().put("taskId",task.toString()).put("deleted",remote.deleted()&&choice.equals("DELETE"));return new ApiReplies(200,result,null,null);
        });
    }
}
