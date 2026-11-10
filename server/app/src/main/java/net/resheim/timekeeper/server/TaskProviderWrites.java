package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Owner-scoped Google write intents. Persist SENDING before I/O and ACK before reconciling its result. */
final class TaskProviderWrites {
    static final String KIND="TASK_WRITE";
    enum Action { UPDATE, DELETE }
    private static final Set<String> DONE=Set.of("DONE","RECONCILED","CANCELLED","FAILED","STALE");
    private static final Set<String> FIELDS=Set.of("summary","body","status","completedAt","scheduledDate");
    private final ServerStore store;
    private final CommandReceipts receipts;
    private final ContractInputs inputs;
    TaskProviderWrites(ServerStore store,CommandReceipts receipts,ContractInputs inputs){this.store=store;this.receipts=receipts;this.inputs=inputs;}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    ObjectNode prepare(UUID connection,UUID listId,UUID taskId,Action action){
        var auth=access(connection,listId);var list=required("TASK_LIST",listId);var task=required("TASK",taskId);var reference=reference(connection,list,task);
        String revision=reference.path("source").path("remoteEtag").asText();
        try{GoogleTasksWriter.revision(revision);}catch(TaskProviderHttp.Failure missing){throw new ApiFailure(409,missing.getMessage());}
        var fields=object();var warnings=array();boolean allowed=true;
        if(action==Action.UPDATE){
            var input=identity(connection,list,task,reference);inputs.validate("TaskSyncExportInput",input);
            var preview=new TaskSync(store,receipts).exportPreview(input).body();fields=(ObjectNode)preview.path("writeFields").deepCopy();warnings=(com.fasterxml.jackson.databind.node.ArrayNode)preview.path("warnings").deepCopy();
            if(reference.path("providerData").path("payload").path("observation").hasNonNull("assignmentInfo")&&fields.has("body")){
                fields.remove("body");warnings.add(object().put("field","body").put("reason","ASSIGNED_TASK_NOTES"));
            }
            while(!fields.isEmpty())try{GoogleTasksWriter.body(fields);break;}catch(GoogleTasksWriter.Unsupported unsupported){
                warnings.add(object().put("field",unsupported.field).put("reason",unsupported.reason));
                if(Set.of("status","completedAt").contains(unsupported.field)){fields.remove("status");fields.remove("completedAt");}
                else if(fields.remove(unsupported.field)==null)throw new ApiFailure(422,"TASK_PROVIDER_INVALID_REQUEST");
            }
            allowed=!fields.isEmpty();
        }else if(reference.path("providerData").path("payload").path("observation").hasNonNull("assignmentInfo")){
            allowed=false;warnings.add(object().put("field","assignment").put("reason","DELETE_AT_ASSIGNMENT_SOURCE"));
        }
        int active=0;
        for(var row:store.list(KIND))if(row.path("connectionId").asText().equals(connection.toString())){
            if(row.path("state").asText().equals("PREVIEW")&&!Instant.parse(row.path("expiresAt").asText()).isAfter(store.now()))remove(row);
            else if(!DONE.contains(row.path("state").asText()))active++;
        }
        if(active>=100)throw new ApiFailure(429,"TASK_PROVIDER_WRITE_LIMIT");
        var value=object().put("connectionId",connection.toString()).put("listId",listId.toString()).put("remoteListId",list.path("remoteListId").asText())
                .put("taskId",taskId.toString()).put("title",task.path("summary").asText()).put("externalId",reference.path("externalId").asText()).put("repositoryId",reference.path("repositoryId").asText())
                .put("action",action.name()).put("state","PREVIEW").put("taskEtag",etag(task)).put("providerEtag",revision).put("generation",auth.path("generation").asText())
                .put("createdAt",store.now().toString()).put("expiresAt",store.now().plusSeconds(900).toString()).put("allowed",allowed).putNull("lastError");
        value.set("writeFields",fields);value.set("warnings",warnings);
        return visible(connections().insert(KIND,UUID.randomUUID(),value));
    }
    ObjectNode confirm(UUID connection,UUID id,String match){
        var value=owned(connection,id);ApiReplies.match(visible(value),match);var auth=access(connection,uuid(value.get("listId")));
        if(!value.path("state").asText().equals("PREVIEW")||!value.path("allowed").asBoolean())throw ApiFailure.conflict();
        if(!Instant.parse(value.path("expiresAt").asText()).isAfter(store.now()))throw new ApiFailure(409,"TASK_PROVIDER_PREVIEW_EXPIRED");
        validate(value,auth);value.put("state","QUEUED").put("confirmedAt",store.now().toString());var saved=connections().save(KIND,value);
        auth.put("requested",true).put("nextRefresh",store.now().toString());connections().save(TaskProviderConnections.AUTH,auth);return visible(saved);
    }
    ObjectNode list(UUID connection){
        connections().required(connection);var result=object();var items=result.putArray("items");var rows=new ArrayList<JsonNode>();
        for(var row:store.list(KIND))if(row.path("connectionId").asText().equals(connection.toString()))rows.add(row);
        rows.sort(Comparator.comparing((JsonNode row)->row.path("createdAt").asText()).reversed());int history=0;
        for(var row:rows){if(DONE.contains(row.path("state").asText())&&history++>=20)continue;var summary=object();for(String field:List.of("id","title","action","state","createdAt","lastError"))summary.set(field,row.path(field).deepCopy());items.add(summary);}
        return result;
    }
    ObjectNode get(UUID connection,UUID id){return visible(owned(connection,id));}
    ObjectNode check(UUID connection,UUID id,String match){
        var row=owned(connection,id);ApiReplies.match(visible(row),match);var auth=readAccess(connection,uuid(row.get("listId")));
        if(!Set.of("UNKNOWN","ACK_REVIEW").contains(row.path("state").asText()))throw ApiFailure.conflict();
        row.put("checkRequested",true).put("checkRequestedAt",store.now().toString()).put("generation",auth.path("generation").asText());var saved=connections().save(KIND,row);
        auth.put("requested",true).put("forceRequested",true).put("nextRefresh",store.now().toString());connections().save(TaskProviderConnections.AUTH,auth);return visible(saved);
    }
    ObjectNode resolve(UUID connection,UUID id,String match,String taskMatch,boolean takeRemote){
        var row=owned(connection,id);ApiReplies.match(visible(row),match);readAccess(connection,uuid(row.get("listId")));
        if(!Set.of("UNKNOWN","ACK_REVIEW").contains(row.path("state").asText())||!row.path("action").asText().equals("UPDATE"))throw ApiFailure.conflict();
        boolean uncertain=row.path("state").asText().equals("UNKNOWN");String result=row.hasNonNull("checkedResult")?"checkedResult":"result",observed=result.equals("checkedResult")?"checkedAt":"acknowledgedAt";
        if(!row.hasNonNull(result)||!row.hasNonNull(observed)||!Instant.parse(row.path(observed).asText()).plusSeconds(900).isAfter(store.now()))throw new ApiFailure(409,"TASK_PROVIDER_PREVIEW_EXPIRED");
        var current=required("TASK",uuid(row.get("taskId")));ApiReplies.match(current,taskMatch);var list=required("TASK_LIST",uuid(row.get("listId")));var reference=reference(connection,list,current);
        if(!reference.path("externalId").equals(row.path("externalId")))throw ApiFailure.conflict();
        var remote=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(row.path(result));if(remote.deleted()||!remote.id().equals(row.path("externalId").asText())||!remote.source().hasNonNull("remoteEtag"))throw ApiFailure.conflict();
        var task=(Task)decode("TASK",current);var fields=remote.fields();
        var decisions=resolutionPreview(row,remote).path("decisions");
        var take=new HashSet<String>();for(var decision:decisions)if(decision.path("outcome").asText().equals("REMOTE_ONLY")||takeRemote&&Set.of("CONFLICT","NO_BASELINE").contains(decision.path("outcome").asText()))take.add(decision.path("field").asText());
        if(!take.isEmpty()){
            if(take.contains("summary")&&!task.summary().equals(fields.path("summary").asText()))task=store.service().updateTask(new UpdateTask(task.id(),task.version(),task.projectId(),task.parentTaskId(),fields.path("summary").asText(),task.url()));
            if(take.contains("body")&&!task.body().equals(JsonValues.body(fields)))task=store.service().updateTaskBody(new UpdateTaskBody(task.id(),task.version(),JsonValues.body(fields)));
            boolean completion=take.contains("status")||take.contains("completedAt");
            var planning=new TaskPlanning(completion?TaskStatus.valueOf(fields.path("status").asText()):task.planning().status(),take.contains("scheduledDate")?optionalText(fields.get("scheduledDate")).map(LocalDate::parse):task.planning().scheduledDate(),completion?optionalText(fields.get("completedAt")).map(Instant::parse):task.planning().completedAt());
            if(!task.planning().equals(planning))task=store.service().updateTaskPlanning(new UpdateTaskPlanning(task.id(),task.version(),planning));
        }
        var accepted=reference.path("baseline").path("fields").isObject()?((ObjectNode)reference.path("baseline").path("fields")).deepCopy():object();
        for(var decision:decisions){String field=decision.path("field").asText();if(FIELDS.contains(field)&&!decision.path("outcome").asText().equals("UNSUPPORTED")&&fields.has(field))accepted.set(field,fields.get(field).deepCopy());}
        var source=((ObjectNode)reference.path("source")).deepCopy();source.setAll(remote.source());
        var key=new ExternalTaskReferenceKey("google",row.path("repositoryId").asText(),row.path("externalId").asText());
        task=store.service().updateExternalTaskSyncState(new UpdateExternalTaskSyncState(task.id(),task.version(),key,source(source),Optional.of(new ExternalTaskBaseline(1,store.now(),accepted.toString()))));
        new TaskCloudPull(store,receipts,inputs).remember(snapshot(store.owner(),task),"google",row.path("repositoryId").asText(),remote);
        row.put("state",uncertain?"RECONCILED":"DONE").put("resolution",takeRemote?"REMOTE":"LOCAL").putNull("lastError");return visible(connections().save(KIND,row));
    }
    void cancel(UUID connection,UUID id,String match){
        connections().required(connection);var value=owned(connection,id);ApiReplies.match(visible(value),match);
        if(!Set.of("PREVIEW","QUEUED","CHECKING","CONFLICT").contains(value.path("state").asText()))throw ApiFailure.conflict();
        value.put("state","CANCELLED");connections().save(KIND,value);
    }
    private ObjectNode visible(JsonNode value){
        var result=object();for(String field:List.of("id","version","connectionId","listId","taskId","title","externalId","action","state","taskEtag","providerEtag","createdAt","expiresAt","lastError","writeFields","warnings"))result.set(field,value.path(field).deepCopy());
        var task=store.find("TASK",uuid(value.get("taskId")));result.putNull("currentTaskEtag").putNull("localFields").putNull("remoteFields").putNull("remoteObservedAt").putNull("resolution");
        if(task.isPresent()){result.put("currentTaskEtag",etag(task.get()));var local=result.putObject("localFields");for(String field:FIELDS)local.set(field,task.get().path(field).deepCopy());}
        String snapshot=value.hasNonNull("checkedResult")?"checkedResult":"result",time=snapshot.equals("result")?"acknowledgedAt":"checkedAt";
        TaskCloudProvider.RemoteTask observed=null;
        if(value.hasNonNull(snapshot)&&!value.path(snapshot).path("deleted").asBoolean()){
            observed=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(value.path(snapshot));var fields=observed.fields();var remote=result.putObject("remoteFields");for(String field:FIELDS)if(fields.has(field))remote.set(field,fields.get(field).deepCopy());
            result.set("remoteObservedAt",value.path(time));
        }
        if(value.hasNonNull("resolution"))result.set("resolution",value.get("resolution"));
        result.put("canConfirm",value.path("allowed").asBoolean()&&value.path("state").asText().equals("PREVIEW")&&task.isPresent()&&etag(task.get()).equals(value.path("taskEtag").asText())&&Instant.parse(value.path("expiresAt").asText()).isAfter(store.now()));
        boolean canResolve=value.path("action").asText().equals("UPDATE")&&Set.of("UNKNOWN","ACK_REVIEW").contains(value.path("state").asText())&&observed!=null&&task.isPresent()&&value.hasNonNull(time)&&Instant.parse(value.path(time).asText()).plusSeconds(900).isAfter(store.now());
        var conflicts=result.putArray("conflictFields");if(canResolve)try{
            readAccess(uuid(value.get("connectionId")),uuid(value.get("listId")));var preview=resolutionPreview(value,observed);
            canResolve=preview.path("taskId").asText().equals(value.path("taskId").asText())&&!Set.of("RECONCILE_ID","DEFERRED").contains(preview.path("status").asText());
            for(var decision:preview.path("decisions"))if(Set.of("CONFLICT","NO_BASELINE").contains(decision.path("outcome").asText()))conflicts.add(decision.path("field").asText());
        }catch(ApiFailure unavailable){canResolve=false;}
        result.put("canResolve",canResolve);return result;
    }
    private JsonNode resolutionPreview(JsonNode row,TaskCloudProvider.RemoteTask remote){
        var proposal=object().put("connectionId",row.path("connectionId").asText()).put("listId",row.path("listId").asText()).put("sourceState","HEALTHY").put("scanComplete",false);
        var item=object().put("operationId",row.path("id").asText()).put("providerId","google").put("repositoryId",row.path("repositoryId").asText()).put("externalId",remote.id()).put("deleted",false);item.set("fields",remote.fields().deepCopy());proposal.putArray("items").add(item);
        inputs.validate("TaskSyncPreviewInput",proposal);return new TaskSync(store,receipts).preview(proposal).body().path("items").get(0);
    }
    /** Called inside the claimed owner transaction; CHECKING can retry because it performs GET only. */
    ObjectNode next(TaskProviderConnections.Claim claim){
        if(connections().active(claim)==null)return null;readAccess(claim.id(),claim.list());
        var rows=new ArrayList<JsonNode>();for(var row:store.list(KIND))if(row.path("connectionId").asText().equals(claim.id().toString())&&row.path("listId").asText().equals(claim.list().toString()))rows.add(row);
        rows.sort(Comparator.comparing(row->row.path("createdAt").asText()));
        for(var before:rows){var row=((ObjectNode)before).deepCopy();String state=row.path("state").asText();
            if(state.equals("SENDING")&&!row.path("lease").asText().equals(claim.lease())){row.put("state","UNKNOWN").put("lastError","TASK_PROVIDER_OUTCOME_UNKNOWN");connections().save(KIND,row);continue;}
            if(state.equals("ACK"))return row;
            if(Set.of("UNKNOWN","ACK_REVIEW").contains(state)&&row.path("checkRequested").asBoolean()){
                if(state.equals("UNKNOWN")&&row.path("action").asText().equals("DELETE")&&!freshScan(claim,row))continue;
                row.put("checkRequested",false).put("checkLease",claim.lease());if(state.equals("ACK_REVIEW")&&row.path("action").asText().equals("DELETE"))row.put("state","ACK");return connections().save(KIND,row);
            }
            if(!claim.writable())continue;
            if(!Set.of("QUEUED","CHECKING").contains(state))continue;
            try{validate(row,connections().required(claim.id()));}
            catch(ApiFailure stale){row.put("state","STALE").put("lastError","TASK_PROVIDER_WRITE_CHANGED");connections().save(KIND,row);continue;}
            row.put("state","CHECKING").put("lease",claim.lease());return connections().save(KIND,row);
        }
        return null;
    }
    ObjectNode sending(TaskProviderConnections.Claim claim,UUID id,TaskCloudProvider.RemoteTask remote){
        if(connections().active(claim)==null)return null;var row=owned(claim.id(),id);var auth=access(claim.id(),claim.list());
        if(!row.path("state").asText().equals("CHECKING")||!row.path("lease").asText().equals(claim.lease()))return null;
        try{validate(row,auth);}catch(ApiFailure stale){row.put("state","STALE").put("lastError","TASK_PROVIDER_WRITE_CHANGED");connections().save(KIND,row);return null;}
        if(remote.deleted()||!remote.id().equals(row.path("externalId").asText())||!row.path("providerEtag").asText().equals(remote.source().path("remoteEtag").asText())){
            row.put("state","CONFLICT").put("lastError","TASK_PROVIDER_CHANGED");connections().save(KIND,row);return null;
        }
        if(remote.original().hasNonNull("assignmentInfo")&&(row.path("action").asText().equals("DELETE")||row.path("writeFields").has("body"))){
            row.put("state","STALE").put("lastError","TASK_PROVIDER_WRITE_CHANGED");connections().save(KIND,row);return null;
        }
        row.put("state","SENDING").put("attempt",UUID.randomUUID().toString()).put("startedAt",store.now().toString());return connections().save(KIND,row);
    }
    /** Durable provider acknowledgement, independent of local reconciliation or a changed connection. */
    boolean acknowledge(UUID id,String attempt,TaskCloudProvider.RemoteTask remote){
        var found=store.find(KIND,id);if(found.isEmpty())return false;var row=((ObjectNode)found.get()).deepCopy();
        if(!Set.of("SENDING","UNKNOWN").contains(row.path("state").asText())||!row.path("attempt").asText().equals(attempt))return false;
        if(remote!=null)row.set("result",remote.original().deepCopy());row.put("state","ACK").put("acknowledgedAt",store.now().toString()).putNull("lastError");connections().save(KIND,row);return true;
    }
    void reconcile(TaskProviderConnections.Claim claim,UUID id){
        var row=owned(claim.id(),id);if(!row.path("state").asText().equals("ACK"))return;
        if(connections().active(claim)==null||!row.path("generation").asText().equals(claim.generation())){
            row.put("state","ACK_REVIEW").put("lastError","TASK_PROVIDER_WRITE_CHANGED");connections().save(KIND,row);return;
        }
        readAccess(claim.id(),claim.list());
        if(!row.path("action").asText().equals("DELETE")){
            var task=store.find("TASK",uuid(row.get("taskId"))).orElse(null);
            if(task==null){row.put("state","DONE").putNull("lastError");connections().save(KIND,row);return;}
            var remote=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(row.path("result"));
            if(remote.deleted()||!remote.id().equals(row.path("externalId").asText()))throw ApiFailure.conflict();
            var item=object().put("providerId","google").put("repositoryId",row.path("repositoryId").asText()).put("externalId",remote.id()).put("deleted",false);item.set("fields",remote.fields().deepCopy());
            item.put("operationId",UUID.nameUUIDFromBytes(("dagsverk-google-ack:"+id+":"+etag(task)+":"+canonical(item)).getBytes(StandardCharsets.UTF_8)).toString());
            var input=object().put("connectionId",claim.id().toString()).put("listId",claim.list().toString()).put("sourceState","HEALTHY");input.set("item",item);
            var preview=input.deepCopy().put("scanComplete",false);preview.remove("item");preview.putArray("items").add(item);inputs.validate("TaskSyncPreviewInput",preview);
            var decision=new TaskSync(store,receipts).preview(preview).body().path("items").get(0);
            if(!Set.of("UPDATE","KEEP_LOCAL","UNCHANGED").contains(decision.path("status").asText())){
                row.put("state","ACK_REVIEW").put("lastError","TASK_PROVIDER_CONFLICTS");connections().save(KIND,row);return;
            }
            inputs.validate("TaskSyncApplyInput",input);var applied=new TaskSync(store,receipts).apply(input,etag(task));
            new TaskCloudPull(store,receipts,inputs).remember(applied.body(),"google",row.path("repositoryId").asText(),remote);
        }
        row.put("state","DONE").putNull("lastError");connections().save(KIND,row);
    }
    void failure(UUID id,String lease,String attempt,String error,boolean uncertain){
        var found=store.find(KIND,id);if(found.isEmpty())return;var row=((ObjectNode)found.get()).deepCopy();String state=row.path("state").asText();
        if(!Set.of("CHECKING","SENDING").contains(state)||!row.path("lease").asText().equals(lease)||state.equals("SENDING")&&!row.path("attempt").asText().equals(attempt))return;
        row.put("state",uncertain&&state.equals("SENDING")?"UNKNOWN":"FAILED").put("lastError",uncertain&&state.equals("SENDING")?"TASK_PROVIDER_OUTCOME_UNKNOWN":error);connections().save(KIND,row);
    }
    /** Every HTTP request runs outside the account transaction. UNKNOWN writes are never sent again. */
    void drain(TaskProviderConnections.Claim claim,String token,GoogleTasksWriter writer){
        for(int count=0;count<5;count++){
            var next=store.transaction(claim.owner(),()->next(claim));if(next==null)return;UUID id=uuid(next.get("id"));
            if(next.path("state").asText().equals("ACK")){store.transaction(claim.owner(),()->{reconcile(claim,id);return null;});continue;}
            ObjectNode sending=null;
            try{
                if(!store.transaction(claim.owner(),()->connections().active(claim)!=null))return;
                var deletion=next.path("state").asText().equals("UNKNOWN")&&next.path("action").asText().equals("DELETE")?store.transaction(claim.owner(),()->deletedSnapshot(claim,next)):null;
                var remote=deletion!=null?deletion:writer.get(token,claim.remoteList(),next.path("externalId").asText());
                if(Set.of("UNKNOWN","ACK_REVIEW").contains(next.path("state").asText())){
                    store.transaction(claim.owner(),()->{observed(claim,id,remote);return null;});
                    store.transaction(claim.owner(),()->{reconcile(claim,id);return null;});continue;
                }
                sending=store.transaction(claim.owner(),()->sending(claim,id,remote));if(sending==null)continue;
                TaskCloudProvider.RemoteTask result=null;
                if(sending.path("action").asText().equals("DELETE"))writer.delete(token,claim.remoteList(),sending.path("externalId").asText(),sending.path("providerEtag").asText());
                else result=writer.update(token,claim.remoteList(),sending.path("externalId").asText(),sending.path("providerEtag").asText(),sending.path("writeFields"));
                String attempt=sending.path("attempt").asText();var observed=result;
                store.transaction(claim.owner(),()->acknowledge(id,attempt,observed));
                store.transaction(claim.owner(),()->{reconcile(claim,id);return null;});
            }catch(TaskProviderHttp.Failure failed){
                String attempt=sending==null?"":sending.path("attempt").asText();store.transaction(claim.owner(),()->{failure(id,claim.lease(),attempt,failed.getMessage(),failed.uncertain);return null;});throw failed;
            }
        }
    }
    static boolean queued(ServerStore store,UUID connection){for(var value:store.list(KIND))if(value.path("connectionId").asText().equals(connection.toString())&&(Set.of("QUEUED","CHECKING").contains(value.path("state").asText())||value.path("checkRequested").asBoolean()))return true;return false;}
    static int pending(ServerStore store,UUID connection){int count=0;for(var value:store.list(KIND))if(value.path("connectionId").asText().equals(connection.toString())&&!DONE.contains(value.path("state").asText()))count++;return count;}
    private boolean freshScan(TaskProviderConnections.Claim claim,JsonNode row){
        var scan=store.find(TaskProviderScans.SCAN,claim.list()).orElse(null);
        return scan!=null&&scan.path("connectionId").asText().equals(claim.id().toString())&&scan.path("accountId").asText().equals(claim.account())&&scan.path("remoteListId").asText().equals(claim.remoteList())
                &&scan.path("generation").asText().equals(claim.generation())&&Set.of("READY","APPLIED").contains(scan.path("state").asText())&&row.hasNonNull("checkRequestedAt")&&!Instant.parse(scan.path("startedAt").asText()).isBefore(Instant.parse(row.path("checkRequestedAt").asText()));
    }
    private TaskCloudProvider.RemoteTask deletedSnapshot(TaskProviderConnections.Claim claim,JsonNode row){
        if(!freshScan(claim,row))return null;var scan=store.find(TaskProviderScans.SCAN,claim.list()).orElseThrow();
        for(var remote:new TaskProviderScans(store,receipts,inputs).tasks(scan))if(remote.deleted()&&remote.id().equals(row.path("externalId").asText()))return remote;return null;
    }
    private void observed(TaskProviderConnections.Claim claim,UUID id,TaskCloudProvider.RemoteTask remote){
        if(connections().active(claim)==null)return;var row=owned(claim.id(),id);
        if(!Set.of("UNKNOWN","ACK_REVIEW").contains(row.path("state").asText())||!row.path("checkLease").asText().equals(claim.lease())||!remote.id().equals(row.path("externalId").asText()))return;
        row.set("checkedResult",remote.original().deepCopy());row.put("checkedAt",store.now().toString());
        row.put("generation",claim.generation());
        boolean matches=row.path("action").asText().equals("DELETE")?remote.deleted():!remote.deleted();
        if(matches&&!row.path("action").asText().equals("DELETE"))for(var fields=row.path("writeFields").fields();fields.hasNext();){var field=fields.next();if(!field.getValue().equals(remote.fields().get(field.getKey())))matches=false;}
        if(matches){row.put("state","ACK").put("acknowledgedAt",store.now().toString()).putNull("lastError");if(!remote.deleted())row.set("result",remote.original().deepCopy());}
        connections().save(KIND,row);
    }
    static void retire(ServerStore store,UUID connection,String generation){
        var state=new TaskProviderConnections(store);for(var before:store.list(KIND))if(before.path("connectionId").asText().equals(connection.toString())&&!before.path("generation").asText().equals(generation)){
            var row=((ObjectNode)before).deepCopy();String old=row.path("state").asText();String next=switch(old){case "PREVIEW","QUEUED","CHECKING"->"STALE";case "SENDING"->"UNKNOWN";case "ACK"->"ACK_REVIEW";default->null;};
            if(next!=null){row.put("state",next).put("lastError",next.equals("UNKNOWN")?"TASK_PROVIDER_OUTCOME_UNKNOWN":"TASK_PROVIDER_WRITE_CHANGED");state.save(KIND,row);}
        }
    }
    private void validate(JsonNode row,JsonNode auth){
        if(!row.path("generation").equals(auth.path("generation")))throw ApiFailure.conflict();
        var task=required("TASK",uuid(row.get("taskId")));if(!etag(task).equals(row.path("taskEtag").asText()))throw new ApiFailure(412,"STALE_VERSION");
        var reference=reference(uuid(row.get("connectionId")),required("TASK_LIST",uuid(row.get("listId"))),task);
        if(!reference.path("externalId").equals(row.path("externalId"))||!reference.path("source").path("remoteEtag").asText().equals(row.path("providerEtag").asText()))throw ApiFailure.conflict();
    }
    private ObjectNode access(UUID connection,UUID listId){
        var auth=readAccess(connection,listId);var metadata=required("TASK_CONNECTION",connection);var list=required("TASK_LIST",listId);
        if(!TaskProviderConnections.writable(auth,metadata)||!list.path("writable").asBoolean())throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");return auth;
    }
    private ObjectNode readAccess(UUID connection,UUID listId){
        var auth=connections().required(connection);var metadata=required("TASK_CONNECTION",connection);var list=required("TASK_LIST",listId);
        if(!list.path("connectionId").asText().equals(connection.toString()))throw ApiFailure.notFound();
        if(!metadata.path("providerId").asText().equals("google")||!metadata.path("enabled").asBoolean()||!metadata.path("readable").asBoolean()||auth.path("blocked").asBoolean())throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
        if(auth.path("paused").asBoolean())throw new ApiFailure(409,"TASK_PROVIDER_PAUSED");
        boolean selected=false;for(var value:auth.path("selected"))if(value.equals(list.path("remoteListId")))selected=true;
        if(!selected||!TaskProviderScans.approved(auth,list.path("remoteListId").asText())||!list.path("present").asBoolean()||!list.path("readable").asBoolean())throw ApiFailure.conflict();return auth;
    }
    private JsonNode reference(UUID connection,JsonNode list,JsonNode task){
        var metadata=required("TASK_CONNECTION",connection);String repository=TaskCloudPull.repository(metadata.path("remoteAccountId").asText(),list.path("remoteListId").asText());
        for(var value:task.path("externalReferences"))if(value.path("providerId").asText().equals("google")&&value.path("repositoryId").asText().equals(repository)&&value.path("taskListId").equals(list.get("id")))return value;
        throw ApiFailure.notFound();
    }
    private static ObjectNode identity(UUID connection,JsonNode list,JsonNode task,JsonNode reference){return object().put("connectionId",connection.toString()).put("listId",list.path("id").asText()).put("taskId",task.path("id").asText()).put("providerId","google").put("repositoryId",reference.path("repositoryId").asText()).put("externalId",reference.path("externalId").asText());}
    private ObjectNode owned(UUID connection,UUID id){var value=required(KIND,id);if(!value.path("connectionId").asText().equals(connection.toString()))throw ApiFailure.notFound();return value;}
    private ObjectNode required(String kind,UUID id){return store.find(kind,id).map(value->((ObjectNode)value).deepCopy()).orElseThrow(ApiFailure::notFound);}
    private void remove(JsonNode value){store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),KIND,uuid(value.get("id")));}
}
