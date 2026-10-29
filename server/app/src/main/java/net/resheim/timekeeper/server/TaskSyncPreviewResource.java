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

/** Owner-scoped, guarded field-level reconciliation for provider task observations. */
@Path("/api/v1/task-sync") @Blocking @Consumes("application/json") @Produces("application/json")
public class TaskSyncPreviewResource {
    private static final String METADATA = "metadata";
    private static final String TAGS = "tags";
    private static final String CATEGORIES = "categories";
    private static final String LINKS = "links";
    private static final String METADATA_PREFIX = METADATA + ".";
    private static final String METADATA_TAGS = METADATA_PREFIX + TAGS;
    private static final String METADATA_CATEGORIES = METADATA_PREFIX + CATEGORIES;
    private static final String METADATA_LINKS = METADATA_PREFIX + LINKS;
    private static final Set<String> METADATA_SET_COMPONENTS = Set.of(TAGS, CATEGORIES);
    private static final String REMINDERS = "reminders";
    private static final String REMINDERS_PREFIX = REMINDERS + ".";
    private static final java.util.List<String> REMINDER_KINDS = java.util.List.of("ABSOLUTE", "RELATIVE", "LOCATION", "CAR", "MESSAGE");
    private static final Set<String> FIELDS=Set.of("summary","url","body","status","scheduledDate","completedAt",
            "start","due","priority","flagged","urgent","parentTaskId","siblingOrder","checklistItems",
            "recurrence",REMINDERS_PREFIX+"ABSOLUTE",REMINDERS_PREFIX+"RELATIVE",REMINDERS_PREFIX+"LOCATION",
            REMINDERS_PREFIX+"CAR",REMINDERS_PREFIX+"MESSAGE",METADATA_TAGS,METADATA_CATEGORIES,METADATA_LINKS,"taskListId","sectionId","assignment");
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
            var accepted=object();var remote=fields(observed.get("fields"));
            remote.fields().forEachRemaining(field -> {
                if(capability(connection,field.getKey()).path("readable").asBoolean())
                    setField(accepted,field.getKey(),field.getValue());
            });
            if(!accepted.hasNonNull("summary")) throw ApiFailure.validation();
            for(var group:GROUPS) if(accepted.has(group[0])!=accepted.has(group[1])) throw ApiFailure.validation();
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
            if(tombstoned(key)) throw ApiFailure.conflict();
            var parentId=optionalText(accepted.get("parentTaskId")).map(TaskSyncPreviewResource::uuidText);
            var parent=parentId.map(value -> providerParent(value,key.providerId(),list.path("id").asText()));
            var projectId=parent.flatMap(value -> optionalText(value.get("projectId"))).map(TaskSyncPreviewResource::uuidText).map(ProjectId::new);
            var baseline=new ExternalTaskBaseline(1,store.now(),accepted.toString());
            var assignment=Optional.ofNullable(accepted.get("assignment")).filter(value -> !value.isNull()).map(JsonValues::assignment);
            var ref=new ExternalTaskReference(key.providerId(),key.repositoryId(),key.externalId(),Optional.empty(),
                    ExternalTaskSource.empty(),Optional.of(uuid(list.get("id"))),
                    optionalText(accepted.get("sectionId")).map(UUID::fromString),assignment,Optional.empty(),Optional.of(baseline));
            var planning=new TaskPlanning(optionalText(accepted.get("status")).map(TaskStatus::valueOf).orElse(TaskStatus.NOT_STARTED),
                    optionalText(accepted.get("scheduledDate")).map(LocalDate::parse),
                    optionalText(accepted.get("completedAt")).map(Instant::parse));
            var created=store.service().createTask(new CreateTask(projectId,parentId.map(TaskId::new),accepted.path("summary").asText(),
                    optionalText(accepted.get("url")),Set.of(ref),planning,checklist(accepted.get("checklistItems")),timing(accepted),
                    importance(accepted),JsonValues.body(accepted),accepted.path("siblingOrder").asInt(0),Set.of(),
                    recurrence(accepted.get("recurrence")),reminders(reminderArray(accepted.get(REMINDERS))),metadata(accepted.get(METADATA))));
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
            var observedFields=fields(observed.path("fields"));
            boolean changed=false;
            for(var decision:preview.path("decisions")) {
                String field=decision.path("field").asText(),outcome=decision.path("outcome").asText();
                var remote=observedFields.get(field);
                if(outcome.equals("REMOTE_ONLY")) {
                    if(field.equals("parentTaskId") && remote.isTextual()) validateParent(current,key,list.path("id").asText(),remote.asText());
                    if(Set.of("taskListId","sectionId","assignment").contains(field)) reference.set(field,remote.deepCopy());
                    else setTaskField(candidate,field,remote.deepCopy());
                    changed=true;
                }
                if(outcome.equals("REMOTE_ONLY") || outcome.equals("CONVERGED")) {
                    setField(baseline,field,remote.deepCopy());changed=true;
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
    private JsonNode providerParent(UUID parentId,String providerId,String listId) {
        var parent=store.find("TASK",parentId).orElseThrow(ApiFailure::conflict);
        for(var reference:parent.path("externalReferences")) if(reference.path("providerId").asText().equals(providerId)
                && reference.path("taskListId").asText().equals(listId)) return parent;
        throw ApiFailure.conflict();
    }
    private void validateParent(JsonNode child,ExternalTaskReferenceKey key,String listId,String parentId) {
        var first=providerParent(uuidText(parentId),key.providerId(),listId);
        if(!child.path("projectId").equals(first.path("projectId"))) throw ApiFailure.conflict();
        UUID cursor=uuidText(parentId);Set<UUID> seen=new HashSet<>();
        while(cursor!=null) {
            if(cursor.equals(uuid(child.get("id"))) || !seen.add(cursor)) throw ApiFailure.conflict();
            var parent=store.find("TASK",cursor).orElseThrow(ApiFailure::conflict);
            cursor=optionalText(parent.get("parentTaskId")).map(TaskSyncPreviewResource::uuidText).orElse(null);
        }
    }
    private static UUID uuidText(String value) {try {return UUID.fromString(value);}catch(IllegalArgumentException error){throw ApiFailure.validation();}}
    private boolean tombstoned(ExternalTaskReferenceKey key) {
        for(var tombstone:store.list("TASK_TOMBSTONE")) for(var reference:tombstone.path("externalReferences"))
            if(reference.path("providerId").asText().equals(key.providerId())
                    && reference.path("repositoryId").asText().equals(key.repositoryId())
                    && reference.path("externalId").asText().equals(key.externalId())) return true;
        return false;
    }

    /** Describe outbound changes without making a provider call or losing local-only fields. */
    @POST @Path("/export-preview") public Response exportPreview(JsonNode body) {
        var input=inputs.validate("TaskSyncExportInput",body);ContractInputs.queries(uri.getQueryParameters());
        return store.authenticated(identity,() -> {
            var connection=store.find("TASK_CONNECTION",uuid(input.get("connectionId"))).orElseThrow(ApiFailure::notFound);
            var list=store.find("TASK_LIST",uuid(input.get("listId"))).orElseThrow(ApiFailure::notFound);
            var task=store.find("TASK",uuid(input.get("taskId"))).orElseThrow(ApiFailure::notFound);
            if(!list.path("connectionId").asText().equals(connection.path("id").asText())) throw ApiFailure.validation();
            if(!connection.path("enabled").asBoolean() || !connection.path("writable").asBoolean()
                    || !list.path("present").asBoolean() || !list.path("writable").asBoolean()) throw ApiFailure.conflict();
            var key=new ExternalTaskReferenceKey(input.path("providerId").asText(),input.path("repositoryId").asText(),input.path("externalId").asText());
            if(!key.providerId().equals(connection.path("providerId").asText())) throw ApiFailure.validation();
            JsonNode reference=null;
            for(var value:task.path("externalReferences")) if(value.path("providerId").asText().equals(key.providerId())
                    && value.path("repositoryId").asText().equals(key.repositoryId())
                    && value.path("externalId").asText().equals(key.externalId())) {reference=value;break;}
            if(reference==null || !reference.path("taskListId").asText().equals(list.path("id").asText())) throw ApiFailure.notFound();
            var baseline=fields(reference.path("baseline").path("fields"));var writes=object();var warnings=array();
            for(var field:FIELDS.stream().sorted().toList()) {
                var local=local(task,reference,field);
                if(!baseline.has(field)) {
                    if(!field.equals("taskListId") && meaningful(field,local)) warning(warnings,field,"NO_BASELINE");
                    continue;
                }
                if(local.equals(baseline.get(field))) continue;
                var cap=capability(connection,field);
                if(!cap.path("readable").asBoolean() || !cap.path("writable").asBoolean()) {
                    warning(warnings,field,"NOT_WRITABLE");continue;
                }
                if(field.equals("taskListId")) {warning(warnings,field,"IDENTITY_RECONCILIATION_REQUIRED");continue;}
                if(clearing(field,local) && !cap.path("deletable").asBoolean()) {
                    warning(warnings,field,"CLEAR_UNSUPPORTED");continue;
                }
                setField(writes,field,local.deepCopy());
            }
            for(var group:GROUPS) if(writes.has(group[0]) || writes.has(group[1])) {
                if(!baseline.has(group[0]) || !baseline.has(group[1])
                        || !connection.path("capabilities").path("fields").path(group[0]).path("readable").asBoolean()
                        || !connection.path("capabilities").path("fields").path(group[1]).path("readable").asBoolean()
                        || !connection.path("capabilities").path("fields").path(group[0]).path("writable").asBoolean()
                        || !connection.path("capabilities").path("fields").path(group[1]).path("writable").asBoolean()
                        || blockedClear(connection,task,reference,baseline,group[0])
                        || blockedClear(connection,task,reference,baseline,group[1])) {
                    writes.remove(group[0]);writes.remove(group[1]);warning(warnings,group[0],"INCOMPLETE_GROUP");
                } else {
                    writes.set(group[0],local(task,reference,group[0]).deepCopy());
                    writes.set(group[1],local(task,reference,group[1]).deepCopy());
                }
            }
            if(writes.has(REMINDERS) && connection.path("capabilities").path("fields").has(REMINDERS)
                    && REMINDER_KINDS.stream().noneMatch(kind -> connection.path("capabilities").path("fields").has(REMINDERS_PREFIX+kind))
                    && REMINDER_KINDS.stream().allMatch(kind -> baseline.has(REMINDERS_PREFIX+kind))) {
                boolean blocked=java.util.stream.StreamSupport.stream(warnings.spliterator(),false)
                        .anyMatch(row -> row.path("field").asText().startsWith(REMINDERS_PREFIX));
                if(blocked) {writes.remove(REMINDERS);warning(warnings,REMINDERS,"INCOMPLETE_GROUP");}
                else writes.set(REMINDERS,task.path(REMINDERS).deepCopy());
            }
            var result=object();result.put("taskId",task.path("id").asText());result.put("taskEtag",etag(task));
            result.set("writeFields",writes);result.set("warnings",warnings);
            return new ApiReplies(200,result,null,null).response();
        });
    }
    private static boolean meaningful(String field,JsonNode value) {
        if(value.isNull() || value.isMissingNode() || value.isContainerNode() && value.isEmpty() || value.isBoolean() && !value.asBoolean()
                || value.isIntegralNumber() && value.asInt()==0 || value.isTextual() && value.asText().isBlank()) return false;
        if(field.equals("priority") && value.asText().equals("UNSPECIFIED") || field.equals("status") && value.asText().equals("NOT_STARTED")) return false;
        if(field.equals("body") && value.path("content").asText().isEmpty()) return false;
        if(field.equals("recurrence") && value.path("series").isNull() && value.path("occurrences").isEmpty()) return false;
        if(field.equals(METADATA) && value.path(TAGS).isEmpty() && value.path(CATEGORIES).isEmpty() && value.path(LINKS).isEmpty()) return false;
        return true;
    }
    private static boolean clearing(String field,JsonNode value) {
        return value.isNull() || value.isContainerNode() && value.isEmpty() || value.isTextual() && value.asText().isEmpty()
                || field.equals("body") && value.path("content").asText().isEmpty();
    }
    private static boolean blockedClear(JsonNode connection,JsonNode task,JsonNode reference,JsonNode baseline,String field) {
        var value=local(task,reference,field);
        return !value.equals(baseline.get(field)) && clearing(field,value)
                && !connection.path("capabilities").path("fields").path(field).path("deletable").asBoolean();
    }
    private static void warning(ArrayNode warnings,String field,String reason) {
        var row=object();row.put("field",field);row.put("reason",reason);warnings.add(row);
    }

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
        var remote=fields(item.path("fields"));var baseline=fields(reference.path("baseline").path("fields"));
        boolean conflict=false,update=false,unsupported=false,localOnly=false;
        for(var entry=remote.fields();entry.hasNext();) {
            var field=entry.next();String name=field.getKey();if(!FIELDS.contains(name)) throw ApiFailure.validation();
            String outcome;
            if(!capability(connection,name).path("readable").asBoolean()) outcome="UNSUPPORTED";
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
    private static ObjectNode fields(JsonNode values) {
        var result=object();
        if(values==null || !values.isObject()) return result;
        values.fields().forEachRemaining(entry->{
            if(entry.getKey().equals(METADATA) && entry.getValue().isObject())
                entry.getValue().fields().forEachRemaining(component->{
                    JsonNode value=component.getValue();
                    if(METADATA_SET_COMPONENTS.contains(component.getKey()) && value.isArray()
                            && java.util.stream.StreamSupport.stream(value.spliterator(),false).allMatch(JsonNode::isTextual)) {
                        var ordered=array();java.util.stream.StreamSupport.stream(value.spliterator(),false)
                                .map(JsonNode::asText).sorted().forEach(ordered::add);value=ordered;
                    }
                    result.set(METADATA_PREFIX+component.getKey(),value);
                });
            else if(entry.getKey().equals(REMINDERS) && (entry.getValue().isArray() || entry.getValue().isObject())) {
                var groups=reminderGroups(entry.getValue());
                groups.fields().forEachRemaining(group -> result.set(REMINDERS_PREFIX+group.getKey(),
                        JsonValues.reminders(JsonValues.reminders(group.getValue()))));
            } else result.set(entry.getKey(),entry.getValue());
        });
        return result;
    }
    private static JsonNode capability(JsonNode connection,String field) {
        var declarations=connection.path("capabilities").path("fields");
        // An explicit component overrides an older aggregate declaration.
        if(declarations.has(field)) return declarations.path(field);
        if(field.startsWith(METADATA_PREFIX)) return declarations.path(METADATA);
        if(field.startsWith(REMINDERS_PREFIX)) return declarations.path(REMINDERS);
        return declarations.path(field);
    }
    private static void setField(ObjectNode target,String field,JsonNode value) {
        if(field.startsWith(METADATA_PREFIX)) {
            if(!target.path(METADATA).isObject())target.set(METADATA,object());
            ((ObjectNode)target.get(METADATA)).set(field.substring(METADATA_PREFIX.length()),value);
        } else if(field.startsWith(REMINDERS_PREFIX)) {
            var groups=reminderGroups(target.get(REMINDERS));
            groups.set(field.substring(REMINDERS_PREFIX.length()),value);
            target.set(REMINDERS,groups);
        } else target.set(field,value);
    }
    private static JsonNode local(JsonNode task,JsonNode reference,String field) {
        if(field.startsWith(REMINDERS_PREFIX))
            return JsonValues.reminders(JsonValues.reminders(reminderGroups(task.path(REMINDERS)).path(field.substring(REMINDERS_PREFIX.length()))));
        var value=field.startsWith(METADATA_PREFIX)?task.path(METADATA).get(field.substring(METADATA_PREFIX.length()))
                :Set.of("taskListId","sectionId","assignment").contains(field)?reference.get(field):task.get(field);
        return value==null?NullNode.instance:value;
    }
    /** Arrays are legacy complete observations; objects retain only observed groups. */
    private static ObjectNode reminderGroups(JsonNode value) {
        if(value!=null && value.isObject()) return ((ObjectNode)value).deepCopy();
        var groups=object();
        if(value!=null && value.isArray()) {
            for(var kind:REMINDER_KINDS) groups.putArray(kind);
            for(var reminder:value) {
                var group=groups.get(reminder.path("kind").asText());
                if(group==null) throw ApiFailure.validation();
                ((ArrayNode)group).add(reminder.deepCopy());
            }
        }
        return groups;
    }
    private static ArrayNode reminderArray(JsonNode value) {
        if(value!=null && value.isArray()) return ((ArrayNode)value).deepCopy();
        var result=array();
        if(value!=null && value.isObject()) for(var kind:REMINDER_KINDS)
            for(var reminder:value.path(kind)) result.add(reminder.deepCopy());
        return result;
    }
    /** Task snapshots remain arrays; merge only the observed kind, retaining other entries. */
    private static void setTaskField(ObjectNode target,String field,JsonNode value) {
        if(!field.startsWith(REMINDERS_PREFIX)) {setField(target,field,value);return;}
        String kind=field.substring(REMINDERS_PREFIX.length());
        var merged=array();boolean inserted=false;
        for(var reminder:target.path(REMINDERS)) {
            if(reminder.path("kind").asText().equals(kind)) {
                if(!inserted) {for(var replacement:value) merged.add(replacement.deepCopy());inserted=true;}
            } else merged.add(reminder.deepCopy());
        }
        if(!inserted) for(var replacement:value) merged.add(replacement.deepCopy());
        target.set(REMINDERS,merged);
    }
    private static boolean localChanged(JsonNode task,JsonNode reference) {
        var baseline=fields(reference.path("baseline").path("fields"));if(baseline.isEmpty()) return true;
        for(var field=baseline.fieldNames();field.hasNext();) {
            String name=field.next();if(!FIELDS.contains(name) || !local(task,reference,name).equals(baseline.get(name))) return true;
        }
        return false;
    }
}
