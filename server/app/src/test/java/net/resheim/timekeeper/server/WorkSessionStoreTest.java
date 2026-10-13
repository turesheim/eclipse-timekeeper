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
    @Test void storedSessionsAndAssociationsSurviveAnotherStoreInstanceWithoutModifyingActivityTimers() {
        String owner="session-store-"+UUID.randomUUID();var from=Instant.parse("2025-10-25T21:59:59.123456789Z");
        var end=from.plus(Duration.ofHours(26));var identity=new OwnerId(owner);
        var session=store.transaction(owner,()->store.workSessionService().createWorkSession(new CreateWorkSession(identity,from,Optional.of(end),"Across midnight")));
        var task=store.transaction(owner,()->store.service().createTask(new CreateTask(Optional.empty(),"Session task",Optional.empty())));
        var activity=store.transaction(owner,()->store.service().createActivity(new CreateActivity(task.id(),identity,from,Optional.of(end),"Associated",Set.of())));
        var association=store.transaction(owner,()->store.workSessionService().associateWorkSession(new AssociateWorkSession(activity.id(),activity.version(),Optional.of(session.id()))));
        var live=store.transaction(owner,()->store.workSessionService().startWorkSession(new StartWorkSession(identity,"Independent running session")));
        var replica=new ServerStore();var actual=io.quarkus.arc.ClientProxy.unwrap(store);replica.datasource=actual.datasource;replica.tokens=actual.tokens;replica.jdbcUrl=actual.jdbcUrl;
        var report=replica.transaction(owner,()->replica.workSessionService().workSessionReport(session.id()));
        assertEquals(session,report.session());assertEquals(List.of(association.activity()),report.activities());
        assertEquals(Duration.ofHours(26),report.time().duration());assertEquals(Duration.ZERO,report.time().unallocatedTime());
        replica.transaction(owner,()->replica.workSessionService().stopWorkSession(new StopWorkSession(live.id(),live.version())));
        var persisted=store.transaction(owner,()->store.service().activity(activity.id()).orElseThrow());
        assertEquals(association.activity(),persisted);assertEquals(Optional.of(session.id()),persisted.workSessionId());
        assertThrows(ServiceException.class,()->store.transaction(owner,()->{store.workSessionService().deleteWorkSession(new DeleteWorkSession(session.id(),session.version()));return null;}));
        assertTrue(store.transaction("session-foreign-"+UUID.randomUUID(),()->store.workSessionService().workSession(session.id())).isEmpty());
    }
    @Test void concurrentSessionsUseTheSameOwnerDatabaseLockAcrossInstances() throws Exception {
        String owner="session-concurrent-"+UUID.randomUUID();store.transaction(owner,()->null);
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);var replica=new ServerStore();replica.datasource=actual.datasource;replica.tokens=actual.tokens;replica.jdbcUrl=actual.jdbcUrl;
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
