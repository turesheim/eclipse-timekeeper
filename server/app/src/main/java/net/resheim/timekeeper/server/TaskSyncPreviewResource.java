package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Context;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;

/** Read-only, field-level reconciliation preview for one provider list page. */
@Path("/api/v1/task-sync") @Blocking @Consumes("application/json") @Produces("application/json")
public class TaskSyncPreviewResource {
    private static final Set<String> FIELDS=Set.of("summary","url","body","status","scheduledDate","completedAt",
            "start","due","priority","flagged","urgent","parentTaskId","siblingOrder","checklistItems",
            "recurrence","reminders","metadata","taskListId","sectionId","assignment");
    private static final String[][] GROUPS={{"status","completedAt"},{"parentTaskId","siblingOrder"},{"taskListId","sectionId"}};
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;

    /** Import one fully observed root task. A receipt and provider identity prevent duplicate creates. */
    @POST @Path("/import") public Response importTask(JsonNode body) {
        var input=inputs.validate("TaskSyncImportInput",body);ContractInputs.queries(uri.getQueryParameters());
        var observed=input.get("item");String operation=observed.path("operationId").asText();
        return store.authenticated(identity,() -> receipts.execute(operation,"POST","/task-sync/import",null,input,() -> {
            var connection=store.find("TASK_CONNECTION",uuid(input.get("connectionId"))).orElseThrow(ApiFailure::notFound);
            var list=store.find("TASK_LIST",uuid(input.get("listId"))).orElseThrow(ApiFailure::notFound);
            if(!list.path("connectionId").asText().equals(connection.path("id").asText())) throw ApiFailure.validation();
            if(!input.path("sourceState").asText().equals("HEALTHY") || !connection.path("enabled").asBoolean()
                    || !connection.path("readable").asBoolean() || !list.path("present").asBoolean()
                    || !list.path("readable").asBoolean()) throw ApiFailure.conflict();
            var key=new ExternalTaskReferenceKey(observed.path("providerId").asText(),observed.path("repositoryId").asText(),observed.path("externalId").asText());
            if(!key.providerId().equals(connection.path("providerId").asText()) || observed.path("deleted").asBoolean()) throw ApiFailure.validation();
            var accepted=object();var remote=observed.get("fields");
            remote.fields().forEachRemaining(field -> {
                if(connection.path("capabilities").path("fields").path(field.getKey()).path("readable").asBoolean())
                    accepted.set(field.getKey(),field.getValue());
            });
            if(!accepted.hasNonNull("summary")) throw ApiFailure.validation();
            for(var group:GROUPS) if(accepted.has(group[0])!=accepted.has(group[1])) throw ApiFailure.validation();
            if(accepted.hasNonNull("parentTaskId")) throw ApiFailure.conflict(); // Remote hierarchy is resolved in a later pass.
            if(accepted.hasNonNull("taskListId") && !accepted.path("taskListId").asText().equals(list.path("id").asText())) throw ApiFailure.conflict();
            if(accepted.hasNonNull("sectionId") && !sectionInList(accepted.path("sectionId").asText(),list.path("id").asText())) throw ApiFailure.validation();
            var old=store.byReference(key);
            if(old.isPresent()) {
                var task=old.orElseThrow();
                for(var reference:task.path("externalReferences")) if(reference.path("providerId").asText().equals(key.providerId())
                        && reference.path("repositoryId").asText().equals(key.repositoryId()) && reference.path("externalId").asText().equals(key.externalId())) {
                    if(reference.path("taskListId").asText().equals(list.path("id").asText())
                            && reference.path("baseline").path("fields").equals(accepted)) return ApiReplies.resource(200,task,null);
                }
                throw ApiFailure.conflict();
            }
            var baseline=new ExternalTaskBaseline(1,store.now(),accepted.toString());
            var assignment=Optional.ofNullable(accepted.get("assignment")).filter(value -> !value.isNull()).map(JsonValues::assignment);
            var ref=new ExternalTaskReference(key.providerId(),key.repositoryId(),key.externalId(),Optional.empty(),
                    ExternalTaskSource.empty(),Optional.of(uuid(list.get("id"))),
                    optionalText(accepted.get("sectionId")).map(UUID::fromString),assignment,Optional.empty(),Optional.of(baseline));
            var planning=new TaskPlanning(optionalText(accepted.get("status")).map(TaskStatus::valueOf).orElse(TaskStatus.NOT_STARTED),
                    optionalText(accepted.get("scheduledDate")).map(LocalDate::parse),
                    optionalText(accepted.get("completedAt")).map(Instant::parse));
            var created=store.service().createTask(new CreateTask(Optional.empty(),Optional.empty(),accepted.path("summary").asText(),
                    optionalText(accepted.get("url")),Set.of(ref),planning,checklist(accepted.get("checklistItems")),timing(accepted),
                    importance(accepted),JsonValues.body(accepted),accepted.path("siblingOrder").asInt(0),Set.of(),
                    recurrence(accepted.get("recurrence")),reminders(accepted.get("reminders")),metadata(accepted.get("metadata"))));
            var value=snapshot(store.owner(),created);
            return ApiReplies.resource(201,value,"/api/v1/tasks/"+value.path("id").asText());
        }).response());
    }

    /** Apply non-conflicting remote fields after an ETag-guarded three-way comparison. */
    @POST @Path("/apply") public Response apply(JsonNode body,@HeaderParam("If-Match") String match) {
        var input=inputs.validate("TaskSyncApplyInput",body);ContractInputs.queries(uri.getQueryParameters());
        var observed=input.get("item");String operation=observed.path("operationId").asText();
        return store.authenticated(identity,() -> receipts.execute(operation,"POST","/task-sync/apply",match,input,() -> {
            var connection=store.find("TASK_CONNECTION",uuid(input.get("connectionId"))).orElseThrow(ApiFailure::notFound);
            var list=store.find("TASK_LIST",uuid(input.get("listId"))).orElseThrow(ApiFailure::notFound);
            if(!list.path("connectionId").asText().equals(connection.path("id").asText())) throw ApiFailure.validation();
            if(!input.path("sourceState").asText().equals("HEALTHY") || !connection.path("enabled").asBoolean()
                    || !connection.path("readable").asBoolean() || !list.path("present").asBoolean()
                    || !list.path("readable").asBoolean()) throw ApiFailure.conflict();
            var key=new ExternalTaskReferenceKey(observed.path("providerId").asText(),observed.path("repositoryId").asText(),observed.path("externalId").asText());
            if(!key.providerId().equals(connection.path("providerId").asText()) || observed.path("deleted").asBoolean()) throw ApiFailure.validation();
            var current=store.byReference(key).orElseThrow(ApiFailure::notFound);
            ApiReplies.match(current,match);
            var preview=previewItem(observed,key,connection,list,true,false);
            if(Set.of("CONFLICT","RECONCILE_ID","INCOMPLETE","DELETE_DEFERRED","DELETE_CONFLICT","DELETE_REVIEW").contains(preview.path("status").asText())) throw ApiFailure.conflict();
            var candidate=((ObjectNode)current).deepCopy();
            var refs=(ArrayNode)candidate.get("externalReferences");ObjectNode reference=null;
            for(var value:refs) if(value.path("providerId").asText().equals(key.providerId())
                    && value.path("repositoryId").asText().equals(key.repositoryId())
                    && value.path("externalId").asText().equals(key.externalId())) {reference=(ObjectNode)value;break;}
            if(reference==null) throw ApiFailure.conflict();
            var baseline=reference.path("baseline").path("fields").isObject()
                    ? ((ObjectNode)reference.path("baseline").path("fields")).deepCopy() : object();
            boolean changed=false;
            for(var decision:preview.path("decisions")) {
                String field=decision.path("field").asText(),outcome=decision.path("outcome").asText();
                var remote=observed.path("fields").get(field);
                if(outcome.equals("REMOTE_ONLY")) {
                    if(field.equals("parentTaskId") && remote.isTextual()) validateParent(uuid(current.get("id")),remote.asText());
                    if(Set.of("taskListId","sectionId","assignment").contains(field)) reference.set(field,remote.deepCopy());
                    else candidate.set(field,remote.deepCopy());
                    changed=true;
                }
                if(outcome.equals("REMOTE_ONLY") || outcome.equals("CONVERGED")) {
                    baseline.set(field,remote.deepCopy());changed=true;
                }
            }
            if(!changed) return ApiReplies.resource(200,current,null);
            var snapshot=object();snapshot.put("version",1);snapshot.put("capturedAt",store.now().toString());snapshot.set("fields",baseline);
            reference.set("baseline",snapshot);candidate.put("version",current.path("version").asLong()+1);
            var updated=(Task)decode("TASK",candidate);
            store.save("TASK",updated);store.change("TASK","UPDATED",uuid(current.get("id")).toString());
            return ApiReplies.resource(200,snapshot(store.owner(),updated),null);
        }).response());
    }
    private void validateParent(UUID child,String parentId) {
        UUID cursor=uuidText(parentId);Set<UUID> seen=new HashSet<>();
        while(cursor!=null) {
            if(cursor.equals(child) || !seen.add(cursor)) throw ApiFailure.conflict();
            var parent=store.find("TASK",cursor).orElseThrow(ApiFailure::conflict);
            cursor=optionalText(parent.get("parentTaskId")).map(TaskSyncPreviewResource::uuidText).orElse(null);
        }
    }
    private static UUID uuidText(String value) {try {return UUID.fromString(value);}catch(IllegalArgumentException error){throw ApiFailure.validation();}}

    @POST @Path("/preview") public Response preview(JsonNode body) {
        var input=inputs.validate("TaskSyncPreviewInput",body);ContractInputs.queries(uri.getQueryParameters());
        return store.authenticated(identity,() -> {
            UUID connectionId=uuid(input.get("connectionId")),listId=uuid(input.get("listId"));
            var connection=store.find("TASK_CONNECTION",connectionId).orElseThrow(ApiFailure::notFound);
            var list=store.find("TASK_LIST",listId).orElseThrow(ApiFailure::notFound);
            if(!list.path("connectionId").asText().equals(connectionId.toString())) throw ApiFailure.validation();
            boolean healthy=input.path("sourceState").asText().equals("HEALTHY")
                    && connection.path("enabled").asBoolean() && connection.path("readable").asBoolean()
                    && list.path("present").asBoolean() && list.path("readable").asBoolean();
            boolean complete=healthy && input.path("scanComplete").asBoolean() && completedScan(connectionId,listId);
            var result=object();result.put("revision",store.revision());
            optionalText(input.get("pageToken")).ifPresentOrElse(value -> result.put("pageToken",value),
                    () -> result.putNull("pageToken"));
            var rows=result.putArray("items");Set<UUID> operations=new HashSet<>();Set<ExternalTaskReferenceKey> identities=new HashSet<>();
            for(var item:input.path("items")) {
                UUID operation=uuid(item.get("operationId"));if(!operations.add(operation)) throw ApiFailure.validation();
                var key=new ExternalTaskReferenceKey(item.path("providerId").asText(),item.path("repositoryId").asText(),item.path("externalId").asText());
                if(!identities.add(key) || !key.providerId().equals(connection.path("providerId").asText())) throw ApiFailure.validation();
                rows.add(previewItem(item,key,connection,list,healthy,complete));
            }
            return new ApiReplies(200,result,null,null).response();
        });
    }
    private boolean completedScan(UUID connectionId,UUID listId) {
        for(var cursor:store.list("TASK_SYNC_CURSOR")) if(cursor.path("connectionId").asText().equals(connectionId.toString())
                && cursor.path("listId").asText().equals(listId.toString())
                && cursor.path("lastCompleteAt").isTextual()
                && !cursor.path("mode").asText().equals("RESCAN_REQUIRED")) return true;
        return false;
    }
    private ObjectNode previewItem(JsonNode item,ExternalTaskReferenceKey key,JsonNode connection,JsonNode list,boolean healthy,boolean complete) {
        var row=object();row.put("operationId",item.path("operationId").asText());
        row.put("providerId",key.providerId());row.put("repositoryId",key.repositoryId());row.put("externalId",key.externalId());
        row.putNull("taskId");row.putNull("taskEtag");row.putNull("reason");var decisions=row.putArray("decisions");
        if(!healthy) {row.put("status","DEFERRED");row.put("reason","SOURCE_UNAVAILABLE");return row;}
        var found=store.byReference(key);
        if(found.isEmpty()) {
            if(item.path("deleted").asBoolean()) row.put("status","IGNORE_DELETED");
            else if(!connection.path("capabilities").path("fields").path("summary").path("readable").asBoolean()) {
                row.put("status","UNSUPPORTED");row.put("reason","SUMMARY_UNSUPPORTED");
            } else if(item.path("fields").hasNonNull("summary")) row.put("status","CREATE");
            else {row.put("status","INCOMPLETE");row.put("reason","SUMMARY_REQUIRED");}
            return row;
        }
        var task=found.orElseThrow();row.put("taskId",task.path("id").asText());row.put("taskEtag",etag(task));
        JsonNode reference=null;
        for(var value:task.path("externalReferences")) if(value.path("providerId").asText().equals(key.providerId())
                && value.path("repositoryId").asText().equals(key.repositoryId())
                && value.path("externalId").asText().equals(key.externalId())) {reference=value;break;}
        if(reference==null) {row.put("status","RECONCILE_ID");row.put("reason","IDENTITY_ALIAS");return row;}
        if(!reference.path("taskListId").asText().equals(list.path("id").asText())) {
            row.put("status","CONFLICT");row.put("reason","OTHER_LIST");return row;
        }
        if(item.path("deleted").asBoolean()) {
            if(!complete) {row.put("status","DELETE_DEFERRED");row.put("reason","COMPLETE_SCAN_REQUIRED");return row;}
            if(localChanged(task,reference)) {row.put("status","DELETE_CONFLICT");row.put("reason","LOCAL_CHANGES");return row;}
            row.put("status","DELETE_REVIEW");row.put("reason",task.path("externalReferences").size()>1?"OTHER_PROVIDER_LINKS":"EXPLICIT_REVIEW_REQUIRED");return row;
        }
        var remote=item.path("fields");var baseline=reference.path("baseline").path("fields");
        boolean conflict=false,update=false,unsupported=false,localOnly=false;
        for(var entry=remote.fields();entry.hasNext();) {
            var field=entry.next();String name=field.getKey();if(!FIELDS.contains(name)) throw ApiFailure.validation();
            String outcome;
            if(!connection.path("capabilities").path("fields").path(name).path("readable").asBoolean()) outcome="UNSUPPORTED";
            else if(incompleteGroup(remote,name)) outcome="INCOMPLETE_GROUP";
            else if(name.equals("taskListId") && field.getValue().isTextual() && !field.getValue().asText().equals(list.path("id").asText())) outcome="OTHER_LIST";
            else if(name.equals("sectionId") && field.getValue().isTextual() && !sectionInList(field.getValue().asText(),list.path("id").asText())) outcome="OTHER_LIST";
            else {
                JsonNode local=local(task,reference,name),remoteValue=field.getValue();
                if(!baseline.has(name)) outcome=local.equals(remoteValue)?"CONVERGED":"NO_BASELINE";
                else {
                    JsonNode common=baseline.get(name);
                    boolean localDiff=!local.equals(common),remoteDiff=!remoteValue.equals(common);
                    outcome=!localDiff && !remoteDiff?"UNCHANGED":!localDiff?"REMOTE_ONLY":!remoteDiff?"LOCAL_ONLY":local.equals(remoteValue)?"CONVERGED":"CONFLICT";
                }
            }
            var decision=object();decision.put("field",name);decision.put("outcome",outcome);decisions.add(decision);
            conflict|=Set.of("CONFLICT","NO_BASELINE","INCOMPLETE_GROUP","OTHER_LIST").contains(outcome);
            update|=outcome.equals("REMOTE_ONLY");unsupported|=outcome.equals("UNSUPPORTED");localOnly|=outcome.equals("LOCAL_ONLY");
        }
        for(var group:GROUPS) {
            ObjectNode first=null,second=null;
            for(var decision:decisions) {
                if(decision.path("field").asText().equals(group[0])) first=(ObjectNode)decision;
                if(decision.path("field").asText().equals(group[1])) second=(ObjectNode)decision;
            }
            if(first!=null && second!=null &&
                    ((first.path("outcome").asText().equals("LOCAL_ONLY") && second.path("outcome").asText().equals("REMOTE_ONLY"))
                    || (first.path("outcome").asText().equals("REMOTE_ONLY") && second.path("outcome").asText().equals("LOCAL_ONLY")))) {
                first.put("outcome","CONFLICT");second.put("outcome","CONFLICT");conflict=true;
            }
        }
        row.put("status",conflict?"CONFLICT":update?"UPDATE":unsupported?"UNSUPPORTED":localOnly?"KEEP_LOCAL":"UNCHANGED");
        return row;
    }
    private boolean sectionInList(String sectionId,String listId) {
        try {return store.find("TASK_SECTION",UUID.fromString(sectionId)).map(value -> value.path("taskListId").asText().equals(listId)).orElse(false);}
        catch(IllegalArgumentException error) {return false;}
    }
    private static boolean incompleteGroup(JsonNode values,String field) {
        for(var group:GROUPS) if(field.equals(group[0]) || field.equals(group[1])) return values.has(group[0])!=values.has(group[1]);
        return false;
    }
    private static JsonNode local(JsonNode task,JsonNode reference,String field) {
        var value=Set.of("taskListId","sectionId","assignment").contains(field)?reference.get(field):task.get(field);
        return value==null?NullNode.instance:value;
    }
    private static boolean localChanged(JsonNode task,JsonNode reference) {
        var baseline=reference.path("baseline").path("fields");if(!baseline.isObject() || baseline.isEmpty()) return true;
        for(var field=baseline.fieldNames();field.hasNext();) {
            String name=field.next();if(!FIELDS.contains(name) || !local(task,reference,name).equals(baseline.get(name))) return true;
        }
        return false;
    }
}
