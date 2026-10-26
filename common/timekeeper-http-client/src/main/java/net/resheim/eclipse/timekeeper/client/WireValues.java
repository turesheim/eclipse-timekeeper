package net.resheim.eclipse.timekeeper.client;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import com.google.gson.*;
import net.resheim.eclipse.timekeeper.domain.*;

/** Explicit JSON values keep Optional, JPA and provider objects out of the wire format. */
public final class WireValues {
    private WireValues() { }
    public static JsonObject object() {return new JsonObject();}
    public static void optional(JsonObject value,String key,Optional<?> field) {if(field.isPresent()) value.addProperty(key,field.get().toString());else value.add(key,JsonNull.INSTANCE);}
    public static Optional<String> text(JsonElement value) {return value==null || value.isJsonNull() ? Optional.empty() : Optional.of(value.getAsString());}
    public static UUID id(JsonElement value) {return UUID.fromString(value.getAsString());}
    public static JsonArray labels(Set<LabelId> ids) {var array=new JsonArray();ids.stream().map(LabelId::value).sorted().forEach(id -> array.add(id.toString()));return array;}
    public static Project project(JsonObject value) {return new Project(new ProjectId(id(value.get("id"))),value.get("name").getAsString(),value.get("version").getAsLong());}
    public static Task task(JsonObject value) {
        var refs=StreamSupport.stream(value.getAsJsonArray("externalReferences").spliterator(),false).map(item -> reference(item.getAsJsonObject())).collect(Collectors.toUnmodifiableSet());
        var planning=new TaskPlanning(value.has("status")?TaskStatus.valueOf(value.get("status").getAsString()):TaskStatus.NOT_STARTED,
                text(value.get("scheduledDate")).map(LocalDate::parse),text(value.get("completedAt")).map(Instant::parse));
        var checklist=checklist(value.get("checklistItems"));
		return new Task(new TaskId(id(value.get("id"))),text(value.get("projectId")).map(UUID::fromString).map(ProjectId::new),text(value.get("parentTaskId")).map(UUID::fromString).map(TaskId::new),value.get("summary").getAsString(),text(value.get("url")),refs,planning,checklist,timing(value),importance(value),body(value),value.get("version").getAsLong());
    }
    public static TaskBody body(JsonObject value) {
        if(!value.has("body") || value.get("body").isJsonNull())return TaskBody.empty();
        var body=value.getAsJsonObject("body");
        return new TaskBody(TaskBodyFormat.valueOf(body.get("format").getAsString()),body.get("content").getAsString());
    }
    public static void body(JsonObject value,TaskBody body) {
        var entry=object();entry.addProperty("format",body.format().name());entry.addProperty("content",body.content());value.add("body",entry);
    }
    public static TaskImportance importance(JsonObject value) {
        return new TaskImportance(value.has("priority")?TaskPriority.valueOf(value.get("priority").getAsString()):TaskPriority.UNSPECIFIED,
                value.has("flagged")&&value.get("flagged").getAsBoolean(),value.has("urgent")&&value.get("urgent").getAsBoolean());
    }
    public static void importance(JsonObject body,TaskImportance value) {
        body.addProperty("priority",value.priority().name());body.addProperty("flagged",value.flagged());body.addProperty("urgent",value.urgent());
    }
    public static TaskTiming timing(JsonObject value) {
        return new TaskTiming(moment(value.get("start")),moment(value.get("due")));
    }
    private static Optional<TaskMoment> moment(JsonElement value) {
        if(value==null || value.isJsonNull())return Optional.empty();
        var entry=value.getAsJsonObject();LocalDate date=LocalDate.parse(entry.get("date").getAsString());
        return Optional.of(switch(entry.get("kind").getAsString()) {
            case "DATE" -> TaskMoment.dateOnly(date);
            case "DATE_TIME" -> TaskMoment.zoned(date,LocalTime.parse(entry.get("time").getAsString()),entry.get("timeZone").getAsString());
            default -> throw new IllegalArgumentException("Invalid task date/time kind");
        });
    }
    public static void timing(JsonObject body,TaskTiming value) {
        moment(body,"start",value.start());moment(body,"due",value.due());
    }
    private static void moment(JsonObject body,String field,Optional<TaskMoment> value) {
        if(value.isEmpty()) {body.add(field,JsonNull.INSTANCE);return;}
        TaskMoment moment=value.get();var entry=object();entry.addProperty("kind",moment.dateOnly()?"DATE":"DATE_TIME");entry.addProperty("date",moment.date().toString());
        if(!moment.dateOnly()) {entry.addProperty("time",moment.time().orElseThrow().toString());entry.addProperty("timeZone",moment.timeZone().orElseThrow());}
        body.add(field,entry);
    }
    public static TaskChecklist checklist(JsonElement value) {
        if(value==null || value.isJsonNull())return TaskChecklist.empty();
        var items=StreamSupport.stream(value.getAsJsonArray().spliterator(),false).map(entry -> {
            var item=entry.getAsJsonObject();
            return new TaskChecklistItem(id(item.get("id")),item.get("text").getAsString(),item.get("completed").getAsBoolean(),
                    text(item.get("completedAt")).map(Instant::parse),item.get("position").getAsInt());
        }).toList();
        return new TaskChecklist(items);
    }
    public static JsonArray checklist(TaskChecklist value) {
        var items=new JsonArray();
        value.items().forEach(item -> {var entry=object();entry.addProperty("id",item.id().toString());entry.addProperty("text",item.text());
            entry.addProperty("completed",item.completed());optional(entry,"completedAt",item.completedAt());entry.addProperty("position",item.position());items.add(entry);});
        return items;
    }
    public static Label label(JsonObject value) {return new Label(new LabelId(id(value.get("id"))),value.get("name").getAsString(),text(value.get("color")).map(WireValues::rgb),value.get("version").getAsLong());}
    public static Activity activity(JsonObject value) {
        Set<LabelId> ids=StreamSupport.stream(value.getAsJsonArray("labelIds").spliterator(),false).map(WireValues::id).map(LabelId::new).collect(Collectors.toUnmodifiableSet());
        return new Activity(new ActivityId(id(value.get("id"))),text(value.get("taskId")).map(UUID::fromString).map(TaskId::new).orElse(null),new OwnerId(value.get("ownerId").getAsString()),Instant.parse(value.get("start").getAsString()),text(value.get("end")).map(Instant::parse),value.get("summary").getAsString(),ids,value.get("manual").getAsBoolean(),value.get("version").getAsLong(),text(value.get("workSessionId")).map(UUID::fromString).map(WorkSessionId::new),text(value.get("projectId")).map(UUID::fromString).map(ProjectId::new));
    }
    public static WorkSession workSession(JsonObject value) {
        var session=new WorkSession(new WorkSessionId(id(value.get("id"))),new OwnerId(value.get("ownerId").getAsString()),
                Instant.parse(value.get("start").getAsString()),text(value.get("end")).map(Instant::parse),value.get("summary").getAsString(),value.get("version").getAsLong());
        if(!session.state().name().equals(value.get("state").getAsString())) throw new IllegalArgumentException("Inconsistent work session state");
        return session;
    }
    public static ExternalTaskReference reference(JsonObject value) {return new ExternalTaskReference(value.get("providerId").getAsString(),value.get("repositoryId").getAsString(),value.get("externalId").getAsString(),text(value.get("externalUrl")),source(value.get("source")));}
    public static JsonObject reference(ExternalTaskReference value) {var out=object();out.addProperty("providerId",value.providerId());out.addProperty("repositoryId",value.repositoryId());out.addProperty("externalId",value.externalId());optional(out,"externalUrl",value.externalUrl());if(!value.source().equals(ExternalTaskSource.empty()))out.add("source",source(value.source()));return out;}
    public static ExternalTaskSource source(JsonElement value) {
        if(value==null || value.isJsonNull())return ExternalTaskSource.empty();
        var item=value.getAsJsonObject();return new ExternalTaskSource(text(item.get("createdAt")).map(Instant::parse),
                text(item.get("modifiedAt")).map(Instant::parse),text(item.get("remoteEtag")),text(item.get("remoteRevision")));
    }
    public static JsonObject source(ExternalTaskSource value) {
        var out=object();optional(out,"createdAt",value.createdAt());optional(out,"modifiedAt",value.modifiedAt());
        optional(out,"remoteEtag",value.remoteEtag());optional(out,"remoteRevision",value.remoteRevision());return out;
    }
    public static String hex(String value) {
        if(value.matches("#[0-9a-fA-F]{6}")) return value;
        String[] parts=value.split(",");if(parts.length!=3) throw new IllegalArgumentException("Use an RGB label color");
        int[] colors=java.util.Arrays.stream(parts).map(String::trim).mapToInt(Integer::parseInt).toArray();for(int color:colors) if(color<0 || color>255) throw new IllegalArgumentException("Use an RGB label color");
        return String.format(java.util.Locale.ROOT,"#%02X%02X%02X",colors[0],colors[1],colors[2]);
    }
    public static String rgb(String value) {if(!value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid server label color");return Integer.parseInt(value.substring(1,3),16)+","+Integer.parseInt(value.substring(3,5),16)+","+Integer.parseInt(value.substring(5,7),16);}
}
