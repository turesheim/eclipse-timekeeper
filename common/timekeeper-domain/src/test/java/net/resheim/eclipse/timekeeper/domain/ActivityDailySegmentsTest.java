package net.resheim.eclipse.timekeeper.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityDailySegmentsTest {
    private Activity activity(String start,String end) {
        return new Activity(new ActivityId(UUID.randomUUID()),new TaskId(UUID.randomUUID()),OwnerId.LOCAL,
                Instant.parse(start),Optional.ofNullable(end).map(Instant::parse),"Daily work",Set.of(),true,0);
    }
    @Test void manualAutumnAndSpringIntervalsKeepExactDurationAcrossLocalMidnight() {
        for(var source:new Activity[]{
                activity("2025-10-25T22:00:00.123456789Z","2025-10-27T02:00:00.987654321Z"),
                activity("2026-03-28T23:00:00.123456789Z","2026-03-30T02:00:00.987654321Z")}) {
            var parts=ActivityDailySegments.split(source,ZoneId.of("Europe/Oslo"),Instant.parse("2026-10-13T00:00:00Z"));
            assertEquals(2,parts.size());assertEquals(source.start(),parts.getFirst().start());
            assertEquals(source.end(),parts.getLast().end());
            assertEquals(parts.getFirst().end().orElseThrow(),parts.getLast().start());
            Duration sum=parts.stream().map(part->Duration.between(part.start(),part.end().orElseThrow())).reduce(Duration.ZERO,Duration::plus);
            assertEquals(Duration.between(source.start(),source.end().orElseThrow()),sum);
            long hours=source.start().atZone(ZoneId.of("Europe/Oslo")).getMonthValue()==10 ? 25 : 23;
            assertEquals(Duration.ofHours(hours).minusNanos(123456789),Duration.between(parts.getFirst().start(),parts.getFirst().end().orElseThrow()));
        }
    }
    @Test void ongoingWorkRollsOverAtTheExactBoundaryWithoutAnEmptyCompletedRecord() {
        var source=activity("2025-10-25T22:00:00Z",null);var midnight=Instant.parse("2025-10-26T23:00:00Z");
        var zone=ZoneId.of("Europe/Oslo");
        assertEquals(1,ActivityDailySegments.split(source,zone,midnight.minusNanos(1)).size());
        var parts=ActivityDailySegments.split(source,zone,midnight);
        assertEquals(2,parts.size());assertEquals(Optional.of(midnight),parts.getFirst().end());
        assertEquals(midnight,parts.getLast().start());assertTrue(parts.getLast().end().isEmpty());
        assertEquals(Duration.ofHours(25),Duration.between(parts.getFirst().start(),midnight));
        assertEquals(1,ActivityDailySegments.split(activity("2025-10-25T22:00:00Z",midnight.toString()),zone,midnight).size());
    }
    @Test void futureEndsDoNotIntroduceFutureStartsAndCanBeSplitLater() {
        var source=activity("2026-10-12T23:00:00Z","2026-10-16T12:00:00Z");
        var now=Instant.parse("2026-10-13T00:00:00Z");
        var parts=ActivityDailySegments.split(source,ZoneId.of("UTC"),now);
        assertEquals(2,parts.size());assertEquals(source.end(),parts.getLast().end());
        assertTrue(parts.stream().noneMatch(part->part.start().isAfter(now)));
        assertEquals(3,ActivityDailySegments.split(source,ZoneId.of("UTC"),now.plusSeconds(86400)).size());
    }
    @Test void skippedLocalDatesAndZeroLengthIntervalsDoNotInventTime() {
        var source=activity("2011-12-29T10:00:00Z","2011-12-30T11:00:00Z");
        var parts=ActivityDailySegments.split(source,ZoneId.of("Pacific/Apia"),Instant.parse("2012-01-01T00:00:00Z"));
        assertEquals(2,parts.size());assertEquals(Instant.parse("2011-12-30T10:00:00Z"),parts.getLast().start());
        var zero=activity("2026-10-13T00:00:00Z","2026-10-13T00:00:00Z");
        assertEquals(1,ActivityDailySegments.split(zero,ZoneId.of("UTC"),zero.start()).size());
    }
}
