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
    private static final Map<String,String> TYPES=Map.of("clients","CLIENT","projects","PROJECT","tasks","TASK","labels","LABEL","activities","ACTIVITY","workSessions","WORK_SESSION");
    @GET @Path("/export") public Response exportData() {
        ContractInputs.queries(uri.getQueryParameters());
        return store.authenticated(identity,() -> {
            var value=object();value.put("format","timekeeper-domain-v3");value.put("zoneId",store.profile().get("zoneId").asText());
            TYPES.forEach((name,type) -> {
                var records=store.list(type);if(records.size()>10000) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
                var array=value.putArray(name);for(JsonNode record:records) {var copy=((ObjectNode)record).deepCopy();copy.remove(List.of("ownerId","version","state"));array.add(copy);}
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
        return store.authenticated(identity,() -> receipts.execute(key,"POST","/data/import",null,input,() -> importRecords(input)).response());
    }
    private ApiReplies importRecords(JsonNode input) {
        Map<UUID,UUID> clients=new HashMap<>();
        Map<UUID,ProjectId> projects=new HashMap<>();Map<UUID,TaskId> tasks=new HashMap<>();Map<UUID,LabelId> labels=new HashMap<>();Map<UUID,WorkSessionId> sessions=new HashMap<>();Set<UUID> sourceIds=new HashSet<>();
        var result=object();var mappings=result.putArray("ids");var service=store.service();
        // Validate source identity uniqueness before any record is created.
        TYPES.forEach((name,type) -> {for(JsonNode item:input.path(name)) if(!sourceIds.add(uuid(item.get("id")))) throw ApiFailure.validation();});
        Set<UUID> sourceProjects=new HashSet<>(),sourceLabels=new HashSet<>(),sourceSessions=new HashSet<>();Map<UUID,JsonNode> sourceTasks=new HashMap<>();
        input.get("projects").forEach(item -> sourceProjects.add(uuid(item.get("id"))));
        input.get("labels").forEach(item -> sourceLabels.add(uuid(item.get("id"))));
        input.path("workSessions").forEach(item -> sourceSessions.add(uuid(item.get("id"))));
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
            var task=optionalText(item.get("taskId")).map(UUID::fromString);
            if(task.isPresent() && !sourceTasks.containsKey(task.get())) throw ApiFailure.validation();
            var project=optionalText(item.get("projectId")).map(UUID::fromString);
            if(project.isPresent() && (!sourceProjects.contains(project.get()) || (task.isPresent() && !optionalText(sourceTasks.get(task.get()).get("projectId")).equals(optionalText(item.get("projectId")))))) throw ApiFailure.validation();
            for(JsonNode label:item.get("labelIds")) if(!sourceLabels.contains(uuid(label))) throw ApiFailure.validation();
            var end=optionalText(item.get("end")).map(Instant::parse);if(end.isPresent() && end.get().isBefore(Instant.parse(item.get("start").asText()))) throw ApiFailure.validation();
        }
        if(input.path("format").asText().equals("timekeeper-domain-v3")) {
            var sourceClients=new HashSet<UUID>();input.path("clients").forEach(item -> sourceClients.add(uuid(item.get("id"))));
            for(String collection:List.of("projects","workSessions")) for(var item:input.path(collection))
                if(!sourceClients.contains(uuid(item.get("clientId")))) throw ApiFailure.validation();
        }
        for(var item:input.path("clients")) {
            var fields=object();fields.put("name",item.get("name").asText());fields.put("color",item.get("color").asText());
            var created=store.createClient(fields);clients.put(uuid(item.get("id")),uuid(created.get("id")));mapping(mappings,"CLIENT",item,uuid(created.get("id")));
        }
        int openSessions=0;
        for(JsonNode item:input.path("workSessions")) {
            Instant start=Instant.parse(item.get("start").asText());WorkSession.validateStart(start,store.now());
            var end=optionalText(item.get("end")).map(Instant::parse);
            if(end.filter(value -> value.isBefore(start)).isPresent()) throw ApiFailure.validation();
            if(end.isEmpty()) openSessions++;
            for(var label:item.path("labelIds")) if(!sourceLabels.contains(uuid(label))) throw ApiFailure.validation();
        }
        if(openSessions>1) throw ApiFailure.validation();
        if(openSessions==1 && store.workSessionService().activeWorkSession(new OwnerId(store.owner())).isPresent()) throw ApiFailure.conflict();
        // Labels must exist before their work-session references are remapped.
        for(JsonNode item:input.get("labels")) {
            var created=service.createLabel(new CreateLabel(item.get("name").asText(),optionalText(item.get("color"))));labels.put(uuid(item.get("id")),created.id());mapping(mappings,"LABEL",item,created.id().value());
        }
        for(JsonNode item:input.path("workSessions")) {
            var created=store.workSessionService().createWorkSession(new CreateWorkSession(new OwnerId(store.owner()),Instant.parse(item.get("start").asText()),
                    optionalText(item.get("end")).map(Instant::parse),item.get("summary").asText()));
            details("WORK_SESSION",created.id().value(),item,clients);
            if(item.has("labelIds")) {
                var fields=object();var assigned=fields.putArray("labelIds");item.get("labelIds").forEach(label->assigned.add(required(labels,uuid(label)).value().toString()));
                store.clientDetails("WORK_SESSION",created.id().value(),fields);
            }
            sessions.put(uuid(item.get("id")),created.id());mapping(mappings,"WORK_SESSION",item,created.id().value());
        }
        for(JsonNode item:input.get("projects")) {
            var created=service.createProject(new CreateProject(item.get("name").asText()));details("PROJECT",created.id().value(),item,clients);projects.put(uuid(item.get("id")),created.id());mapping(mappings,"PROJECT",item,created.id().value());
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
            TaskId task=optionalText(item.get("taskId")).map(UUID::fromString).map(id -> required(tasks,id)).orElse(null);Instant start=Instant.parse(item.get("start").asText());var end=optionalText(item.get("end")).map(Instant::parse);
            // Preserve the transfer's manual flag while reusing domain validation and owned persistence.
            if(end.isEmpty() && store.active().isPresent()) throw ApiFailure.conflict();
            Activity created=new Activity(new ActivityId(UUID.randomUUID()),task,new OwnerId(store.owner()),start,end,item.get("summary").asText(),assigned,item.get("manual").asBoolean(),0,Optional.empty(),optionalText(item.get("projectId")).map(UUID::fromString).map(id -> required(projects,id)));
            store.save("ACTIVITY",created);store.change("ACTIVITY","CREATED",created.id().value().toString());mapping(mappings,"ACTIVITY",item,created.id().value());
        }
        service.rolloverActivities(new OwnerId(store.owner()));
        result.put("revision",store.revision());return new ApiReplies(201,result,null,null);
    }
    private void details(String type,UUID id,JsonNode item,Map<UUID,UUID> clients) {
        if(!item.has("clientId")) return;
        var fields=object();fields.put("clientId",required(clients,uuid(item.get("clientId"))).toString());
        if(item.has("title")) fields.put("title",item.get("title").asText());store.clientDetails(type,id,fields);
    }
    private static <T> T required(Map<UUID,T> values,UUID id) {T value=values.get(id);if(value==null) throw ApiFailure.validation();return value;}
    private static void mapping(com.fasterxml.jackson.databind.node.ArrayNode mappings,String type,JsonNode source,UUID id) {
        var item=object();item.put("entityType",type);item.put("sourceId",uuid(source.get("id")).toString());item.put("id",id.toString());mappings.add(item);
    }
}
