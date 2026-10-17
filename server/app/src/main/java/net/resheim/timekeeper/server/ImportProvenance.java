package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import net.resheim.eclipse.timekeeper.domain.Activity;

/** Entry claims and daily-part lineage share the business write's owner transaction. */
final class ImportProvenance {
    private final ServerStore store;
    private final ImportLedger ledger;
    ImportProvenance(ServerStore store) {this.store=store;this.ledger=new ImportLedger(store);}
    private static void digest(String value) {if(value==null || !value.matches("[0-9a-f]{64}")) throw ApiFailure.validation();}
    private static UUID originId(UUID activity) {return ImportLedger.identifier("activity-origin",activity.toString());}
    static String baseline(String owner,Activity value) {return hash(canonical(snapshot(owner,value)).toString());}
    private static ObjectNode part(String owner,Activity activity,boolean eligible) {
        var value=object();value.put("id",activity.id().value().toString());
        value.put("baselineVersion",eligible ? activity.version() : -1);value.put("baselineHash",eligible ? baseline(owner,activity) : "");
        if(eligible) value.set("baseline",snapshot(owner,activity));return value;
    }
    Optional<ObjectNode> active(ObjectNode job,String fingerprint,int occurrence) {
        digest(fingerprint);if(occurrence<0 || occurrence>100000) throw ApiFailure.validation();
        String authority=hash(job.path("sourceKey").asText()+"\n"+fingerprint+"\n"+occurrence);
        UUID key=ImportLedger.identifier("entry-key",authority);
        var found=ledger.find(ImportLedger.Kind.IMPORT_KEY,key);
        if(found.isPresent() && !authority.equals(found.get().path("authority").asText())) throw ApiFailure.conflict();
        return found;
    }
    ObjectNode record(ObjectNode job,String fingerprint,String contentFingerprint,int occurrence,List<Activity> activities,boolean created) {
        digest(fingerprint);digest(contentFingerprint);
        if(activities.isEmpty() || activities.size()>1000 || active(job,fingerprint,occurrence).isPresent()) throw ApiFailure.conflict();
        if(activities.stream().anyMatch(value->!value.ownerId().value().equals(store.owner()) || value.end().isEmpty())) throw ApiFailure.validation();
        if(activities.stream().map(Activity::id).distinct().count()!=activities.size()) throw ApiFailure.validation();
        for(var activity:activities) {
            var current=store.find("ACTIVITY",activity.id().value()).orElseThrow(ApiFailure::notFound);
            if(!hash(canonical(current).toString()).equals(baseline(store.owner(),activity))) throw new ApiFailure(412,"STALE_VERSION");
        }
        var item=object();item.put("kind","ENTRY");item.put("created",created);item.put("fingerprint",fingerprint);
        item.put("contentFingerprint",contentFingerprint);item.put("occurrence",occurrence);item.put("state","PENDING");
        var parts=item.putArray("parts");activities.forEach(value->parts.add(part(store.owner(),value,true)));
        var saved=ledger.appendItem(job,item);
        String authority=hash(job.path("sourceKey").asText()+"\n"+fingerprint+"\n"+occurrence);
        UUID keyId=ImportLedger.identifier("entry-key",authority);
        var key=object();key.put("authority",authority);key.put("jobId",job.path("id").asText());key.put("itemId",saved.path("id").asText());
        key.put("contentFingerprint",contentFingerprint);ledger.insert(ImportLedger.Kind.IMPORT_KEY,keyId,key);
        for(var activity:activities) {
            if(created) origin(job,saved,activity,true);
            new ImportReferences(store).add("ACTIVITY",activity.id().value(),uuid(job.get("id")),uuid(saved.get("id")),created);
        }
        return saved;
    }
    private void origin(ObjectNode job,ObjectNode item,Activity activity,boolean eligible) {
        UUID id=originId(activity.id().value());var existing=ledger.find(ImportLedger.Kind.IMPORT_ORIGIN,id);
        if(existing.isPresent() && (!activity.id().value().toString().equals(existing.get().path("activityId").asText())
                || !item.path("id").asText().equals(existing.get().path("itemId").asText()))) throw ApiFailure.conflict();
        var value=existing.orElseGet(JsonValues::object);value.put("activityId",activity.id().value().toString());
        value.put("jobId",job.path("id").asText());value.put("itemId",item.path("id").asText());
        if(eligible || existing.isEmpty()) {
            value.put("baselineVersion",eligible ? activity.version() : -1);value.put("baselineHash",eligible ? baseline(store.owner(),activity) : "");
        }
        if(existing.isEmpty()) ledger.insert(ImportLedger.Kind.IMPORT_ORIGIN,id,value);else ledger.update(ImportLedger.Kind.IMPORT_ORIGIN,value);
    }
    static void dailyPart(ServerStore store,Activity prior,Activity saved,boolean unchangedWork) {
        if(prior==null) return;
        var provenance=new ImportProvenance(store);var ledger=provenance.ledger;
        var origin=ledger.find(ImportLedger.Kind.IMPORT_ORIGIN,originId(prior.id().value()));if(origin.isEmpty()) return;
        if(!prior.id().value().toString().equals(origin.get().path("activityId").asText())) throw ApiFailure.conflict();
        var item=ledger.required(ImportLedger.Kind.IMPORT_ITEM,uuid(origin.get().get("itemId")));
        var job=ledger.required(ImportLedger.Kind.IMPORT_JOB,uuid(origin.get().get("jobId")));
        boolean eligible=unchangedWork && prior.version()==origin.get().path("baselineVersion").asLong(-1)
                && baseline(store.owner(),prior).equals(origin.get().path("baselineHash").asText());
        var parts=(ArrayNode)item.get("parts");ObjectNode previous=null;
        for(var candidate:parts) if(candidate.path("id").asText().equals(saved.id().value().toString())) previous=(ObjectNode)candidate;
        if(previous==null) parts.add(part(store.owner(),saved,eligible));
        else if(eligible) {previous.removeAll();previous.setAll(part(store.owner(),saved,true));}
        if(!eligible) item.put("changedSinceImport",true);
        var updated=ledger.update(ImportLedger.Kind.IMPORT_ITEM,item);
        provenance.origin(job,updated,saved,eligible);
        new ImportReferences(store).inherit(prior.id().value(),saved.id().value());
        job.put("updatedAt",store.now().toString());ledger.update(ImportLedger.Kind.IMPORT_JOB,job);
    }
}
