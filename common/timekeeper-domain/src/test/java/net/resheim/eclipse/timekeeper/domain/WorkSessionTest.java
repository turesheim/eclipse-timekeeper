package net.resheim.eclipse.timekeeper.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkSessionTest {
    private static final OwnerId OWNER = new OwnerId("alice");
    private static final Instant START = Instant.parse("2025-10-25T21:59:59.123456789Z");
    private WorkSession session(Optional<Instant> end) {
        return new WorkSession(WorkSessionId.random(), OWNER, START, end, " Workday ", 0);
    }
    private Activity activity(Instant start, Optional<Instant> end) {
        return new Activity(ActivityId.random(), TaskId.random(), OWNER, start, end, "Work", Set.of(), false, 0);
    }
    @Test void sessionRemainsRunningAcrossMidnightAndDstUntilExplicitStop() {
        var session = session(Optional.empty()); var now = START.plus(Duration.ofHours(26));
        assertEquals(WorkSession.State.RUNNING, session.state()); assertEquals(Duration.ofHours(26), session.duration(now));
        var stopped = session.stop(now);
        assertEquals(session.id(), stopped.id()); assertEquals(1, stopped.version());
        assertEquals(WorkSession.State.COMPLETED, stopped.state());
        assertEquals(Duration.ofHours(26), stopped.duration(now.plus(Duration.ofDays(3))));
        assertThrows(DomainValidationException.class, () -> stopped.stop(now));
    }
    @Test void overlapsAndOutsideIntervalsUseAnExactClippedUnionForUnallocatedTime() {
        var end = START.plusSeconds(10); var session = session(Optional.of(end));
        var first = activity(START.minusSeconds(5), Optional.of(START.plusSeconds(4).plusNanos(1)));
        var second = activity(START.plusSeconds(2), Optional.of(START.plusSeconds(7)));
        var third = activity(START.plusSeconds(9), Optional.of(end.plusSeconds(20)));
        var time = WorkSessionTime.calculate(session, List.of(first, second, third), end);
        assertEquals(Duration.ofSeconds(10), time.duration());
        assertEquals(Duration.ofSeconds(10).plusNanos(1), time.trackedActivityTime());
        assertEquals(Duration.ofSeconds(8), time.allocatedTime()); assertEquals(Duration.ofSeconds(2), time.unallocatedTime());
        assertEquals(Set.of(first.id(), third.id()), time.outsideSession());
        assertEquals(time, WorkSessionTime.calculate(session, List.of(third, second, first), end));
    }
    @Test void activeActivitiesUseOneAsOfInstantAndDoNotGetStoppedWithTheSession() {
        var session = session(Optional.empty()); var now = START.plusSeconds(60).plusNanos(123);
        var activity = activity(START.plusSeconds(30), Optional.empty());
        var time = WorkSessionTime.calculate(session, List.of(activity), now);
        assertEquals(Duration.ofSeconds(30).plusNanos(123), time.allocatedTime());
        assertEquals(Duration.ofSeconds(30), time.unallocatedTime());
        var stopped = session.stop(now);
        assertTrue(activity.end().isEmpty());
        var later = WorkSessionTime.calculate(stopped, List.of(activity), now.plusSeconds(1));
        assertEquals(time.unallocatedTime(), later.unallocatedTime()); assertEquals(Set.of(activity.id()), later.outsideSession());
    }
    @Test void preciseGapsAndZeroLengthRecordsDoNotLoseNanoseconds() {
        var end = START.plusNanos(10); var session = session(Optional.of(end));
        var a = activity(START, Optional.of(START.plusNanos(4)));
        var b = activity(START.plusNanos(5), Optional.of(end));
        var zero = activity(end, Optional.of(end));
        var time = WorkSessionTime.calculate(session, List.of(zero,b,a), end);
        assertEquals(Duration.ofNanos(9), time.allocatedTime()); assertEquals(Duration.ofNanos(1), time.unallocatedTime());
        assertEquals(Duration.ofNanos(10), WorkSessionTime.calculate(session,List.of(),end).unallocatedTime());
    }
    @Test void invalidTimesDuplicateRowsAndCrossOwnerAssociationsAreRefused() {
        assertThrows(DomainValidationException.class, () -> session(Optional.of(START.minusNanos(1))));
        WorkSession.validateStart(START, START);
        assertThrows(DomainValidationException.class, () -> WorkSession.validateStart(START.plusNanos(1), START));
        var a = activity(START, Optional.of(START.plusSeconds(1)));
        var session = session(Optional.empty());
        assertThrows(DomainValidationException.class, () -> WorkSessionTime.calculate(session,List.of(a,a),START.plusSeconds(1)));
        var foreign = new Activity(a.id(), a.taskId(),new OwnerId("bob"),a.start(),a.end(),a.summary(),a.labelIds(),a.manual(),a.version());
        assertThrows(DomainValidationException.class, () -> WorkSessionTime.calculate(session,List.of(foreign),START.plusSeconds(1)));
    }
}
