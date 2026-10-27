package net.resheim.eclipse.timekeeper.client;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
    public static TaskConnection taskConnection(JsonObject value) {
        return new TaskConnection(id(value.get("id")),value.get("providerId").getAsString(),value.get("remoteAccountId").getAsString(),
                value.get("name").getAsString(),value.get("readable").getAsBoolean(),value.get("writable").getAsBoolean(),
                value.get("enabled").getAsBoolean(),capabilities(value.get("capabilities")),value.get("version").getAsLong());
    }
    public static TaskList taskList(JsonObject value) {
        return new TaskList(id(value.get("id")),id(value.get("connectionId")),value.get("remoteListId").getAsString(),
                value.get("name").getAsString(),TaskListKind.valueOf(value.get("kind").getAsString()),value.get("readable").getAsBoolean(),
                value.get("writable").getAsBoolean(),value.get("shared").getAsBoolean(),value.get("owner").getAsBoolean(),
                value.get("present").getAsBoolean(),value.get("position").getAsInt(),sharing(value.get("sharing")),value.get("version").getAsLong());
    }
    public static TaskListSharing sharing(JsonElement value) {
        if(value==null || value.isJsonNull()) return TaskListSharing.empty();
        var node=value.getAsJsonObject();var members=new ArrayList<TaskListParticipant>();
        for(var item:node.getAsJsonArray("participants")) {var participant=item.getAsJsonObject();members.add(new TaskListParticipant(
                participant.get("remotePrincipalId").getAsString(),text(participant.get("displayName")),
                TaskListMemberRole.valueOf(participant.get("role").getAsString())));}
        return new TaskListSharing(text(node.get("remoteOwnerId")),members);
    }
    public static JsonObject sharing(TaskListSharing value) {
        var node=object();value.remoteOwnerId().ifPresent(id -> node.addProperty("remoteOwnerId",id));
        var members=new JsonArray();for(var item:value.participants()) {var member=object();member.addProperty("remotePrincipalId",item.remotePrincipalId());
            item.displayName().ifPresent(name -> member.addProperty("displayName",name));member.addProperty("role",item.role().name());members.add(member);}
        node.add("participants",members);return node;
    }
    public static TaskListSection taskListSection(JsonObject value) {
        return new TaskListSection(id(value.get("id")),id(value.get("taskListId")),value.get("remoteSectionId").getAsString(),
                value.get("name").getAsString(),TaskListSectionKind.valueOf(value.get("kind").getAsString()),
                value.get("position").getAsInt(),value.get("present").getAsBoolean(),value.get("writable").getAsBoolean(),value.get("version").getAsLong());
    }
    public static TaskSyncCursor taskSyncCursor(JsonObject value) {
        return new TaskSyncCursor(id(value.get("id")),id(value.get("connectionId")),text(value.get("listId")).map(UUID::fromString),
                TaskSyncMode.valueOf(value.get("mode").getAsString()),text(value.get("cursor")),
                text(value.get("lastObservedAt")).map(Instant::parse),text(value.get("lastCompleteAt")).map(Instant::parse),
                value.get("version").getAsLong());
    }
    public static Task task(JsonObject value) {
        var refs=StreamSupport.stream(value.getAsJsonArray("externalReferences").spliterator(),false).map(item -> reference(item.getAsJsonObject())).collect(Collectors.toUnmodifiableSet());
        var aliases=value.has("externalAliases") ? StreamSupport.stream(value.getAsJsonArray("externalAliases").spliterator(),false).map(item -> alias(item.getAsJsonObject())).collect(Collectors.toUnmodifiableSet()) : Set.<ExternalTaskReferenceKey>of();
        var planning=new TaskPlanning(value.has("status")?TaskStatus.valueOf(value.get("status").getAsString()):TaskStatus.NOT_STARTED,
                text(value.get("scheduledDate")).map(LocalDate::parse),text(value.get("completedAt")).map(Instant::parse));
        var checklist=checklist(value.get("checklistItems"));
		return new Task(new TaskId(id(value.get("id"))),text(value.get("projectId")).map(UUID::fromString).map(ProjectId::new),text(value.get("parentTaskId")).map(UUID::fromString).map(TaskId::new),value.get("summary").getAsString(),text(value.get("url")),refs,aliases,planning,checklist,timing(value),importance(value),body(value),recurrence(value.get("recurrence")),reminders(value.get("reminders")),metadata(value.get("metadata")),value.has("siblingOrder")?value.get("siblingOrder").getAsInt():0,value.get("version").getAsLong());
    }
    public static TaskBody body(JsonObject value) {
        if(!value.has("body") || value.get("body").isJsonNull())return TaskBody.empty();
        var body=value.getAsJsonObject("body");
        return new TaskBody(TaskBodyFormat.valueOf(body.get("format").getAsString()),body.get("content").getAsString());
    }
    public static void body(JsonObject value,TaskBody body) {
        var entry=object();entry.addProperty("format",body.format().name());entry.addProperty("content",body.content());value.add("body",entry);
    }
    public static TaskRecurrence recurrence(JsonElement value) {
        if(value==null || value.isJsonNull())return TaskRecurrence.empty();
        var node=value.getAsJsonObject();var raw=node.get("series");
        Optional<TaskSeries> series=raw==null || raw.isJsonNull()?Optional.empty():Optional.of(new TaskSeries(
                raw.getAsJsonObject().get("rule").getAsString(),TaskRecurrenceEnd.valueOf(raw.getAsJsonObject().get("end").getAsString()),
                moment(raw.getAsJsonObject().get("until")),text(raw.getAsJsonObject().get("count")).map(Integer::parseInt)));
        return new TaskRecurrence(series,occurrences(node.get("occurrences")));
    }
    public static java.util.List<TaskOccurrence> occurrences(JsonElement value) {
        if(value==null || value.isJsonNull())return java.util.List.of();
        return StreamSupport.stream(value.getAsJsonArray().spliterator(),false).map(item -> {
            var node=item.getAsJsonObject();return new TaskOccurrence(moment(node.get("original")).orElseThrow(),moment(node.get("movedTo")),
                    TaskOccurrenceState.valueOf(node.get("state").getAsString()),text(node.get("completedAt")).map(Instant::parse));
        }).toList();
    }
    public static JsonObject recurrence(TaskRecurrence value) {
        var node=object();if(value.series().isPresent()) {
            var series=value.series().get();var entry=object();entry.addProperty("rule",series.rule());entry.addProperty("end",series.end().name());
            moment(entry,"until",series.until());if(series.count().isPresent())entry.addProperty("count",series.count().get());else entry.add("count",JsonNull.INSTANCE);node.add("series",entry);
        } else node.add("series",JsonNull.INSTANCE);
        node.add("occurrences",occurrences(value.occurrences()));return node;
    }
    public static JsonArray occurrences(java.util.List<TaskOccurrence> values) {
        var items=new JsonArray();values.forEach(item -> {
            var entry=object();moment(entry,"original",Optional.of(item.original()));moment(entry,"movedTo",item.movedTo());
            entry.addProperty("state",item.state().name());optional(entry,"completedAt",item.completedAt());items.add(entry);
        });return items;
    }
    public static JsonArray reminders(java.util.List<TaskReminder> values) {
        var array=new JsonArray();values.forEach(value -> {
            var entry=object();entry.addProperty("id",value.id().toString());
            if(value instanceof TaskReminder.Absolute alert) {entry.addProperty("kind","ABSOLUTE");moment(entry,"at",Optional.of(alert.at()));}
            else if(value instanceof TaskReminder.Relative alert) {entry.addProperty("kind","RELATIVE");entry.addProperty("anchor",alert.anchor().name());entry.addProperty("offsetMinutes",alert.offsetMinutes());}
            else if(value instanceof TaskReminder.Location alert) {entry.addProperty("kind","LOCATION");entry.addProperty("latitude",alert.latitude());entry.addProperty("longitude",alert.longitude());entry.addProperty("radiusMeters",alert.radiusMeters());entry.addProperty("transition",alert.transition().name());}
            else if(value instanceof TaskReminder.Car alert) {entry.addProperty("kind","CAR");entry.addProperty("event",alert.event().name());}
            else if(value instanceof TaskReminder.Message alert) {entry.addProperty("kind","MESSAGE");entry.addProperty("contactKey",alert.contactKey());}
            array.add(entry);
        });return array;
    }
    public static java.util.List<TaskReminder> reminders(JsonElement value) {
        if(value==null || value.isJsonNull())return java.util.List.of();
        return StreamSupport.stream(value.getAsJsonArray().spliterator(),false).<TaskReminder>map(item -> {
            var entry=item.getAsJsonObject();UUID id=id(entry.get("id"));return switch(entry.get("kind").getAsString()) {
                case "ABSOLUTE" -> new TaskReminder.Absolute(id,moment(entry.get("at")).orElseThrow());
                case "RELATIVE" -> new TaskReminder.Relative(id,TaskReminder.Anchor.valueOf(entry.get("anchor").getAsString()),entry.get("offsetMinutes").getAsInt());
                case "LOCATION" -> new TaskReminder.Location(id,entry.get("latitude").getAsDouble(),entry.get("longitude").getAsDouble(),entry.get("radiusMeters").getAsDouble(),TaskReminder.Transition.valueOf(entry.get("transition").getAsString()));
                case "CAR" -> new TaskReminder.Car(id,TaskReminder.CarEvent.valueOf(entry.get("event").getAsString()));
                case "MESSAGE" -> new TaskReminder.Message(id,entry.get("contactKey").getAsString());
                default -> throw new IllegalArgumentException("Unsupported reminder kind");
            };
        }).toList();
    }
    public static TaskCapabilities capabilities(JsonElement value) {
        if(value==null || value.isJsonNull())return TaskCapabilities.unknown();
        var node=value.getAsJsonObject();var fields=new java.util.TreeMap<String,TaskFieldCapability>();
        node.getAsJsonObject("fields").entrySet().forEach(entry -> {
            var item=entry.getValue().getAsJsonObject();fields.put(entry.getKey(),new TaskFieldCapability(item.get("readable").getAsBoolean(),item.get("writable").getAsBoolean(),item.get("deletable").getAsBoolean(),text(item.get("note"))));
        });return new TaskCapabilities(node.get("version").getAsInt(),node.get("providerApiVersion").getAsString(),fields);
    }
    public static JsonObject capabilities(TaskCapabilities value) {
        var node=object();node.addProperty("version",value.version());node.addProperty("providerApiVersion",value.providerApiVersion());var fields=object();
        new java.util.TreeMap<>(value.fields()).forEach((key,entry) -> {
            var field=object();field.addProperty("readable",entry.readable());field.addProperty("writable",entry.writable());field.addProperty("deletable",entry.deletable());optional(field,"note",entry.note());fields.add(key,field);
        });node.add("fields",fields);return node;
    }
    public static TaskMetadata metadata(JsonElement value) {
        if(value==null || value.isJsonNull())return TaskMetadata.empty();
        var node=value.getAsJsonObject();var tags=StreamSupport.stream(node.getAsJsonArray("tags").spliterator(),false).map(JsonElement::getAsString).collect(Collectors.toUnmodifiableSet());
        var categories=StreamSupport.stream(node.getAsJsonArray("categories").spliterator(),false).map(JsonElement::getAsString).collect(Collectors.toUnmodifiableSet());
        var links=StreamSupport.stream(node.getAsJsonArray("links").spliterator(),false).map(item -> {
            var entry=item.getAsJsonObject();return new TaskLink(id(entry.get("id")),TaskLinkKind.valueOf(entry.get("kind").getAsString()),entry.get("url").getAsString(),
                    text(entry.get("title")),text(entry.get("providerId")),text(entry.get("remoteId")));
        }).toList();return new TaskMetadata(tags,categories,links);
    }
    public static JsonObject metadata(TaskMetadata value) {
        var node=object();var tags=new JsonArray();value.tags().stream().sorted().forEach(tags::add);node.add("tags",tags);
        var categories=new JsonArray();value.categories().stream().sorted().forEach(categories::add);node.add("categories",categories);
        var links=new JsonArray();value.links().forEach(link -> {
            var entry=object();entry.addProperty("id",link.id().toString());entry.addProperty("kind",link.kind().name());entry.addProperty("url",link.url());
            optional(entry,"title",link.title());optional(entry,"providerId",link.providerId());optional(entry,"remoteId",link.remoteId());links.add(entry);
        });node.add("links",links);return node;
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
    public static ExternalTaskReference reference(JsonObject value) {return new ExternalTaskReference(value.get("providerId").getAsString(),value.get("repositoryId").getAsString(),value.get("externalId").getAsString(),text(value.get("externalUrl")),source(value.get("source")),text(value.get("taskListId")).map(UUID::fromString),text(value.get("sectionId")).map(UUID::fromString),
            value.has("assignment")&&!value.get("assignment").isJsonNull()?Optional.of(assignment(value.getAsJsonObject("assignment"))):Optional.empty(),
            value.has("providerData")&&!value.get("providerData").isJsonNull()?Optional.of(providerData(value.getAsJsonObject("providerData"))):Optional.empty(),
            value.has("baseline")&&!value.get("baseline").isJsonNull()?Optional.of(baseline(value.getAsJsonObject("baseline"))):Optional.empty());}
    public static JsonObject reference(ExternalTaskReference value) {var out=object();out.addProperty("providerId",value.providerId());out.addProperty("repositoryId",value.repositoryId());out.addProperty("externalId",value.externalId());optional(out,"externalUrl",value.externalUrl());value.taskListId().ifPresent(list -> out.addProperty("taskListId",list.toString()));value.sectionId().ifPresent(section -> out.addProperty("sectionId",section.toString()));value.assignment().ifPresent(data -> out.add("assignment",assignment(data)));if(!value.source().equals(ExternalTaskSource.empty()))out.add("source",source(value.source()));value.providerData().ifPresent(data -> out.add("providerData",providerData(data)));value.baseline().ifPresent(data -> out.add("baseline",baseline(data)));return out;}
    public static TaskAssignment assignment(JsonObject value) {return new TaskAssignment(text(value.get("assigneeId")),text(value.get("assigneeName")),text(value.get("assignedById")),text(value.get("assignedByName")),text(value.get("sourceId")),text(value.get("sourceUrl")),value.get("writable").getAsBoolean());}
    public static JsonObject assignment(TaskAssignment value) {var out=object();value.assigneeId().ifPresent(text -> out.addProperty("assigneeId",text));value.assigneeName().ifPresent(text -> out.addProperty("assigneeName",text));value.assignedById().ifPresent(text -> out.addProperty("assignedById",text));value.assignedByName().ifPresent(text -> out.addProperty("assignedByName",text));value.sourceId().ifPresent(text -> out.addProperty("sourceId",text));value.sourceUrl().ifPresent(text -> out.addProperty("sourceUrl",text));out.addProperty("writable",value.writable());return out;}
    public static ExternalTaskReferenceKey alias(JsonObject value) {return new ExternalTaskReferenceKey(value.get("providerId").getAsString(),value.get("repositoryId").getAsString(),value.get("externalId").getAsString());}
    public static JsonObject alias(ExternalTaskReferenceKey value) {var out=object();out.addProperty("providerId",value.providerId());out.addProperty("repositoryId",value.repositoryId());out.addProperty("externalId",value.externalId());return out;}
    public static ExternalTaskProviderData providerData(JsonObject value) {return new ExternalTaskProviderData(value.get("namespace").getAsString(),value.get("version").getAsInt(),value.getAsJsonObject("payload").toString());}
    public static JsonObject providerData(ExternalTaskProviderData value) {var out=object();out.addProperty("namespace",value.namespace());out.addProperty("version",value.version());out.add("payload",JsonParser.parseString(value.json()).getAsJsonObject());return out;}
    public static ExternalTaskBaseline baseline(JsonObject value) {return new ExternalTaskBaseline(value.get("version").getAsInt(),Instant.parse(value.get("capturedAt").getAsString()),value.getAsJsonObject("fields").toString());}
    public static JsonObject baseline(ExternalTaskBaseline value) {var out=object();out.addProperty("version",value.version());out.addProperty("capturedAt",value.capturedAt().toString());out.add("fields",JsonParser.parseString(value.json()).getAsJsonObject());return out;}
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
