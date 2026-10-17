package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(ImportUndoTest.Profile.class)
class ImportUndoTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:import_undo_tests");}
    }
    @Inject ServerStore store;
    private ObjectNode job(ImportLedger ledger) {return ledger.create("History",hash("source"),"Europe/Oslo",hash("file"),hash("plan"));}
    private List<Activity> entry(ObjectNode job,int number,Instant start,Instant end) {
        var retained=store.service().createActivity(new CreateActivity(null,new OwnerId(store.owner()),start,Optional.of(end),"Imported "+number,Set.of()));
        var intervals=ActivityDailySegments.split(start,Optional.of(end),ContractInputs.zone(store.profile().path("zoneId").asText()),store.now());
        var parts=new ArrayList<Activity>();
        for(int index=0;index<intervals.size();index++) {
            var id=index==intervals.size()-1 ? retained.id() : ActivityDailySegments.identifier(retained.id(),intervals.get(index).start());
            parts.add(store.service().activity(id).orElseThrow());
        }
        new ImportProvenance(store).record(job,hash("entry"+number),hash("content"+number),0,parts,true);return parts;
    }
    private ObjectNode request(ObjectNode preview) {return object().put("reviewToken",preview.path("reviewToken").asText()).set("choices",object());}
    private ObjectNode finish(String owner,UUID job) {
        ObjectNode outcome=null;
        for(int step=0;step<1000;step++) {
            var next=store.transaction(owner,()->new ImportUndo(store).preview(job));
            int index=store.transaction(owner,()->new ImportLedger(store).required(ImportLedger.Kind.IMPORT_JOB,job).path("undoNextBatch").asInt());
            outcome=store.transaction(owner,()->new ImportUndo(store).apply(job,index,request(next)));
            if(outcome.path("state").asText().startsWith("UNDONE")) return outcome;
        }
        fail("Undo did not finish");return outcome;
    }
    @Test void allDailyPartsAndNewCatalogAreRemovedInDependencyOrderAndRepliesSurviveRestart() {
        String owner="undo-catalog-"+UUID.randomUUID();var ledger=new ImportLedger(store);var catalog=new ImportCatalog(store);
        var client=object().put("key",hash("client")).put("resourceType","CLIENT").put("action","CREATE").put("name","Imported client").put("color","#123456");
        var project=object().put("key",hash("project")).put("resourceType","PROJECT").put("action","CREATE").put("name","Imported project").put("parentKey",hash("client"));
        var plan=array().add(client).add(project);
        UUID job=uuid(store.transaction(owner,()->{
            store.updateProfile("Europe/Oslo");var value=ledger.create("History",hash("source"),"Europe/Oslo",hash("file"),hash(canonical(plan).toString()));
            catalog.register(value,plan);return ledger.update(ImportLedger.Kind.IMPORT_JOB,value);
        }).get("id"));
        store.transaction(owner,()->ledger.batch(job,0,object(),true,state->{
            var target=catalog.resolve(state,hash("project"),"PROJECT");
            var start=Instant.parse("2025-10-25T20:00:00Z");var end=Instant.parse("2025-10-26T03:00:00Z");
            var retained=store.service().createActivity(new CreateActivity(null,new OwnerId(owner),start,Optional.of(end),"DST interval",Set.of(),Optional.empty(),Optional.of(new ProjectId(uuid(target.get("id"))))));
            var first=store.service().activity(ActivityDailySegments.identifier(retained.id(),start)).orElseThrow();
            new ImportProvenance(store).record(state,hash("entry"),hash("content"),0,List.of(first,retained),true);return object();
        }));
        store.transaction(owner,()->new ImportUndo(store).start(job,etag(ledger.required(ImportLedger.Kind.IMPORT_JOB,job))));
        var preview=store.transaction(owner,()->new ImportUndo(store).preview(job));var request=request(preview);
        var receipt=store.transaction(owner,()->new ImportUndo(store).apply(job,0,request));
        assertEquals(2,receipt.path("removed").asInt());
        assertEquals(receipt.toString(),store.transaction(owner,()->new ImportUndo(store).apply(job,0,parse(request.toString()))).toString());
        assertEquals("UNDONE",finish(owner,job).path("state").asText());
        store.transaction(owner,()->{
            assertTrue(store.service().projects().isEmpty());assertTrue(store.list("CLIENT").isEmpty());assertTrue(store.list("ACTIVITY").isEmpty());
            assertTrue(new ImportProvenance(store).active(ledger.required(ImportLedger.Kind.IMPORT_JOB,job),hash("entry"),0).isEmpty());
            var replacement=job(ledger);entry(replacement,0,Instant.parse("2025-10-20T08:00:00Z"),Instant.parse("2025-10-20T09:00:00Z"));return null;
        });
        assertEquals(receipt.toString(),store.transaction(owner,()->new ImportUndo(store).apply(job,0,request)).toString());
    }
    @Test void reviewedEditsMustStillMatchAndRunningOrReusedRecordsCannotBeDeleted() {
        String owner="undo-edited-"+UUID.randomUUID();var ledger=new ImportLedger(store);UUID job=uuid(store.transaction(owner,()->job(ledger)).get("id"));
        var ids=new ArrayList<ActivityId>();
        store.transaction(owner,()->ledger.batch(job,0,object(),true,state->{
            ids.add(entry(state,0,Instant.parse("2025-10-20T08:00:00Z"),Instant.parse("2025-10-20T09:00:00Z")).get(0).id());
            ids.add(entry(state,1,Instant.parse("2025-10-20T10:00:00Z"),Instant.parse("2025-10-20T11:00:00Z")).get(0).id());return object();
        }));
        store.transaction(owner,()->{
            var first=store.service().activity(ids.get(0)).orElseThrow();
            store.service().updateActivity(new UpdateActivity(first.id(),first.version(),first.start(),first.end(),"User edit",first.labelIds()));
            return new ImportUndo(store).start(job,etag(ledger.required(ImportLedger.Kind.IMPORT_JOB,job)));
        });
        var stale=store.transaction(owner,()->new ImportUndo(store).preview(job));assertEquals("REVIEW",stale.path("targets").get(0).path("status").asText());
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction(owner,()->new ImportUndo(store).apply(job,0,request(stale)))).status);
        store.transaction(owner,()->{var second=store.service().activity(ids.get(1)).orElseThrow();return store.service().updateActivity(new UpdateActivity(second.id(),second.version(),store.now().minusSeconds(60),Optional.empty(),"Now running",second.labelIds()));});
        var old=request(stale);((ObjectNode)old.path("choices")).put(stale.path("targets").get(0).path("key").asText(),"DELETE");
        assertEquals(412,assertThrows(ApiFailure.class,()->store.transaction(owner,()->new ImportUndo(store).apply(job,0,old))).status);
        var current=store.transaction(owner,()->new ImportUndo(store).preview(job));var reviewed=request(current);
        for(var target:current.path("targets")) ((ObjectNode)reviewed.path("choices")).put(target.path("key").asText(),target.path("status").asText().equals("RUNNING") ? "KEEP" : "DELETE");
        var result=store.transaction(owner,()->new ImportUndo(store).apply(job,0,reviewed));assertEquals(1,result.path("removed").asInt());assertEquals(1,result.path("retained").asInt());
        assertEquals("UNDONE_PARTIAL",finish(owner,job).path("state").asText());
        store.transaction(owner,()->{assertTrue(store.service().activity(ids.get(0)).isEmpty());assertTrue(store.service().activity(ids.get(1)).orElseThrow().end().isEmpty());return null;});
    }
    @Test void interruptedLargeUndoIsBoundedOwnerScopedAndFencesFurtherImportBatches() {
        String owner="undo-large-"+UUID.randomUUID();var ledger=new ImportLedger(store);UUID job=uuid(store.transaction(owner,()->job(ledger)).get("id"));
        for(int batch=0;batch<3;batch++) {
            int batchIndex=batch;
            store.transaction(owner,()->ledger.batch(job,batchIndex,object().put("batch",batchIndex),false,state->{
                for(int n=batchIndex*100;n<(batchIndex+1)*100;n++) entry(state,n,Instant.parse("2025-10-20T08:00:00Z").plusSeconds(n),Instant.parse("2025-10-20T09:00:00Z").plusSeconds(n));return object();
            }));
        }
        store.transaction(owner,()->new ImportUndo(store).start(job,etag(ledger.required(ImportLedger.Kind.IMPORT_JOB,job))));
        assertEquals(404,assertThrows(ApiFailure.class,()->store.transaction("other-"+UUID.randomUUID(),()->new ImportUndo(store).preview(job))).status);
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction(owner,()->ledger.batch(job,3,object(),true,state->object()))).status);
        var first=store.transaction(owner,()->new ImportUndo(store).preview(job));assertEquals(100,first.path("targets").size());
        store.transaction(owner,()->new ImportUndo(store).apply(job,0,request(first)));
        assertEquals(200,store.transaction(owner,()->store.list("ACTIVITY").size()));
        var last=finish(owner,job);assertEquals("UNDONE",last.path("state").asText());assertEquals(300,last.path("removed").asInt());
        assertEquals(0,store.transaction(owner,()->store.list("ACTIVITY").size()));
    }
}
