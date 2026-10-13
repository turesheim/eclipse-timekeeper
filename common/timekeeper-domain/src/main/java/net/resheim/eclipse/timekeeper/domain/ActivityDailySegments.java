package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Exact local-day boundaries for recorded intervals, including ongoing work. */
public final class ActivityDailySegments {
    private ActivityDailySegments() { }

    public record Interval(Instant start, Optional<Instant> end) { }

    public static List<Interval> split(Activity activity, ZoneId zone, Instant now) {
        Values.required(activity,"activity");Values.required(zone,"zone");Values.required(now,"now");
        var result=new ArrayList<Interval>();
        Instant start=activity.start();
        while (true) {
            Instant boundary=start.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
            // Create no future-starting fragments and no zero-length completed tail.
            if (boundary.isAfter(now) || activity.end().filter(end->!boundary.isBefore(end)).isPresent()) break;
            result.add(new Interval(start,Optional.of(boundary)));
            start=boundary;
        }
        result.add(new Interval(start,activity.end()));
        return List.copyOf(result);
    }
}
