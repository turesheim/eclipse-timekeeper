package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.ServiceException;

@QuarkusTest @TestProfile(WorkSessionStoreTest.Profile.class)
class WorkSessionStoreTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:work_session_store_tests");}
    }
    @Inject ServerStore store;
    @Inject MockBackendClock clock;
    @org.junit.jupiter.api.AfterEach void resetClock() {clock.set(null);}
    @Test void legacyLinkCleanupPreservesOwnedRecordsTimersAndOtherOwners() {
        clock.set(Instant.parse("2025-10-25T12:00:00Z"));
        String owner="retired-links-"+UUID.randomUUID(),other="retained-links-"+UUID.randomUUID();
        var sessions=new HashMap<String,WorkSession>();var records=new HashMap<String,Activity>();
        for(var account:List.of(owner,other)) store.transaction(account,()->{
            var identity=new OwnerId(account);
            var session=store.workSessionService().startWorkSession(new StartWorkSession(identity,"Independent"));
            var activity=store.service().startActivity(new StartActivity(null,identity,"Timer unchanged",Set.of()));
            sessions.put(account,session);records.put(account,activity);
            var payload=JsonValues.snapshot(account,activity);payload.put("workSessionId",session.id().value().toString());
            store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type='ACTIVITY' AND id=?",payload.toString(),account,activity.id().value());
            store.update("INSERT INTO tk_relation VALUES(?,'ACTIVITY',?,'workSession','WORK_SESSION',?)",account,activity.id().value(),session.id().value());
            return null;
        });
        store.transaction(owner,()->{
            store.retireSessionLinks();store.retireSessionLinks();
            assertEquals(records.get(owner),store.service().activeActivity(new OwnerId(owner)).orElseThrow());
            try(var query=store.statement("SELECT payload FROM tk_resource WHERE owner_id=? AND entity_type='ACTIVITY'",owner);var rows=query.executeQuery()) {
                assertTrue(rows.next());assertFalse(JsonValues.parse(rows.getString(1)).has("workSessionId"));
            } catch(java.sql.SQLException failure) {throw new AssertionError(failure);}
            var session=sessions.get(owner);store.workSessionService().deleteWorkSession(new DeleteWorkSession(session.id(),session.version()));
            assertEquals(records.get(owner),store.service().activeActivity(new OwnerId(owner)).orElseThrow());return null;
        });
        store.transaction(other,()->{
            assertEquals(records.get(other),store.service().activeActivity(new OwnerId(other)).orElseThrow());
            assertTrue(store.related("workSession","WORK_SESSION",sessions.get(other).id().value()),"Cleanup crossed owner boundary");
            // A stale replica's legacy relation cannot prevent deleting a session.
            var session=sessions.get(other);store.workSessionService().deleteWorkSession(new DeleteWorkSession(session.id(),session.version()));
            assertEquals(records.get(other),store.service().activeActivity(new OwnerId(other)).orElseThrow());return null;
        });
    }
    @Test void temporalContextSurvivesAnotherStoreInstanceWithoutModifyingActivityTimers() {
        String owner="session-store-"+UUID.randomUUID();var from=Instant.parse("2025-10-25T21:59:59.123456789Z");
        var end=from.plus(Duration.ofHours(26));var identity=new OwnerId(owner);
        var session=store.transaction(owner,()->store.workSessionService().createWorkSession(new CreateWorkSession(identity,from,Optional.of(end),"Across midnight")));
        var task=store.transaction(owner,()->store.service().createTask(new CreateTask(Optional.empty(),"Session task",Optional.empty())));
        var activity=store.transaction(owner,()->store.service().createActivity(new CreateActivity(task.id(),identity,from,Optional.of(end),"Associated",Set.of())));
        var parts=store.transaction(owner,()->store.service().activities(new net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery(
                from,end.plusSeconds(1),Optional.of(identity),Optional.of(task.id()),Optional.empty(),ZoneId.of("UTC"))).activities());
        var live=store.transaction(owner,()->store.workSessionService().startWorkSession(new StartWorkSession(identity,"Independent running session")));
        var replica=new ServerStore();var actual=io.quarkus.arc.ClientProxy.unwrap(store);replica.datasource=actual.datasource;replica.restoreFence=actual.restoreFence;replica.tokens=actual.tokens;replica.jdbcUrl=actual.jdbcUrl;replica.clock=actual.clock;
        var report=replica.transaction(owner,()->replica.workSessionService().workSessionReport(session.id()));
        assertEquals(session,report.session());assertEquals(new HashSet<>(parts),new HashSet<>(report.activities()));
        assertEquals(Duration.ofHours(26),report.time().duration());assertEquals(Duration.ZERO,report.time().unallocatedTime());
        replica.transaction(owner,()->replica.workSessionService().stopWorkSession(new StopWorkSession(live.id(),live.version())));
        var persisted=store.transaction(owner,()->store.service().activity(activity.id()).orElseThrow());
        assertEquals(activity,persisted);assertTrue(persisted.workSessionId().isEmpty());
        store.transaction(owner,()->{store.workSessionService().deleteWorkSession(new DeleteWorkSession(session.id(),session.version()));return null;});
        assertEquals(activity,store.transaction(owner,()->store.service().activity(activity.id()).orElseThrow()));
        assertTrue(store.transaction("session-foreign-"+UUID.randomUUID(),()->store.workSessionService().workSession(session.id())).isEmpty());
    }
    @Test void midnightArchivesRemainIndependentAndDoNotStopTheSession() throws Exception {
        String owner="session-midnight-"+UUID.randomUUID();var identity=new OwnerId(owner);
        var from=Instant.parse("2025-10-25T21:59:59.123456789Z");clock.set(from);
        store.transaction(owner,()->store.updateProfile("Europe/Oslo"));
        var task=store.transaction(owner,()->store.service().createTask(new CreateTask(Optional.empty(),"Midnight task",Optional.empty())));
        var session=store.transaction(owner,()->store.workSessionService().startWorkSession(new StartWorkSession(identity,"Independent day")));
        var started=store.transaction(owner,()->store.service().startActivity(new StartActivity(task.id(),identity,"Daily activity",Set.of())));
        clock.set(from.plus(Duration.ofHours(26)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int count=0;
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);
        while(count!=3 && System.nanoTime()<deadline) {
            try(var connection=actual.datasource.getConnection();var query=connection.prepareStatement("SELECT COUNT(*) FROM tk_resource WHERE owner_id=? AND entity_type='ACTIVITY'")) {
                query.setString(1,owner);try(var rows=query.executeQuery()) {rows.next();count=rows.getInt(1);}
            }
            if(count!=3) Thread.sleep(20);
        }
        assertEquals(3,count,"Background midnight maintenance did not run");
        var report=store.transaction(owner,()->store.workSessionService().workSessionReport(session.id()));
        assertEquals(session,report.session());assertEquals(Duration.ofHours(26),report.time().trackedActivityTime());
        assertEquals(Duration.ZERO,report.time().unallocatedTime());assertEquals(3,report.activities().size());
        for(var activity:report.activities()) assertTrue(activity.workSessionId().isEmpty());
        store.transaction(owner,()->store.workSessionService().stopWorkSession(new StopWorkSession(session.id(),session.version())));
        var active=store.transaction(owner,()->store.service().activeActivity(identity).orElseThrow());assertEquals(started.id(),active.id());
        store.transaction(owner,()->store.service().stopActivity(new StopActivity(active.id(),active.version())));
    }
    @Test void concurrentSessionsUseTheSameOwnerDatabaseLockAcrossInstances() throws Exception {
        String owner="session-concurrent-"+UUID.randomUUID();store.transaction(owner,()->null);
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);var replica=new ServerStore();replica.datasource=actual.datasource;replica.restoreFence=actual.restoreFence;replica.tokens=actual.tokens;replica.jdbcUrl=actual.jdbcUrl;replica.clock=actual.clock;
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(var member:List.of(store,replica)) futures.add(workers.submit(()->{
                ready.countDown();go.await();
                try {member.transaction(owner,()->member.workSessionService().startWorkSession(new StartWorkSession(new OwnerId(owner),"Concurrent")));return true;}
                catch(ServiceException conflict) {assertEquals(FailureCode.CONFLICT,conflict.code());return false;}
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();int succeeded=0;
            for(var future:futures) if(future.get(10,TimeUnit.SECONDS)) succeeded++;
            assertEquals(1,succeeded);
        }
        assertEquals(1,store.transaction(owner,()->store.workSessionService().workSessions(new OwnerId(owner))).size());
    }
}
