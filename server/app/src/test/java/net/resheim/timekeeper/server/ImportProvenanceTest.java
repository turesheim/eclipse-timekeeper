package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(ImportProvenanceTest.Profile.class)
class ImportProvenanceTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:import_provenance_tests");}
    }
    @Inject ServerStore store;
    @Test void zoneResegmentationKeepsAllDailyPartsAndLaterEditsRemainVisibleToUndo() {
        String owner="import-lineage-"+UUID.randomUUID();var ledger=new ImportLedger(store);var provenance=new ImportProvenance(store);
        UUID job=uuid(store.transaction(owner,()->ledger.create("Reviewed history",hash("member"),"Europe/Oslo",hash("file"),hash("plan"))).get("id"));
        store.transaction(owner,()->ledger.batch(job,0,object(),true,state->{
            var activity=store.service().createActivity(new CreateActivity(null,new OwnerId(owner),Instant.parse("2025-10-25T20:00:00Z"),Optional.of(Instant.parse("2025-10-25T23:00:00Z")),"Original source",Set.of()));
            provenance.record(state,hash("entry"),hash("content"),0,List.of(activity),true);return object();
        }));
        String before=store.transaction(owner,()->etag(ledger.required(ImportLedger.Kind.IMPORT_JOB,job)));
        store.transaction(owner,()->{store.updateProfile("Europe/Oslo");return null;});
        store.transaction(owner,()->{
            // The same automatic partitioner runs on account-zone changes and daily maintenance.
            store.service().rolloverActivities(new OwnerId(owner));
            var item=ledger.required(ImportLedger.Kind.IMPORT_ITEM,ledger.itemId(job,0));
            assertEquals(2,item.path("parts").size());assertFalse(item.path("changedSinceImport").asBoolean());
            Duration duration=Duration.ZERO;Activity retained=null;
            for(var part:item.path("parts")) {
                var activity=store.service().activity(new ActivityId(uuid(part.get("id")))).orElseThrow();
                assertEquals(activity.version(),part.path("baselineVersion").asLong());
                assertEquals(ImportProvenance.baseline(owner,activity),part.path("baselineHash").asText());
                duration=duration.plus(Duration.between(activity.start(),activity.end().orElseThrow()));
                if(activity.start().equals(Instant.parse("2025-10-25T22:00:00Z"))) retained=activity;
            }
            assertEquals(Duration.ofHours(3),duration);
            var current=ledger.required(ImportLedger.Kind.IMPORT_JOB,job);
            assertEquals(412,assertThrows(ApiFailure.class,()->ledger.beginUndo(job,before)).status);
            assertNotNull(retained);
            store.service().updateActivity(new UpdateActivity(retained.id(),retained.version(),Instant.parse("2025-10-25T21:00:00Z"),retained.end(),"Edited after import",retained.labelIds()));
            var edited=ledger.required(ImportLedger.Kind.IMPORT_ITEM,ledger.itemId(job,0));
            assertTrue(edited.path("changedSinceImport").asBoolean());assertEquals(3,edited.path("parts").size());
            assertEquals(1,java.util.stream.StreamSupport.stream(edited.path("parts").spliterator(),false).filter(part->part.path("baselineVersion").asLong()==-1).count());
            assertTrue(provenance.active(current,hash("entry"),0).isPresent());return null;
        });
    }
    @Test void provenanceCannotClaimForeignRecordsOrReplaceTheCreatorWhenAnEntryIsReused() {
        String owner="import-reuse-"+UUID.randomUUID(),foreign="import-private-"+UUID.randomUUID();
        var ledger=new ImportLedger(store);var provenance=new ImportProvenance(store);
        var other=store.transaction(foreign,()->store.service().createActivity(new CreateActivity(null,new OwnerId(foreign),Instant.parse("2025-10-20T08:00:00Z"),Optional.of(Instant.parse("2025-10-20T09:00:00Z")),"Other owner",Set.of())));
        var job=store.transaction(owner,()->ledger.create("First",hash("member"),"UTC",hash("file"),hash("plan")));
        assertThrows(ApiFailure.class,()->store.transaction(owner,()->provenance.record(job,hash("entry"),hash("content"),0,List.of(other),true)));
        var first=store.transaction(owner,()->ledger.batch(uuid(job.get("id")),0,object(),true,state->{
            var created=store.service().createActivity(new CreateActivity(null,new OwnerId(owner),other.start(),other.end(),"Owned interval",Set.of()));
            provenance.record(state,hash("entry"),hash("content"),0,List.of(created),true);return object().put("activityId",created.id().value().toString());
        }));
        var next=store.transaction(owner,()->ledger.create("Another source",hash("another member"),"UTC",hash("file"),hash("plan")));
        store.transaction(owner,()->ledger.batch(uuid(next.get("id")),0,object(),true,state->{
            var reused=store.service().activity(new ActivityId(uuid(first.get("activityId")))).orElseThrow();
            provenance.record(state,hash("entry"),hash("content"),0,List.of(reused),false);return object();
        }));
        store.transaction(owner,()->{
            var origin=ledger.required(ImportLedger.Kind.IMPORT_ORIGIN,ImportLedger.identifier("activity-origin",first.path("activityId").asText()));
            assertEquals(job.path("id").asText(),origin.path("jobId").asText());
            assertFalse(ledger.required(ImportLedger.Kind.IMPORT_ITEM,ledger.itemId(uuid(next.get("id")),0)).path("created").asBoolean());
            var references=new ImportReferences(store);assertTrue(references.usedByAnotherImport("ACTIVITY",uuid(first.get("activityId")),uuid(job.get("id"))));
            var current=ledger.required(ImportLedger.Kind.IMPORT_JOB,uuid(next.get("id")));ledger.beginUndo(uuid(next.get("id")),etag(current));
            ledger.undoBatch(uuid(next.get("id")),0,object(),state->{state.put("state","UNDONE");return object();});
            assertFalse(references.usedByAnotherImport("ACTIVITY",uuid(first.get("activityId")),uuid(job.get("id"))));return null;
        });
        var repeated=store.transaction(owner,()->ledger.create("Repeated file",hash("member"),"UTC",hash("another file"),hash("plan")));
        store.transaction(owner,()->ledger.batch(uuid(repeated.get("id")),0,object(),true,state->{
            var existing=store.service().activity(new ActivityId(uuid(first.get("activityId")))).orElseThrow();
            provenance.reuseActive(state,hash("entry"),hash("content"),0,List.of(existing));return object();
        }));
        store.transaction(owner,()->{
            assertEquals(job.path("id").asText(),provenance.active(repeated,hash("entry"),0).orElseThrow().path("jobId").asText());
            assertTrue(new ImportReferences(store).usedByAnotherImport("ACTIVITY",uuid(first.get("activityId")),uuid(job.get("id"))));
            assertTrue(ledger.required(ImportLedger.Kind.IMPORT_ITEM,ledger.itemId(uuid(repeated.get("id")),0)).has("sharedClaimItemId"));return null;
        });
    }
}
