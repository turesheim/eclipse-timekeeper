package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.resheim.eclipse.timekeeper.db.adapter.JpaServicePorts;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

class WorkSessionPersistenceTest {
    @TempDir Path directory;
    private String url() {return "jdbc:h2:"+directory.resolve("sessions");}
    private WorkSessionService sessions(EntityManager manager) {
        var repository=new JpaServicePorts(()->manager);return new DefaultWorkSessionService(repository.workSessionPorts(event->{}));
    }
    @Test void independentSessionsLabelsAndExactOverlapsSurviveClosingAndReopeningTheFile() throws Exception {
        EntityManager manager=DatabaseStartup.open(url());WorkSessionId id;ActivityId activityId;LabelId labelId;
        var from=Instant.parse("2025-10-25T21:59:59.123456789Z");var end=Instant.parse("2025-10-27T22:00:00.987654321Z");
        WorkSessionTime expected;
        try {
            var repository=new JpaServicePorts(()->manager);var sessions=new DefaultWorkSessionService(repository.workSessionPorts(event->{}));
            var activities=new DefaultTimekeeperService(repository.ports(event->{}),sessions);
            var session=sessions.createWorkSession(new CreateWorkSession(OwnerId.LOCAL,from,Optional.of(end),"Persistent session"));id=session.id();
            var label=activities.createLabel(new CreateLabel("Persistent label",Optional.of("12,34,56")));labelId=label.id();
            var activity=activities.createActivity(new CreateActivity(null,OwnerId.LOCAL,from,Optional.of(end),"Taskless persistent",Set.of(labelId),Optional.of(id),Optional.empty()));activityId=activity.id();
            var report=sessions.workSessionReport(id);assertEquals(3,report.activities().size());
            assertEquals(Duration.between(from,end),report.time().duration());assertEquals(Duration.ZERO,report.time().unallocatedTime());expected=report.time();
            var changed=sessions.updateWorkSession(new UpdateWorkSession(id,session.version(),from,Optional.of(end),"Edited"));
            assertEquals(session.version()+1,changed.version());
            assertThrows(ServiceException.class,()->sessions.updateWorkSession(new UpdateWorkSession(id,session.version(),from,Optional.of(end),"Stale")));
        } finally {DatabaseStartup.close(manager);}
        var reopened=DatabaseStartup.open(url()+";IFEXISTS=TRUE");
        try {
            var sessions=sessions(reopened);var report=sessions.workSessionReport(id);assertEquals("Edited",report.session().summary());assertEquals(expected,report.time());
            for(var activity:report.activities()) {assertTrue(activity.task().isEmpty());assertEquals(Set.of(labelId),activity.labelIds());assertTrue(activity.workSessionId().isEmpty());}
            assertTrue(report.activities().stream().anyMatch(value->value.id().equals(activityId)));
            var before=List.copyOf(report.activities());sessions.deleteWorkSession(new DeleteWorkSession(id,report.session().version()));
            var repository=new JpaServicePorts(()->reopened);var activities=new DefaultTimekeeperService(repository.ports(event->{}));
            for(var activity:before) assertEquals(activity,activities.activity(activity.id()).orElseThrow());
        } finally {DatabaseStartup.close(reopened);}
    }
    @Test void databaseGateBlocksASeparateJpaAdapterUntilTheOtherConnectionCommits() throws Exception {
        var manager=DatabaseStartup.open(url());
        try(var connection=java.sql.DriverManager.getConnection(url(),"sa","");var workers=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var query=connection.createStatement()) {query.executeUpdate("MERGE INTO TIMEKEEPER_WRITE_LOCK KEY(ID) VALUES(1)");}
            var service=sessions(manager);var started=new CountDownLatch(1);
            var pending=workers.submit(()->{started.countDown();return service.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Locked across connections"));});
            assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->pending.get(250,TimeUnit.MILLISECONDS));
            connection.commit();assertEquals("Locked across connections",pending.get(5,TimeUnit.SECONDS).summary());
        } finally {DatabaseStartup.close(manager);}
    }
    @Test void twoJpaAdaptersCannotStartTwoSessionsForTheSameOwner() throws Exception {
        var manager=DatabaseStartup.open(url());
        try {
            var first=sessions(manager);var second=sessions(manager);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
            try(var workers=Executors.newFixedThreadPool(2)) {
                var futures=new ArrayList<Future<Boolean>>();
                for(var service:List.of(first,second)) futures.add(workers.submit(()->{
                    ready.countDown();go.await();try {service.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Concurrent"));return true;}
                    catch(ServiceException conflict) {assertEquals(FailureCode.CONFLICT,conflict.code());return false;}
                }));
                assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();int count=0;for(var future:futures) if(future.get(10,TimeUnit.SECONDS)) count++;
                assertEquals(1,count);assertEquals(1,first.workSessions(OwnerId.LOCAL).size());
            }
        } finally {DatabaseStartup.close(manager);}
    }
}
