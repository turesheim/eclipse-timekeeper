package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;

@QuarkusTest @TestProfile(ImportLedgerTest.Profile.class)
class ImportLedgerTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:import_ledger_tests");}
    }
    @Inject ServerStore store;
    private com.fasterxml.jackson.databind.node.ObjectNode create(ImportLedger ledger) {
        return ledger.create("Reviewed export",hash("selected source"),"Europe/Oslo",hash("file"),hash("reviewed mappings"));
    }
    @Test void rollbackLostReplyRestartAndUndoFencePreserveOneAuthoritativeOutcome() {
        String owner="import-ledger-"+UUID.randomUUID();var ledger=new ImportLedger(store);
        var job=store.transaction(owner,()->create(ledger));UUID id=uuid(job.get("id"));var input=object().put("entry","one");
        assertThrows(ApiFailure.class,()->store.transaction(owner,()->ledger.batch(id,0,input,false,state->{
            store.service().createProject(new CreateProject("Must roll back"));throw ApiFailure.validation();
        })));
        store.transaction(owner,()->{assertTrue(store.service().projects().isEmpty());assertEquals(0,ledger.required(ImportLedger.Kind.IMPORT_JOB,id).path("nextBatch").asInt());return null;});
        var executions=new AtomicInteger();
        var outcome=store.transaction(owner,()->ledger.batch(id,0,input,false,state->{
            executions.incrementAndGet();var project=store.service().createProject(new CreateProject("Imported project"));
            var item=object().put("kind","CATALOG").put("recordId",project.id().value().toString());ledger.appendItem(state,item);
            return object().put("created",1);
        }));
        // Simulate a new server instance after a lost transport reply. No new command key is needed to reconcile.
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);var replica=new ServerStore();
        replica.datasource=actual.datasource;replica.tokens=actual.tokens;replica.jdbcUrl=actual.jdbcUrl;replica.clock=actual.clock;
        var replay=replica.transaction(owner,()->new ImportLedger(replica).batch(id,0,input,false,state->{throw new AssertionError("Batch replay executed writes");}));
        assertEquals(outcome.toString(),replay.toString());assertEquals(1,executions.get());
        store.transaction(owner,()->{
            var current=ledger.required(ImportLedger.Kind.IMPORT_JOB,id);assertEquals(1,current.path("itemCount").asInt());
            ledger.beginUndo(id,etag(current));return null;
        });
        assertThrows(ApiFailure.class,()->store.transaction(owner,()->ledger.batch(id,1,input,true,state->{throw new AssertionError("A stale worker wrote after Undo");})));
        var oldReply=store.transaction(owner,()->ledger.batch(id,0,input,false,state->{throw new AssertionError("Historical reply created data");}));
        assertEquals(outcome.toString(),oldReply.toString());
        assertThrows(ApiFailure.class,()->store.transaction(owner,()->ledger.batch(id,0,object().put("entry","changed"),false,state->object())));
        store.transaction(owner,()->{
            assertEquals("UNDOING",ledger.required(ImportLedger.Kind.IMPORT_JOB,id).path("state").asText());
            assertEquals(1,store.service().projects().size());return null;
        });
        store.transaction("other-"+UUID.randomUUID(),()->{
            assertTrue(ledger.find(ImportLedger.Kind.IMPORT_JOB,id).isEmpty());
            assertThrows(ApiFailure.class,()->ledger.beginUndo(id,etag(job)));assertTrue(store.service().projects().isEmpty());return null;
        });
    }
    @Test void concurrentConflictingBatchesSerializeAndReportingZoneChangesRequireNewReview() throws Exception {
        String owner="import-concurrent-"+UUID.randomUUID();var ledger=new ImportLedger(store);
        UUID id=uuid(store.transaction(owner,()->create(ledger)).get("id"));
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var executions=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(var label:List.of("First","Second")) futures.add(workers.submit(()->{
                ready.countDown();go.await();
                try {store.transaction(owner,()->ledger.batch(id,0,object().put("choice",label),false,state->{
                    executions.incrementAndGet();store.service().createProject(new CreateProject(label));return object().put("created",1);
                }));return true;} catch(ApiFailure conflict) {assertEquals(409,conflict.status);return false;}
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();int winners=0;
            for(var future:futures) if(future.get(10,TimeUnit.SECONDS)) winners++;
            assertEquals(1,winners);assertEquals(1,executions.get());
        }
        store.transaction(owner,()->{store.updateProfile("Europe/Oslo");return null;});
        assertThrows(ApiFailure.class,()->store.transaction(owner,()->ledger.batch(id,1,object(),true,state->{throw new AssertionError("Changed zone was silently accepted");})));
        store.transaction(owner,()->{assertEquals(1,store.service().projects().size());assertEquals(1,ledger.required(ImportLedger.Kind.IMPORT_JOB,id).path("nextBatch").asInt());return null;});
    }
    @Test void undoCanAdvanceBeyondTheSourceBatchLimitWhenEntriesHaveManyDailyParts() {
        String owner="import-many-parts-"+UUID.randomUUID();var ledger=new ImportLedger(store);
        UUID id=uuid(store.transaction(owner,()->create(ledger)).get("id"));
        store.transaction(owner,()->{
            var state=ledger.beginUndo(id,etag(ledger.required(ImportLedger.Kind.IMPORT_JOB,id)));
            // Model restored progress without materializing ten million fixture activities.
            state.put("undoNextBatch",100001);ledger.update(ImportLedger.Kind.IMPORT_JOB,state);
            var result=ledger.undoBatch(id,100001,object(),job->object().put("continued",true));
            assertTrue(result.path("continued").asBoolean());assertEquals(100002,ledger.required(ImportLedger.Kind.IMPORT_JOB,id).path("undoNextBatch").asInt());return null;
        });
    }
}
