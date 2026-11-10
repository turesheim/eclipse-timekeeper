package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Explicit replacement of observed identities; neither titles nor absent records establish a move. */
final class TaskProviderIdentities {
    private final ServerStore store;private final CommandReceipts receipts;private final ContractInputs inputs;
    TaskProviderIdentities(ServerStore store,CommandReceipts receipts,ContractInputs inputs){this.store=store;this.receipts=receipts;this.inputs=inputs;}
    private TaskProviderScans scans(){return new TaskProviderScans(store,receipts,inputs);}
    static boolean holdsImport(ServerStore store,JsonNode connection,JsonNode list,ExternalTaskReferenceKey key){
        if(!key.providerId().equals("google")||!key.repositoryId().equals(TaskCloudPull.repository(connection.path("remoteAccountId").asText(),list.path("remoteListId").asText())))return false;
        var auth=store.find(TaskProviderConnections.AUTH,uuid(connection.get("id")));return auth.isPresent()&&auth.get().path("reviewAdditions").asBoolean(true)&&TaskProviderScans.approved(auth.get(),list.path("remoteListId").asText());
    }
    static JsonNode descriptors(ServerStore store,JsonNode auth){
        var result=array();for(var scan:store.list(TaskProviderScans.SCAN))if(scan.path("connectionId").equals(auth.path("id"))&&scan.path("generation").equals(auth.path("generation"))&&scan.path("providerId").asText().equals("google")&&scan.path("state").asText().equals("READY")&&(!TaskProviderScans.approved(auth,scan.path("remoteListId").asText())||scan.path("identityCount").asInt()>0))result.add(object().put("listId",scan.path("id").asText()).put("remoteListId",scan.path("remoteListId").asText()).put("version",scan.path("version").asLong()));return result;
    }
    private ExternalTaskReferenceKey key(JsonNode scan,String id){return new ExternalTaskReferenceKey("google",TaskCloudPull.repository(scan.path("accountId").asText(),scan.path("remoteListId").asText()),id);}
    private boolean tombstoned(ExternalTaskReferenceKey key){for(var row:store.list("TASK_TOMBSTONE"))for(var ref:row.path("externalReferences"))if(alias(ref).equals(key))return true;return false;}
    private boolean active(JsonNode task,ExternalTaskReferenceKey key){for(var ref:task.path("externalReferences"))if(alias(ref).equals(key))return true;return false;}
    private ObjectNode review(JsonNode scan,TaskCloudProvider.RemoteTask remote){
        var key=key(scan,remote.id());var found=store.byReference(key);if(remote.deleted()||found.isPresent()&&active(found.get(),key))return null;
        boolean held=TaskProviderWrites.holdsImport(store,key),deleted=tombstoned(key);var row=object().put("externalId",remote.id()).put("version",scan.path("version").asLong()).put("scanId",scan.path("scanId").asText()).put("generation",hash(scan.path("generation").asText())).put("observedAt",scan.path("startedAt").asText());
        row.set("fields",GoogleTaskContext.fields(remote,key.repositoryId()));if(found.isPresent())row.put("taskId",found.get().path("id").asText());else row.putNull("taskId");
        boolean parent=remote.parent()==null||store.byReference(key(scan,remote.parent())).isPresent();
        row.put("canImport",found.isEmpty()&&!held&&!deleted&&parent&&remote.position()!=null&&!remote.position().isBlank()).put("canReplace",!held&&!deleted);
        row.put("blockedReason",held?"CREATE_IDENTITY_REQUIRED":deleted?"LOCAL_DELETED":!parent?"PARENT_UNRESOLVED":"");return row;
    }
    ObjectNode list(UUID connection,UUID list,String query){
        var scan=scans().ready(connection,list,false);if(!scan.path("providerId").asText().equals("google"))throw ApiFailure.conflict();String filter=Objects.toString(query,"").strip().toLowerCase(Locale.ROOT);if(filter.length()>1024)throw ApiFailure.badRequest();
        var result=object().put("observedAt",scan.path("startedAt").asText()).put("count",0);var items=result.putArray("items");
        for(var remote:scans().tasks(scan)){var row=review(scan,remote);if(row==null||!filter.isEmpty()&&!(remote.id()+" "+remote.fields().path("summary").asText()).toLowerCase(Locale.ROOT).contains(filter))continue;result.put("count",result.path("count").asInt()+1);if(items.size()<100){row.put("reviewEtag",etag(row));items.add(row);}}
        return result;
    }
    ApiReplies decide(UUID connection,UUID list,String match,JsonNode input){
        new TaskProviderConnections(store).required(connection);String path="/task-providers/"+connection+"/identities/"+list;String operation=UUID.nameUUIDFromBytes(("dagsverk-identity-decision:"+store.owner()+":"+path+":"+match+":"+canonical(input)).getBytes(StandardCharsets.UTF_8)).toString();
        return receipts.execute(operation,"POST",path,match,input,()->{
            var scan=scans().ready(connection,list,false);if(!scan.path("providerId").asText().equals("google"))throw ApiFailure.conflict();var remote=scans().tasks(scan).stream().filter(item->item.id().equals(input.path("externalId").asText())).findFirst().orElseThrow(ApiFailure::notFound);var row=review(scan,remote);if(row==null)throw ApiFailure.conflict();ApiReplies.match(row,match);
            UUID task;
            if(input.path("decision").asText().equals("IMPORT")){
                if(!row.path("canImport").asBoolean())throw ApiFailure.conflict();var pull=new TaskCloudPull(store,receipts,inputs);var context=new TaskCloudPull.Scan("google",scan.path("accountId").asText(),scan.path("remoteListId").asText(),scans().tasks(scan),Instant.parse(scan.path("startedAt").asText()),scan.path("full").asBoolean());var item=pull.observation(context,remote);item.put("operationId",UUID.nameUUIDFromBytes((operation+":import").getBytes(StandardCharsets.UTF_8)).toString());
                var proposal=object().put("connectionId",connection.toString()).put("listId",list.toString()).put("sourceState","HEALTHY");proposal.set("item",item);var auth=new TaskProviderConnections(store).required(connection);var project=auth.path("projects").get(scan.path("remoteListId").asText());if(project!=null&&!project.isNull()&&!item.path("fields").hasNonNull("parentTaskId"))proposal.set("projectId",project);inputs.validate("TaskSyncImportInput",proposal);
                var created=new TaskSync(store,receipts).importTask(proposal);task=uuid(created.body().get("id"));pull.remember(created.body(),"google",key(scan,remote.id()).repositoryId(),remote);
            }else{
                if(!row.path("canReplace").asBoolean())throw ApiFailure.conflict();task=uuid(input.get("taskId"));var current=store.find("TASK",task).orElseThrow(ApiFailure::notFound);ApiReplies.match(current,input.path("taskEtag").asText());var previous=alias(input.get("previous"));JsonNode old=null;for(var ref:current.path("externalReferences"))if(alias(ref).equals(previous)){old=ref;break;}if(old==null||!old.hasNonNull("taskListId")||!previous.providerId().equals("google"))throw ApiFailure.conflict();
                var oldList=store.find("TASK_LIST",uuid(old.get("taskListId"))).orElseThrow(ApiFailure::notFound);
                if(!oldList.path("connectionId").asText().equals(connection.toString())||!previous.repositoryId().equals(TaskCloudPull.repository(scan.path("accountId").asText(),oldList.path("remoteListId").asText())))throw ApiFailure.conflict();
                var target=key(scan,remote.id());var bound=store.byReference(target);if(bound.isPresent()&&!bound.get().path("id").asText().equals(task.toString()))throw ApiFailure.conflict();
                var payload=old.path("providerData").path("payload").isObject()?((ObjectNode)old.path("providerData").path("payload")).deepCopy():object();if(old.hasNonNull("providerData")&&(!old.path("providerData").path("namespace").asText().equals("google.tasks")||old.path("providerData").path("version").asInt()!=1))throw ApiFailure.conflict();payload.set("observation",remote.original().deepCopy());payload.remove("deletionReview");var policy=payload.path("policy").isObject()?((ObjectNode)payload.get("policy")).deepCopy():object();policy.put("localHierarchy",true);payload.set("policy",policy);
                var fields=GoogleTaskContext.fields(remote,target.repositoryId());var value=(Task)decode("TASK",current);var baseline=value.externalReferences().stream().filter(ref->ref.key().equals(previous)).findFirst().orElseThrow().baseline();value=sourceLinks(value,old,fields);
                var reference=new ExternalTaskReference("google",target.repositoryId(),target.externalId(),GoogleTaskContext.taskUrl(remote.original()),source(remote.source()),Optional.of(list),Optional.empty(),Optional.ofNullable(fields.get("assignment")).filter(field->!field.isNull()).map(JsonValues::assignment),Optional.of(new ExternalTaskProviderData("google.tasks",1,payload.toString())),baseline);
                var replaced=store.service().replaceExternalReference(new ReplaceExternalReference(value.id(),value.version(),previous,reference));
                var observation=object().put("providerId","google").put("repositoryId",target.repositoryId()).put("externalId",target.externalId()).put("deleted",false).put("operationId",UUID.nameUUIDFromBytes((operation+":reconcile").getBytes(StandardCharsets.UTF_8)).toString());observation.set("fields",fields);
                var proposal=object().put("connectionId",connection.toString()).put("listId",list.toString()).put("sourceState","HEALTHY");proposal.set("item",observation);inputs.validate("TaskSyncApplyInput",proposal);var applied=new TaskSync(store,receipts).resolve(proposal,etag(store.taskSnapshot(replaced)),false);new TaskCloudPull(store,receipts,inputs).remember(applied.body(),"google",target.repositoryId(),remote);
            }
            scans().applyReviewed(scan);var auth=new TaskProviderConnections(store).required(connection);auth.put("requested",true).put("nextRefresh",store.now().toString());new TaskProviderConnections(store).save(TaskProviderConnections.AUTH,auth);return ApiReplies.resource(200,store.find("TASK",task).orElseThrow(ApiFailure::notFound),null);
        });
    }
    private Task sourceLinks(Task task,JsonNode old,JsonNode observed){
        var incoming=observed.path("metadata").path("links");if(!incoming.isArray())return task;var current=snapshot(store.owner(),task);var metadata=((ObjectNode)current.path("metadata")).deepCopy();var links=array();var previous=old.path("baseline").path("fields").path("metadata").path("links");if(!previous.isArray())previous=old.path("baseline").path("fields").path("metadata.links");var known=new HashMap<String,JsonNode>();previous.forEach(link->known.put(link.path("id").asText(),link));var present=new HashSet<String>();
        for(var link:metadata.path("links")){var baseline=known.get(link.path("id").asText());if(baseline==null||!canonical(link).equals(canonical(baseline))){links.add(link);present.add(link.path("id").asText());}}
        for(var link:incoming)if(present.add(link.path("id").asText()))links.add(link);metadata.set("links",links);if(metadata.equals(current.path("metadata")))return task;return store.service().updateTaskMetadata(new UpdateTaskMetadata(task.id(),task.version(),metadata(metadata)));
    }
}
