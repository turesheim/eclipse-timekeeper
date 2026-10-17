package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Reviewed, immutable catalog choices; never merges by name. */
final class ImportCatalog {
    private static final Set<String> TYPES=Set.of("CLIENT","PROJECT","TASK","LABEL");
    private final ServerStore store;
    private final ImportLedger ledger;
    ImportCatalog(ServerStore store) {this.store=store;this.ledger=new ImportLedger(store);}
    private static UUID id(UUID job,String key) {return ImportLedger.identifier("catalog-map",job.toString(),key);}
    private static void key(String value) {if(value==null || !value.matches("[0-9a-f]{64}")) throw ApiFailure.validation();}
    void register(ObjectNode job,JsonNode definitions) {
        if(!definitions.isArray() || definitions.size()>1000) throw ApiFailure.validation();
        if(!hash(canonical(definitions).toString()).equals(job.path("planFingerprint").asText())) throw ApiFailure.validation();
        var keys=new HashMap<String,JsonNode>();
        for(var definition:definitions) {
            String key=definition.path("key").asText();key(key);
            if(keys.put(key,definition)!=null || !TYPES.contains(definition.path("resourceType").asText())
                    || !Set.of("CREATE","REUSE").contains(definition.path("action").asText())) throw ApiFailure.validation();
            if(definition.path("action").asText().equals("CREATE")) {
                String name=definition.path("name").asText();if(name.isBlank() || name.length()>1000) throw ApiFailure.validation();
                if(definition.path("resourceType").asText().equals("CLIENT") && !definition.path("color").asText().matches("#[0-9a-fA-F]{6}")) throw ApiFailure.validation();
            } else {
                ContractInputs.pathId(definition.path("id").asText());
                if(!definition.path("etag").isTextual()) throw ApiFailure.validation();
            }
        }
        for(var definition:definitions) {
            String type=definition.path("resourceType").asText(), parent=definition.path("parentKey").asText("");
            if(type.equals("PROJECT") || type.equals("TASK")) {
                key(parent);var target=keys.get(parent);
                if(target==null || !target.path("resourceType").asText().equals(type.equals("PROJECT") ? "CLIENT" : "PROJECT")) throw ApiFailure.validation();
            } else if(!parent.isBlank()) throw ApiFailure.validation();
        }
        for(var definition:definitions) {
            var value=object();value.put("jobId",job.path("id").asText());value.put("key",definition.path("key").asText());
            value.set("definition",definition.deepCopy());
            ledger.insert(ImportLedger.Kind.IMPORT_MAP,id(uuid(job.get("id")),definition.path("key").asText()),value);
        }
        job.put("mappingCount",definitions.size());
    }
    ObjectNode definition(UUID job,String key) {
        key(key);var value=ledger.required(ImportLedger.Kind.IMPORT_MAP,id(job,key));
        if(!job.toString().equals(value.path("jobId").asText()) || !key.equals(value.path("key").asText())) throw ApiFailure.conflict();
        return value;
    }
    JsonNode resolve(ObjectNode job,String key,String expectedType) {
        if(!TYPES.contains(expectedType)) throw ApiFailure.validation();
        var mapping=definition(uuid(job.get("id")),key);var definition=mapping.get("definition");
        if(!expectedType.equals(definition.path("resourceType").asText())) throw ApiFailure.validation();
        JsonNode parent=null;
        if(expectedType.equals("PROJECT") || expectedType.equals("TASK"))
            parent=resolve(job,definition.path("parentKey").asText(),expectedType.equals("PROJECT") ? "CLIENT" : "PROJECT");
        if(mapping.hasNonNull("targetId")) {
            var current=store.find(expectedType,uuid(mapping.get("targetId"))).orElseThrow(ApiFailure::notFound);
            ApiReplies.match(current,mapping.path("currentTag").asText());hierarchy(expectedType,current,parent);return current;
        }
        boolean created=definition.path("action").asText().equals("CREATE");JsonNode value;
        if(created) value=create(expectedType,definition,parent);
        else {
            value=store.find(expectedType,ContractInputs.pathId(definition.path("id").asText())).orElseThrow(ApiFailure::notFound);
            ApiReplies.match(value,definition.path("etag").asText());hierarchy(expectedType,value,parent);
        }
        var item=object();item.put("kind","CATALOG");item.put("created",created);item.put("state","PENDING");
        item.put("recordType",expectedType);item.put("recordId",value.path("id").asText());item.put("baselineVersion",value.path("version").asLong());
        item.put("baselineTag",etag(value));item.set("baseline",value.deepCopy());
        var saved=ledger.appendItem(job,item);
        new ImportReferences(store).add(expectedType,uuid(value.get("id")),uuid(job.get("id")),uuid(saved.get("id")),created);
        mapping.put("targetId",value.path("id").asText());mapping.put("currentTag",etag(value));mapping.put("itemId",saved.path("id").asText());
        ledger.update(ImportLedger.Kind.IMPORT_MAP,mapping);job.put("catalogCount",job.path("catalogCount").asInt()+1);return value;
    }
    private static void hierarchy(String type,JsonNode value,JsonNode parent) {
        if(parent==null) return;
        String field=type.equals("PROJECT") ? "clientId" : "projectId";
        if(!parent.path("id").asText().equals(value.path(field).asText())) throw ApiFailure.validation();
    }
    private JsonNode create(String type,JsonNode definition,JsonNode parent) {
        String name=definition.path("name").asText();UUID id;
        switch(type) {
            case "CLIENT": return store.createClient(object().put("name",name).put("color",definition.path("color").asText()));
            case "PROJECT":
                id=store.withCreationClient(uuid(parent.get("id")),()->store.service().createProject(new CreateProject(name))).id().value();break;
            case "TASK":
                id=store.service().createTask(new CreateTask(Optional.of(new ProjectId(uuid(parent.get("id")))),Optional.empty(),name,Optional.empty(),Set.of())).id().value();break;
            case "LABEL":
                id=store.service().createLabel(new CreateLabel(name,optionalText(definition.get("color")))).id().value();break;
            default: throw ApiFailure.validation();
        }
        return store.find(type,id).orElseThrow(ApiFailure::notFound);
    }
}
