package net.resheim.timekeeper.server;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion.VersionFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedMap;

/** Validate inputs against the published JSON schemas, with format assertions. */
@ApplicationScoped
public class ContractInputs {
    private final JsonNode contract;
    private final Map<String,JsonSchema> schemas=new ConcurrentHashMap<>();
    public ContractInputs() {
        try (var input=getClass().getResourceAsStream("/META-INF/resources/api/v1/openapi.json")) { contract=JsonValues.MAPPER.readTree(input); }
        catch(Exception e) { throw new IllegalStateException("Missing API contract",e); }
    }
    public JsonNode contract() { return contract; }
    public JsonNode validate(String name,JsonNode input) {
        if (input==null || !input.isObject()) throw ApiFailure.badRequest();
        JsonSchema schema=schemas.computeIfAbsent(name,key -> {
            ObjectNode root=JsonValues.object(); root.set("$defs",contract.get("components").get("schemas")); root.put("$ref","#/$defs/"+key);
            root=(ObjectNode)JsonValues.parse(root.toString().replace("#/components/schemas/","#/$defs/"));
            return JsonSchemaFactory.getInstance(VersionFlag.V202012).getSchema(root,SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        });
        var errors=schema.validate(input);
        if (!errors.isEmpty()) {
            if (errors.stream().anyMatch(error -> Set.of("required","additionalProperties").contains(error.getType()))) throw ApiFailure.badRequest();
            throw ApiFailure.validation();
        }
        return input;
    }
    public static UUID pathId(String id) {
        if (id==null || !id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw ApiFailure.badRequest();
        return UUID.fromString(id);
    }
    public static void queries(MultivaluedMap<String,String> query,String... allowed) {
        Set<String> names=Set.of(allowed);
        query.forEach((key,values) -> { if (!names.contains(key) || values.size()!=1) throw ApiFailure.badRequest(); });
    }
    public static int number(String value,int fallback,int minimum,int maximum) {
        if (value==null) return fallback;
        try { int number=Integer.parseInt(value); if (!value.matches("[0-9]+") || number<minimum || number>maximum) throw ApiFailure.validation(); return number; }
        catch(NumberFormatException e) { throw ApiFailure.badRequest(); }
    }
    public static long sequence(String value) {
        try { long number=Long.parseLong(value); if (!value.matches("[0-9]+") || number<0 || number>9007199254740991L) throw ApiFailure.validation(); return number; }
        catch(NumberFormatException | NullPointerException e) { throw ApiFailure.badRequest(); }
    }
    public static Instant instant(String value) {
        if (value==null || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z")) throw ApiFailure.badRequest();
        try { return Instant.parse(value); } catch(Exception e) { throw ApiFailure.badRequest(); }
    }
    public static ZoneId zone(String value) { try { if(!ZoneId.getAvailableZoneIds().contains(value)) throw ApiFailure.validation(); return ZoneId.of(value); } catch(Exception e) { throw ApiFailure.validation(); } }
    public static String type(String plural) { return switch(plural) { case "clients"->"CLIENT"; case "projects"->"PROJECT"; case "tasks"->"TASK"; case "task-connections"->"TASK_CONNECTION"; case "task-lists"->"TASK_LIST"; case "labels"->"LABEL"; case "activities"->"ACTIVITY"; case "work-sessions"->"WORK_SESSION"; default->throw ApiFailure.notFound(); }; }
    public static String input(String type,boolean create) { return switch(type) { case "CLIENT"->"ClientInput"; case "PROJECT"->"ProjectInput"; case "TASK"->create ? "TaskCreateInput" : "TaskInput"; case "TASK_CONNECTION"->create ? "TaskConnectionCreateInput" : "TaskConnectionUpdateInput"; case "TASK_LIST"->create ? "TaskListCreateInput" : "TaskListUpdateInput"; case "LABEL"->"LabelInput"; case "ACTIVITY"->create ? "ActivityInput" : "ActivityUpdate"; case "WORK_SESSION"->"WorkSessionInput"; default->throw ApiFailure.notFound(); }; }
}
