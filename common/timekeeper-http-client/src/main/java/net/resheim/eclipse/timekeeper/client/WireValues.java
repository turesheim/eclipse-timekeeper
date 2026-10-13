package net.resheim.eclipse.timekeeper.client;

import java.time.Instant;
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
        return new Task(new TaskId(id(value.get("id"))),text(value.get("projectId")).map(UUID::fromString).map(ProjectId::new),text(value.get("parentTaskId")).map(UUID::fromString).map(TaskId::new),value.get("summary").getAsString(),text(value.get("url")),refs,value.get("version").getAsLong());
    }
    public static Label label(JsonObject value) {return new Label(new LabelId(id(value.get("id"))),value.get("name").getAsString(),text(value.get("color")).map(WireValues::rgb),value.get("version").getAsLong());}
    public static Activity activity(JsonObject value) {
        Set<LabelId> ids=StreamSupport.stream(value.getAsJsonArray("labelIds").spliterator(),false).map(WireValues::id).map(LabelId::new).collect(Collectors.toUnmodifiableSet());
        return new Activity(new ActivityId(id(value.get("id"))),new TaskId(id(value.get("taskId"))),new OwnerId(value.get("ownerId").getAsString()),Instant.parse(value.get("start").getAsString()),text(value.get("end")).map(Instant::parse),value.get("summary").getAsString(),ids,value.get("manual").getAsBoolean(),value.get("version").getAsLong(),text(value.get("workSessionId")).map(UUID::fromString).map(WorkSessionId::new));
    }
    public static ExternalTaskReference reference(JsonObject value) {return new ExternalTaskReference(value.get("providerId").getAsString(),value.get("repositoryId").getAsString(),value.get("externalId").getAsString(),text(value.get("externalUrl")));}
    public static JsonObject reference(ExternalTaskReference value) {var out=object();out.addProperty("providerId",value.providerId());out.addProperty("repositoryId",value.repositoryId());out.addProperty("externalId",value.externalId());optional(out,"externalUrl",value.externalUrl());return out;}
    public static String hex(String value) {
        if(value.matches("#[0-9a-fA-F]{6}")) return value;
        String[] parts=value.split(",");if(parts.length!=3) throw new IllegalArgumentException("Use an RGB label color");
        int[] colors=java.util.Arrays.stream(parts).map(String::trim).mapToInt(Integer::parseInt).toArray();for(int color:colors) if(color<0 || color>255) throw new IllegalArgumentException("Use an RGB label color");
        return String.format(java.util.Locale.ROOT,"#%02X%02X%02X",colors[0],colors[1],colors[2]);
    }
    public static String rgb(String value) {if(!value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid server label color");return Integer.parseInt(value.substring(1,3),16)+","+Integer.parseInt(value.substring(3,5),16)+","+Integer.parseInt(value.substring(5,7),16);}
}
