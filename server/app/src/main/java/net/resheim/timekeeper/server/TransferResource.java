package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@Path("/api/v1/data") @Blocking @Consumes("application/json") @Produces("application/json")
public class TransferResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;
    private static final Map<String,String> TYPES=Map.of("projects","PROJECT","tasks","TASK","labels","LABEL","activities","ACTIVITY");
    @GET @Path("/export") public Response exportData() {
        ContractInputs.queries(uri.getQueryParameters());
        return store.transaction(identity.owner(),() -> {
            var value=object();value.put("format","timekeeper-domain-v1");value.put("zoneId",store.profile().get("zoneId").asText());
            TYPES.forEach((name,type) -> {
                var records=store.list(type);if(records.size()>10000) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                var array=value.putArray(name);for(JsonNode record:records) {var copy=((ObjectNode)record).deepCopy();copy.remove(List.of("ownerId","version"));array.add(copy);}
            });
            if(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>20*1024*1024) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            return new ApiReplies(200,value,null,null).response();
        });
    }
    @POST @Path("/import") public Response importData(@HeaderParam("Idempotency-Key") String key,JsonNode body) {
        if(body!=null && body.isObject()) for(String name:TYPES.keySet()) {
            if(body.has(name) && body.get(name).isArray() && body.get(name).size()>10000) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
        }
        JsonNode input=inputs.validate("TransferSnapshot",body);ContractInputs.zone(input.get("zoneId").asText());ContractInputs.queries(uri.getQueryParameters());
        return store.transaction(identity.owner(),() -> receipts.execute(key,"POST","/data/import",null,input,() -> importRecords(input)).response());
    }
    private ApiReplies importRecords(JsonNode input) {
        Map<UUID,ProjectId> projects=new HashMap<>();Map<UUID,TaskId> tasks=new HashMap<>();Map<UUID,LabelId> labels=new HashMap<>();Set<UUID> sourceIds=new HashSet<>();
        var result=object();var mappings=result.putArray("ids");var service=store.service();
        // Validate source identity uniqueness before any record is created.
        TYPES.forEach((name,type) -> {for(JsonNode item:input.get(name)) if(!sourceIds.add(uuid(item.get("id")))) throw ApiFailure.validation();});
        Set<UUID> sourceProjects=new HashSet<>(),sourceLabels=new HashSet<>();Map<UUID,JsonNode> sourceTasks=new HashMap<>();
        input.get("projects").forEach(item -> sourceProjects.add(uuid(item.get("id"))));
        input.get("labels").forEach(item -> sourceLabels.add(uuid(item.get("id"))));
        input.get("tasks").forEach(item -> sourceTasks.put(uuid(item.get("id")),item));
        for(JsonNode item:input.get("tasks")) {
            var project=optionalText(item.get("projectId")).map(UUID::fromString);
            if(project.isPresent() && !sourceProjects.contains(project.get())) throw ApiFailure.validation();
            Set<UUID> seen=new HashSet<>();JsonNode current=item;
            while(!current.get("parentTaskId").isNull()) {
                UUID parentId=uuid(current.get("parentTaskId"));if(!seen.add(parentId)) throw ApiFailure.validation();
                JsonNode ancestor=sourceTasks.get(parentId);
                if(ancestor==null || project.isEmpty() || !optionalText(ancestor.get("projectId")).map(UUID::fromString).equals(project)) throw ApiFailure.validation();
                current=ancestor;
            }
        }
        for(JsonNode item:input.get("activities")) {
            Activity.validateStart(Instant.parse(item.get("start").asText()),store.now());
            if(!sourceTasks.containsKey(uuid(item.get("taskId")))) throw ApiFailure.validation();
            for(JsonNode label:item.get("labelIds")) if(!sourceLabels.contains(uuid(label))) throw ApiFailure.validation();
            var end=optionalText(item.get("end")).map(Instant::parse);if(end.isPresent() && end.get().isBefore(Instant.parse(item.get("start").asText()))) throw ApiFailure.validation();
        }
        for(JsonNode item:input.get("projects")) {
            var created=service.createProject(new CreateProject(item.get("name").asText()));projects.put(uuid(item.get("id")),created.id());mapping(mappings,"PROJECT",item,created.id().value());
        }
        for(JsonNode item:input.get("labels")) {
            var created=service.createLabel(new CreateLabel(item.get("name").asText(),optionalText(item.get("color"))));labels.put(uuid(item.get("id")),created.id());mapping(mappings,"LABEL",item,created.id().value());
        }
        Map<UUID,JsonNode> pending=new LinkedHashMap<>();input.get("tasks").forEach(item -> pending.put(uuid(item.get("id")),item));
        while(!pending.isEmpty()) {
            boolean progressed=false;var iterator=pending.entrySet().iterator();
            while(iterator.hasNext()) {
                var entry=iterator.next();JsonNode item=entry.getValue();
                var parent=optionalText(item.get("parentTaskId")).map(UUID::fromString);
                if(parent.isPresent() && !tasks.containsKey(parent.get())) {
                    if(!pending.containsKey(parent.get())) throw ApiFailure.validation();continue;
                }
                var project=optionalText(item.get("projectId")).map(UUID::fromString).map(id -> required(projects,id));
                var parentId=parent.map(id -> required(tasks,id));
                var created=service.createTask(new CreateTask(project,parentId,item.get("summary").asText(),optionalText(item.get("url")),references(item.get("externalReferences"))));
                tasks.put(entry.getKey(),created.id());mapping(mappings,"TASK",item,created.id().value());iterator.remove();progressed=true;
            }
            if(!progressed) throw ApiFailure.validation();
        }
        for(JsonNode item:input.get("activities")) {
            Set<LabelId> assigned=new HashSet<>();item.get("labelIds").forEach(label -> assigned.add(required(labels,uuid(label))));
            TaskId task=required(tasks,uuid(item.get("taskId")));Instant start=Instant.parse(item.get("start").asText());var end=optionalText(item.get("end")).map(Instant::parse);
            // Preserve the transfer's manual flag while reusing domain validation and owned persistence.
            if(end.isEmpty() && store.active().isPresent()) throw ApiFailure.conflict();
            Activity created=new Activity(new ActivityId(UUID.randomUUID()),task,new OwnerId(store.owner()),start,end,item.get("summary").asText(),assigned,item.get("manual").asBoolean(),0);
            store.save("ACTIVITY",created);store.change("ACTIVITY","CREATED",created.id().value().toString());mapping(mappings,"ACTIVITY",item,created.id().value());
        }
        service.rolloverActivities(new OwnerId(store.owner()));
        result.put("revision",store.revision());return new ApiReplies(201,result,null,null);
    }
    private static <T> T required(Map<UUID,T> values,UUID id) {T value=values.get(id);if(value==null) throw ApiFailure.validation();return value;}
    private static void mapping(com.fasterxml.jackson.databind.node.ArrayNode mappings,String type,JsonNode source,UUID id) {
        var item=object();item.put("entityType",type);item.put("sourceId",uuid(source.get("id")).toString());item.put("id",id.toString());mappings.add(item);
    }
}
