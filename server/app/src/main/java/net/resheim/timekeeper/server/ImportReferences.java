package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Logical import references never restrict ordinary record deletion with a SQL foreign key. */
final class ImportReferences {
    private final ServerStore store;
    private final ImportLedger ledger;
    ImportReferences(ServerStore store) {this.store=store;this.ledger=new ImportLedger(store);}
    private static UUID id(String type,UUID record) {
        if(!Set.of("CLIENT","PROJECT","TASK","LABEL","ACTIVITY","WORK_SESSION").contains(type)) throw ApiFailure.validation();
        return ImportLedger.identifier("import-uses",type,record.toString());
    }
    private Optional<ObjectNode> uses(String type,UUID record) {
        var found=ledger.find(ImportLedger.Kind.IMPORT_USE,id(type,record));
        if(found.isPresent() && (!type.equals(found.get().path("recordType").asText()) || !record.toString().equals(found.get().path("recordId").asText()))) throw ApiFailure.conflict();
        return found;
    }
    void add(String type,UUID record,UUID job,UUID item,boolean created) {
        store.find(type,record).orElseThrow(ApiFailure::notFound);
        ledger.required(ImportLedger.Kind.IMPORT_JOB,job);
        if(!ledger.required(ImportLedger.Kind.IMPORT_ITEM,item).path("jobId").asText().equals(job.toString())) throw ApiFailure.notFound();
        var previous=uses(type,record);var value=previous.orElseGet(()->{
            var fresh=object();fresh.put("recordType",type);fresh.put("recordId",record.toString());fresh.putArray("uses");return fresh;
        });
        var entries=(com.fasterxml.jackson.databind.node.ArrayNode)value.get("uses");
        for(var entry:entries) if(entry.path("jobId").asText().equals(job.toString()) && entry.path("itemId").asText().equals(item.toString())) return;
        entries.add(object().put("jobId",job.toString()).put("itemId",item.toString()).put("created",created));
        if(previous.isEmpty()) ledger.insert(ImportLedger.Kind.IMPORT_USE,id(type,record),value);else ledger.update(ImportLedger.Kind.IMPORT_USE,value);
    }
    boolean usedByAnotherImport(String type,UUID record,UUID except) {
        var found=uses(type,record);if(found.isEmpty()) return false;
        for(var entry:found.get().path("uses")) {
            UUID job=uuid(entry.get("jobId"));if(job.equals(except)) continue;
            var current=ledger.find(ImportLedger.Kind.IMPORT_JOB,job);
            if(current.isPresent() && !current.get().path("state").asText().startsWith("UNDONE")) return true;
        }
        return false;
    }
    void inherit(UUID original,UUID part) {
        if(original.equals(part)) return;
        var found=uses("ACTIVITY",original);if(found.isEmpty()) return;
        for(var entry:found.get().path("uses")) add("ACTIVITY",part,uuid(entry.get("jobId")),uuid(entry.get("itemId")),entry.path("created").asBoolean());
    }
}
