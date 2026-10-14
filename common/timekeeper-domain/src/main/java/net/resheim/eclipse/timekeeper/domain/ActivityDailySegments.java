package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Exact local-day boundaries for recorded intervals, including ongoing work. */
public final class ActivityDailySegments {
    private ActivityDailySegments() { }

    public record Interval(Instant start, Optional<Instant> end) { }

    /** Stable archive identities let repeated imports retain the same daily rows. */
    public static ActivityId identifier(ActivityId retained, Instant start) {
        return new ActivityId(UUID.nameUUIDFromBytes(("timekeeper-day:"+retained.value()+":"+start)
                .getBytes(StandardCharsets.UTF_8)));
    }

    public static List<Interval> split(Activity activity, ZoneId zone, Instant now) {
        Values.required(activity,"activity");
        return split(activity.start(),activity.end(),zone,now);
    }

    public static List<Interval> split(Instant originalStart, Optional<Instant> end, ZoneId zone, Instant now) {
        Values.required(originalStart,"start");Values.required(end,"end");Values.required(zone,"zone");Values.required(now,"now");
        var result=new ArrayList<Interval>();
        Instant start=originalStart;
        while (true) {
            Instant boundary=start.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
            // Create no future-starting fragments and no zero-length completed tail.
            if (boundary.isAfter(now) || end.filter(value->!boundary.isBefore(value)).isPresent()) break;
            result.add(new Interval(start,Optional.of(boundary)));
            start=boundary;
        }
        result.add(new Interval(start,end));
        return List.copyOf(result);
    }
}
