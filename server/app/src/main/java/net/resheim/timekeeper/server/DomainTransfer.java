package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Shared owner-scoped graph remapping for domain transfers and reviewed account imports. */
final class DomainTransfer {
    private final ServerStore store;
    DomainTransfer(ServerStore store) {this.store=store;}
    static final Map<String,String> TYPES=Map.of("clients","CLIENT","projects","PROJECT","tasks","TASK","labels","LABEL","activities","ACTIVITY","workSessions","WORK_SESSION",
            "taskConnections","TASK_CONNECTION","taskLists","TASK_LIST");
    ApiReplies importRecords(JsonNode input) {
        Map<UUID,UUID> clients=new HashMap<>();
        Map<UUID,UUID> connections=new HashMap<>();
        Map<UUID,ProjectId> projects=new HashMap<>();Map<UUID,TaskId> tasks=new HashMap<>();Map<UUID,LabelId> labels=new HashMap<>();Map<UUID,WorkSessionId> sessions=new HashMap<>();Set<UUID> sourceIds=new HashSet<>();
        var result=object();var mappings=result.putArray("ids");var service=store.service();
        // Validate source identity uniqueness before any record is created.
        TYPES.forEach((name,type) -> {for(JsonNode item:input.path(name)) if(!sourceIds.add(uuid(item.get("id")))) throw ApiFailure.validation();});
        Set<UUID> sourceConnections=new HashSet<>();input.path("taskConnections").forEach(item->sourceConnections.add(uuid(item.get("id"))));
        for(JsonNode item:input.path("taskLists")) if(!sourceConnections.contains(uuid(item.get("connectionId")))) throw ApiFailure.validation();
        Map<UUID,JsonNode> sourceProjectRecords=new HashMap<>();
        input.get("projects").forEach(item -> sourceProjectRecords.put(uuid(item.get("id")),item));
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
        Set<String> accountKeys=new HashSet<>();
        for(JsonNode item:input.path("taskConnections")) {
            String key=item.get("providerId").asText()+"\u0000"+item.get("remoteAccountId").asText();
            if(!accountKeys.add(key)) throw ApiFailure.validation();
            for(var existing:store.list("TASK_CONNECTION")) if(existing.get("providerId").asText().equals(item.get("providerId").asText())
                    && existing.get("remoteAccountId").asText().equals(item.get("remoteAccountId").asText())) throw ApiFailure.conflict();
            var created=new TaskConnection(UUID.randomUUID(),item.get("providerId").asText(),item.get("remoteAccountId").asText(),
                    item.get("name").asText(),item.get("readable").asBoolean(),item.get("writable").asBoolean(),item.get("enabled").asBoolean(),0);
            store.save("TASK_CONNECTION",created);store.change("TASK_CONNECTION","CREATED",created.id().toString());
            connections.put(uuid(item.get("id")),created.id());mapping(mappings,"TASK_CONNECTION",item,created.id());
        }
        Set<String> listKeys=new HashSet<>();
        for(JsonNode item:input.path("taskLists")) {
            UUID sourceConnection=uuid(item.get("connectionId"));String key=sourceConnection+"\u0000"+item.get("remoteListId").asText();
            if(!listKeys.add(key)) throw ApiFailure.validation();
            var created=new TaskList(UUID.randomUUID(),required(connections,sourceConnection),item.get("remoteListId").asText(),
                    item.get("name").asText(),TaskListKind.valueOf(item.get("kind").asText()),item.get("readable").asBoolean(),
                    item.get("writable").asBoolean(),item.get("shared").asBoolean(),item.get("owner").asBoolean(),
                    item.get("present").asBoolean(),item.get("position").asInt(),0);
            store.save("TASK_LIST",created);store.change("TASK_LIST","CREATED",created.id().toString());
            mapping(mappings,"TASK_LIST",item,created.id());
        }
        int openSessions=0;
        for(JsonNode item:input.path("workSessions")) {
            Instant start=Instant.parse(item.get("start").asText());WorkSession.validateStart(start,store.now());
            var end=optionalText(item.get("end")).map(Instant::parse);
            if(end.filter(value -> value.isBefore(start)).isPresent()) throw ApiFailure.validation();
            if(end.isEmpty()) openSessions++;
            for(var label:item.path("labelIds")) if(!sourceLabels.contains(uuid(label))) throw ApiFailure.validation();
            if(item.hasNonNull("projectId")) {
                var project=sourceProjectRecords.get(uuid(item.get("projectId")));
                if(project==null || !project.path("clientId").equals(item.path("clientId"))) throw ApiFailure.validation();
            }
        }
        if(openSessions>1) throw ApiFailure.validation();
        if(openSessions==1 && store.workSessionService().activeWorkSession(new OwnerId(store.owner())).isPresent()) throw ApiFailure.conflict();
        // Labels must exist before their work-session references are remapped.
        for(JsonNode item:input.get("labels")) {
            var created=service.createLabel(new CreateLabel(item.get("name").asText(),optionalText(item.get("color"))));labels.put(uuid(item.get("id")),created.id());mapping(mappings,"LABEL",item,created.id().value());
        }
        for(JsonNode item:input.get("projects")) {
            var created=service.createProject(new CreateProject(item.get("name").asText()));details("PROJECT",created.id().value(),item,clients);projects.put(uuid(item.get("id")),created.id());mapping(mappings,"PROJECT",item,created.id().value());
        }
        for(JsonNode item:input.path("workSessions")) {
            var created=store.workSessionService().createWorkSession(new CreateWorkSession(new OwnerId(store.owner()),Instant.parse(item.get("start").asText()),
                    optionalText(item.get("end")).map(Instant::parse),WorkSessionDescriptions.summary(item)));
            details("WORK_SESSION",created.id().value(),item,clients);
            var fields=object();
            if(item.has("labelIds")) {var assigned=fields.putArray("labelIds");item.get("labelIds").forEach(label->assigned.add(required(labels,uuid(label)).value().toString()));}
            if(item.has("projectId")) {
                if(item.hasNonNull("projectId")) fields.put("projectId",required(projects,uuid(item.get("projectId"))).value().toString());
                else fields.putNull("projectId");
            }
            store.clientDetails("WORK_SESSION",created.id().value(),fields);
            sessions.put(uuid(item.get("id")),created.id());mapping(mappings,"WORK_SESSION",item,created.id().value());
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
                var created=service.createTask(new CreateTask(project,parentId,item.get("summary").asText(),optionalText(item.get("url")),references(item.get("externalReferences")),
                        new TaskPlanning(optionalText(item.get("status")).map(TaskStatus::valueOf).orElse(TaskStatus.NOT_STARTED),
                                optionalText(item.get("scheduledDate")).map(java.time.LocalDate::parse),optionalText(item.get("completedAt")).map(java.time.Instant::parse)),
                        checklist(item.get("checklistItems")),timing(item),importance(item),body(item)));
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
        var fields=object();
        if(item.has("clientId")) fields.put("clientId",required(clients,uuid(item.get("clientId"))).toString());
        if(type.equals("PROJECT") && item.has("icon")) fields.set("icon",item.get("icon").deepCopy());
        if(!fields.isEmpty()) store.clientDetails(type,id,fields);
    }
    private static <T> T required(Map<UUID,T> values,UUID id) {T value=values.get(id);if(value==null) throw ApiFailure.validation();return value;}
    private static void mapping(com.fasterxml.jackson.databind.node.ArrayNode mappings,String type,JsonNode source,UUID id) {
        var item=object();item.put("entityType",type);item.put("sourceId",uuid(source.get("id")).toString());item.put("id",id.toString());mappings.add(item);
    }
}
