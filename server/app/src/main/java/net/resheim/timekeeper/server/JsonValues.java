package net.resheim.timekeeper.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;

/** Explicit immutable wire snapshots and persistence codecs, without JPA objects. */
public final class JsonValues {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonValues() { }
    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static ArrayNode array() { return MAPPER.createArrayNode(); }
    public static JsonNode parse(String text) {
        try { return MAPPER.readTree(text); } catch (Exception e) { throw new IllegalStateException("Invalid stored JSON", e); }
    }
    public static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = object();
            TreeMap<String, JsonNode> fields = new TreeMap<>(); value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, node) -> sorted.set(key, canonical(node))); return sorted;
        }
        if (value.isArray()) { ArrayNode sorted = array(); value.forEach(node -> sorted.add(canonical(node))); return sorted; }
        return value;
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String etag(JsonNode body) { return '"' + hash(canonical(body).toString()) + '"'; }
    public static UUID uuid(JsonNode value) { return UUID.fromString(value.asText()); }
    public static Optional<String> optionalText(JsonNode value) { return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asText()); }
    private static ObjectNode base(String owner, UUID id, long version) {
        ObjectNode node = object(); node.put("ownerId", owner); node.put("id", id.toString()); node.put("version", version); return node;
    }
    private static void nullable(ObjectNode node, String name, Optional<?> value) {
        if (value.isEmpty()) node.putNull(name); else node.put(name, value.get().toString());
    }
    public static ObjectNode snapshot(String owner, Object value) {
        if (value instanceof TaskConnection connection) {
            var node=base(owner,connection.id(),connection.version());
            node.put("providerId",connection.providerId());node.put("remoteAccountId",connection.remoteAccountId());node.put("name",connection.name());
            node.put("readable",connection.readable());node.put("writable",connection.writable());node.put("enabled",connection.enabled());return node;
        }
        if (value instanceof TaskList list) {
            var node=base(owner,list.id(),list.version());
            node.put("connectionId",list.connectionId().toString());node.put("remoteListId",list.remoteListId());node.put("name",list.name());
            node.put("kind",list.kind().name());node.put("readable",list.readable());node.put("writable",list.writable());
            node.put("shared",list.shared());node.put("owner",list.owner());node.put("present",list.present());node.put("position",list.position());return node;
        }
        if (value instanceof TaskSyncCursor cursor) {
            var node=base(owner,cursor.id(),cursor.version());node.put("connectionId",cursor.connectionId().toString());
            nullable(node,"listId",cursor.listId());node.put("mode",cursor.mode().name());nullable(node,"cursor",cursor.cursor());
            nullable(node,"lastObservedAt",cursor.lastObservedAt());nullable(node,"lastCompleteAt",cursor.lastCompleteAt());return node;
        }
        if (value instanceof Project project) { var node = base(owner, project.id().value(), project.version()); node.put("name", project.name()); return node; }
        if (value instanceof Label label) { var node = base(owner, label.id().value(), label.version()); node.put("name", label.name()); nullable(node,"color",label.color()); return node; }
        if (value instanceof Task task) {
            var node = base(owner,task.id().value(),task.version());
            nullable(node,"projectId",task.projectId().map(ProjectId::value)); nullable(node,"parentTaskId",task.parentTaskId().map(TaskId::value));
            node.put("summary",task.summary()); nullable(node,"url",task.url());
            node.put("status",task.planning().status().name());nullable(node,"scheduledDate",task.planning().scheduledDate());nullable(node,"completedAt",task.planning().completedAt());
            ArrayNode checklist = node.putArray("checklistItems");
            task.checklist().items().forEach(item -> {
                var entry = object();entry.put("id",item.id().toString());entry.put("text",item.text());entry.put("completed",item.completed());
                nullable(entry,"completedAt",item.completedAt());entry.put("position",item.position());checklist.add(entry);
            });
            moment(node,"start",task.timing().start());moment(node,"due",task.timing().due());
            node.put("priority",task.importance().priority().name());node.put("flagged",task.importance().flagged());node.put("urgent",task.importance().urgent());
            node.put("siblingOrder",task.siblingOrder());
            var body=node.putObject("body");body.put("format",task.body().format().name());body.put("content",task.body().content());
            ArrayNode refs = node.putArray("externalReferences");
            task.externalReferences().stream().sorted(Comparator.comparing(ExternalTaskReference::providerId).thenComparing(ExternalTaskReference::repositoryId).thenComparing(ExternalTaskReference::externalId)).forEach(reference -> {
                var ref = object(); ref.put("providerId",reference.providerId()); ref.put("repositoryId",reference.repositoryId()); ref.put("externalId",reference.externalId()); nullable(ref,"externalUrl",reference.externalUrl());
                reference.taskListId().ifPresent(list -> ref.put("taskListId",list.toString()));
                reference.providerData().ifPresent(data -> {
                    var envelope=ref.putObject("providerData");envelope.put("namespace",data.namespace());envelope.put("version",data.version());
                    envelope.set("payload",parse(data.json()));
                });
                var source=ref.putObject("source");nullable(source,"createdAt",reference.source().createdAt());nullable(source,"modifiedAt",reference.source().modifiedAt());
                nullable(source,"remoteEtag",reference.source().remoteEtag());nullable(source,"remoteRevision",reference.source().remoteRevision());refs.add(ref);
            }); return node;
        }
        if (value instanceof WorkSession session) {
            if (!owner.equals(session.ownerId().value())) throw new IllegalStateException("Work session owner mismatch");
            var node=base(owner,session.id().value(),session.version());node.put("start",session.start().toString());
            nullable(node,"end",session.end());node.put("summary",session.summary());node.put("state",session.state().name());return node;
        }
        if (value instanceof Activity activity) {
            if (!owner.equals(activity.ownerId().value())) throw new IllegalStateException("Activity owner mismatch");
            var node = base(owner,activity.id().value(),activity.version()); nullable(node,"taskId",activity.task().map(TaskId::value));
            activity.projectId().ifPresent(project -> node.put("projectId",project.value().toString()));
            node.put("start",activity.start().toString()); nullable(node,"end",activity.end()); node.put("summary",activity.summary()); node.put("manual",activity.manual());
            var labels = node.putArray("labelIds"); activity.labelIds().stream().map(LabelId::value).sorted().forEach(id -> labels.add(id.toString())); return node;
        }
        throw new IllegalArgumentException("Unsupported resource");
    }
    public static Object decode(String type, JsonNode node) {
        UUID id = uuid(node.get("id")); long version = node.get("version").asLong();
        return switch (type) {
            case "TASK_CONNECTION" -> new TaskConnection(id,node.get("providerId").asText(),node.get("remoteAccountId").asText(),node.get("name").asText(),
                    node.get("readable").asBoolean(),node.get("writable").asBoolean(),node.get("enabled").asBoolean(),version);
            case "TASK_LIST" -> new TaskList(id,uuid(node.get("connectionId")),node.get("remoteListId").asText(),node.get("name").asText(),
                    TaskListKind.valueOf(node.get("kind").asText()),node.get("readable").asBoolean(),node.get("writable").asBoolean(),
                    node.get("shared").asBoolean(),node.get("owner").asBoolean(),node.get("present").asBoolean(),node.get("position").asInt(),version);
            case "TASK_SYNC_CURSOR" -> new TaskSyncCursor(id,uuid(node.get("connectionId")),optionalText(node.get("listId")).map(UUID::fromString),
                    TaskSyncMode.valueOf(node.get("mode").asText()),optionalText(node.get("cursor")),
                    optionalText(node.get("lastObservedAt")).map(Instant::parse),optionalText(node.get("lastCompleteAt")).map(Instant::parse),version);
            case "PROJECT" -> new Project(new ProjectId(id), node.get("name").asText(), version);
            case "LABEL" -> new Label(new LabelId(id), node.get("name").asText(), optionalText(node.get("color")), version);
            case "TASK" -> new Task(new TaskId(id), optionalText(node.get("projectId")).map(UUID::fromString).map(ProjectId::new),
                    optionalText(node.get("parentTaskId")).map(UUID::fromString).map(TaskId::new), node.get("summary").asText(), optionalText(node.get("url")), references(node.get("externalReferences")),
                    new TaskPlanning(optionalText(node.get("status")).map(TaskStatus::valueOf).orElse(TaskStatus.NOT_STARTED),optionalText(node.get("scheduledDate")).map(java.time.LocalDate::parse),optionalText(node.get("completedAt")).map(Instant::parse)),
                    checklist(node.get("checklistItems")),timing(node),importance(node),body(node),node.path("siblingOrder").asInt(0),version);
            case "ACTIVITY" -> new Activity(new ActivityId(id), optionalText(node.get("taskId")).map(UUID::fromString).map(TaskId::new).orElse(null), new OwnerId(node.get("ownerId").asText()),
                    Instant.parse(node.get("start").asText()), optionalText(node.get("end")).map(Instant::parse),node.get("summary").asText(), labels(node.get("labelIds")),node.get("manual").asBoolean(),version,Optional.empty(),optionalText(node.get("projectId")).map(UUID::fromString).map(ProjectId::new));
            case "WORK_SESSION" -> new WorkSession(new WorkSessionId(id),new OwnerId(node.get("ownerId").asText()),
                    Instant.parse(node.get("start").asText()),optionalText(node.get("end")).map(Instant::parse),node.get("summary").asText(),version);
            default -> throw new IllegalStateException("Unknown stored entity type");
        };
    }
    public static ExternalTaskReference reference(JsonNode node) {
        return new ExternalTaskReference(node.get("providerId").asText(),node.get("repositoryId").asText(),node.get("externalId").asText(),optionalText(node.get("externalUrl")),source(node.get("source")),optionalText(node.get("taskListId")).map(UUID::fromString),
                Optional.ofNullable(node.get("providerData")).filter(value -> !value.isNull()).map(JsonValues::providerData));
    }
    public static ExternalTaskProviderData providerData(JsonNode value) {
        if(value==null || !value.isObject() || !value.path("payload").isObject()) throw ApiFailure.validation();
        rejectCredentials(value.get("payload"));
        String json=value.get("payload").toString();if(json.length()>65536) throw ApiFailure.validation();
        return new ExternalTaskProviderData(value.get("namespace").asText(),value.get("version").asInt(),json);
    }
    private static void rejectCredentials(JsonNode value) {
        if(value.isObject()) value.fields().forEachRemaining(entry -> {
            String field=entry.getKey().replaceAll("[^A-Za-z]","").toLowerCase(java.util.Locale.ROOT);
            if(Set.of("token","accesstoken","refreshtoken","authtoken","oauthtoken","bearertoken",
                    "clientsecret","privatekey","password","apikey","authorization").contains(field)) throw ApiFailure.validation();
            rejectCredentials(entry.getValue());
        });
        else if(value.isArray()) value.forEach(JsonValues::rejectCredentials);
    }
    public static ExternalTaskSource source(JsonNode value) {
        if(value==null || value.isNull())return ExternalTaskSource.empty();
        return new ExternalTaskSource(optionalText(value.get("createdAt")).map(Instant::parse),
                optionalText(value.get("modifiedAt")).map(Instant::parse),optionalText(value.get("remoteEtag")),optionalText(value.get("remoteRevision")));
    }
    public static Set<ExternalTaskReference> references(JsonNode values) {
        if (values == null) return Set.of();
        return java.util.stream.StreamSupport.stream(values.spliterator(),false).map(JsonValues::reference).collect(Collectors.toUnmodifiableSet());
    }
    public static Set<LabelId> labels(JsonNode values) {
        return java.util.stream.StreamSupport.stream(values.spliterator(),false).map(JsonValues::uuid).map(LabelId::new).collect(Collectors.toUnmodifiableSet());
    }
    public static TaskChecklist checklist(JsonNode values) {
        if (values == null || values.isNull()) return TaskChecklist.empty();
        var items = java.util.stream.StreamSupport.stream(values.spliterator(),false).map(value ->
                new TaskChecklistItem(uuid(value.get("id")),value.get("text").asText(),value.get("completed").asBoolean(),
                        optionalText(value.get("completedAt")).map(Instant::parse),value.get("position").asInt())).toList();
        return new TaskChecklist(items);
    }
    private static void moment(ObjectNode node,String field,Optional<TaskMoment> value) {
        if(value.isEmpty()) {node.putNull(field);return;}
        TaskMoment moment=value.get();var entry=node.putObject(field);
        entry.put("kind",moment.dateOnly()?"DATE":"DATE_TIME");entry.put("date",moment.date().toString());
        if(!moment.dateOnly()) {entry.put("time",moment.time().orElseThrow().toString());entry.put("timeZone",moment.timeZone().orElseThrow());}
    }
    public static Optional<TaskMoment> moment(JsonNode value) {
        if(value==null || value.isNull())return Optional.empty();
        LocalDate date=LocalDate.parse(value.get("date").asText());
        return Optional.of(switch(value.get("kind").asText()) {
            case "DATE" -> TaskMoment.dateOnly(date);
            case "DATE_TIME" -> TaskMoment.zoned(date,LocalTime.parse(value.get("time").asText()),value.get("timeZone").asText());
            default -> throw new DomainValidationException("taskMoment", "invalid date/time kind");
        });
    }
    public static TaskTiming timing(JsonNode value) {
        if(value==null || value.isNull())return TaskTiming.empty();
        return new TaskTiming(moment(value.get("start")),moment(value.get("due")));
    }
    public static TaskImportance importance(JsonNode value) {
        if(value==null || value.isNull())return TaskImportance.ordinary();
        return new TaskImportance(optionalText(value.get("priority")).map(TaskPriority::valueOf).orElse(TaskPriority.UNSPECIFIED),
                value.path("flagged").asBoolean(false),value.path("urgent").asBoolean(false));
    }
    public static TaskBody body(JsonNode value) {
        if(value==null || value.isNull() || !value.hasNonNull("body"))return TaskBody.empty();
        JsonNode body=value.get("body");
        return new TaskBody(TaskBodyFormat.valueOf(body.get("format").asText()),body.get("content").asText());
    }
}
