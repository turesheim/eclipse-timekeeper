package net.resheim.eclipse.timekeeper.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Ports.*;

class WorkSessionServiceTest {
    private final OwnerId owner = new OwnerId("alice");
    private Instant now = Instant.parse("2025-10-25T22:00:00Z");
    private final Store store = new Store();
    private final List<TimekeeperEvent> events = new ArrayList<>();
    private final WorkSessionService service = new DefaultWorkSessionService(new WorkSessionPorts(
            store,store,WorkSessionId::random,()->now,store,events::add));
    private Activity activity(Instant start, Optional<Instant> end) {
        var activity = new Activity(ActivityId.random(),TaskId.random(),owner,start,end,"Activity",Set.of(),false,0);
        store.activities.put(activity.id(),activity);return activity;
    }
    @Test void sessionControlsAreIndependentAndOnlyOneSessionPerOwnerMayRun() {
        var activity = activity(now,Optional.empty());
        var first = service.startWorkSession(new StartWorkSession(owner,"Workday"));
        int count = events.size();
        assertThrows(ServiceException.class,()->service.startWorkSession(new StartWorkSession(owner,"Duplicate")));
        assertEquals(count,events.size());assertEquals(1,service.workSessions(owner).size());
        var other = service.startWorkSession(new StartWorkSession(new OwnerId("bob"),"Other account"));
        now = now.plus(Duration.ofHours(26));
        assertEquals(first,service.activeWorkSession(owner).orElseThrow());
        var stopped = service.stopWorkSession(new StopWorkSession(first.id(),first.version()));
        assertTrue(service.activeWorkSession(owner).isEmpty());assertTrue(stopped.end().isPresent());
        assertEquals(activity,store.activities.get(activity.id()),"Stopping a work session modified a separate activity timer");
        assertEquals(other,service.activeWorkSession(other.ownerId()).orElseThrow());
        var second = service.startWorkSession(new StartWorkSession(owner,"Another session"));
        assertNotEquals(first.id(),second.id());assertEquals(2,service.workSessions(owner).size());
        assertThrows(ServiceException.class,()->service.updateWorkSession(new UpdateWorkSession(
                stopped.id(),stopped.version(),stopped.start(),Optional.empty(),"Cannot reopen alongside another session")));
    }
    @Test void retrospectiveAssociationsWarnAndUnallocatedTimeUsesOverlapCoverage() {
        var end = now.plusSeconds(10);
        var session = service.createWorkSession(new CreateWorkSession(owner,now,Optional.of(end),"Past work"));
        var a = activity(now.minusSeconds(1),Optional.of(now.plusSeconds(6)));
        var b = activity(now.plusSeconds(4),Optional.of(now.plusSeconds(8)));
        var associatedA = service.associateWorkSession(new AssociateWorkSession(a.id(),a.version(),Optional.of(session.id())));
        var associatedB = service.associateWorkSession(new AssociateWorkSession(b.id(),b.version(),Optional.of(session.id())));
        assertTrue(associatedA.outsideSession());assertFalse(associatedB.outsideSession());
        var report = service.workSessionReport(session.id());
        assertEquals(Duration.ofSeconds(10),report.time().trackedActivityTime());
        assertEquals(Duration.ofSeconds(8),report.time().allocatedTime());assertEquals(Duration.ofSeconds(2),report.time().unallocatedTime());
        assertThrows(ServiceException.class,()->service.deleteWorkSession(new DeleteWorkSession(session.id(),session.version())));
        var edited = service.updateWorkSession(new UpdateWorkSession(session.id(),session.version(),session.start(),Optional.of(end.plusSeconds(2)),"Adjusted"));
        assertEquals(Duration.ofSeconds(4),service.workSessionReport(session.id()).time().unallocatedTime());
        assertThrows(ServiceException.class,()->service.updateWorkSession(new UpdateWorkSession(session.id(),session.version(),session.start(),session.end(),"Stale")));
        assertEquals(edited,service.workSession(session.id()).orElseThrow());
        for (var association : List.of(associatedA,associatedB)) {
            var detached = service.associateWorkSession(new AssociateWorkSession(association.activity().id(),association.activity().version(),Optional.empty()));
            assertTrue(detached.activity().workSessionId().isEmpty());assertEquals(association.activity().start(),detached.activity().start());
            assertEquals(association.activity().end(),detached.activity().end());
        }
        service.deleteWorkSession(new DeleteWorkSession(session.id(),edited.version()));assertTrue(service.workSession(session.id()).isEmpty());
        assertEquals(2,store.activities.size());
    }
    @Test void staleOrForeignAssociationsAndFutureStartsDoNotChangeAnything() {
        var session = service.startWorkSession(new StartWorkSession(owner,"Work"));
        var foreign = service.startWorkSession(new StartWorkSession(new OwnerId("bob"),"Other"));
        var activity = activity(now,Optional.empty());int count = events.size();
        assertThrows(ServiceException.class,()->service.associateWorkSession(new AssociateWorkSession(activity.id(),1,Optional.of(session.id()))));
        assertThrows(ServiceException.class,()->service.associateWorkSession(new AssociateWorkSession(activity.id(),0,Optional.of(foreign.id()))));
        assertThrows(DomainValidationException.class,()->service.createWorkSession(new CreateWorkSession(owner,now.plusNanos(1),Optional.empty(),"Future")));
        assertEquals(activity,store.activities.get(activity.id()));assertEquals(count,events.size());
    }
    private static class Store implements WorkSessionRepository, ActivityRepository, TransactionRunner {
        final Map<WorkSessionId,WorkSession> sessions = new HashMap<>();
        final Map<ActivityId,Activity> activities = new HashMap<>();
        public synchronized <T> T required(Supplier<T> work) {return work.get();}
        public Optional<WorkSession> find(WorkSessionId id) {return Optional.ofNullable(sessions.get(id));}
        public Optional<WorkSession> findOpenWorkSessionByOwner(OwnerId owner) {return sessions.values().stream().filter(v->v.ownerId().equals(owner)&&v.end().isEmpty()).findFirst();}
        public List<WorkSession> findWorkSessions(OwnerId owner) {return sessions.values().stream().filter(v->v.ownerId().equals(owner)).toList();}
        public List<Activity> findSessionActivities(WorkSessionId id) {return activities.values().stream().filter(v->v.workSessionId().equals(Optional.of(id))).toList();}
        public WorkSession save(WorkSession session) {sessions.put(session.id(),session);return session;}
        public void delete(WorkSessionId id) {sessions.remove(id);}
        public Optional<Activity> find(ActivityId id) {return Optional.ofNullable(activities.get(id));}
        public Optional<Activity> findOpenByOwner(OwnerId owner) {return activities.values().stream().filter(v->v.ownerId().equals(owner)&&v.end().isEmpty()).findFirst();}
        public List<Activity> findOverlapping(Instant from,Instant to,Optional<OwnerId> owner,Optional<TaskId> task) {throw new UnsupportedOperationException();}
        public boolean existsByTask(TaskId task) {return activities.values().stream().anyMatch(v->v.taskId().equals(task));}
        public boolean existsByLabel(LabelId label) {return activities.values().stream().anyMatch(v->v.labelIds().contains(label));}
        public Activity save(Activity activity) {activities.put(activity.id(),activity);return activity;}
        public void delete(ActivityId id) {activities.remove(id);}
    }
}
