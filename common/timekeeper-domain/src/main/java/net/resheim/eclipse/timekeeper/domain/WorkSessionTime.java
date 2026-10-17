package net.resheim.eclipse.timekeeper.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Exact session accounting over the supplied overlapping activity snapshots. */
public record WorkSessionTime(Duration duration, Duration trackedActivityTime,
        Duration allocatedTime, Duration unallocatedTime, Set<ActivityId> outsideSession) {
    private record Interval(Instant start, Instant end) { }

    /**
     * Tracked time sums each activity's recorded time within the session. Allocated
     * time is their union; only this union is subtracted to find unallocated time.
     * This preserves overlap information without double-counting covered time.
     */
    public static WorkSessionTime calculate(WorkSession session, Collection<Activity> activities, Instant asOf) {
        Values.required(session, "workSession"); Values.required(activities, "activities"); Values.required(asOf, "asOf");
        Instant until = session.end().orElse(asOf);
        var intervals = new ArrayList<Interval>();
        var outside = new HashSet<ActivityId>();
        Duration tracked = Duration.ZERO;
        var seen = new HashSet<ActivityId>();
        for (var activity : activities) {
            Values.required(activity, "activity");
            if (!activity.ownerId().equals(session.ownerId()))
                throw new DomainValidationException("ownerId", "work session and activity must have the same owner");
            if (!seen.add(activity.id()))
                throw new DomainValidationException("activities", "an activity must occur only once in a work session");
            Instant end = activity.end().orElse(asOf);
            if (activity.start().isBefore(session.start()) || activity.start().isAfter(until) || end.isAfter(until)) outside.add(activity.id());
            Instant first = activity.start().isAfter(session.start()) ? activity.start() : session.start();
            Instant last = end.isBefore(until) ? end : until;
            if (last.isAfter(first)) {
                intervals.add(new Interval(first, last)); tracked = tracked.plus(Duration.between(first, last));
            }
        }
        intervals.sort(Comparator.comparing(Interval::start).thenComparing(Interval::end));
        Duration allocated = Duration.ZERO;
        Instant first = null, last = null;
        for (var interval : intervals) {
            if (last == null) { first = interval.start(); last = interval.end(); }
            else if (!interval.start().isAfter(last)) {
                if (interval.end().isAfter(last)) last = interval.end();
            } else {
                allocated = allocated.plus(Duration.between(first, last)); first = interval.start(); last = interval.end();
            }
        }
        if (last != null) allocated = allocated.plus(Duration.between(first, last));
        Duration duration = session.duration(asOf);
        return new WorkSessionTime(duration, tracked, allocated, duration.minus(allocated), Set.copyOf(outside));
    }
}
