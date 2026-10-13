package net.resheim.eclipse.timekeeper.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** A continuous work period independent of projects, tasks and activity timers. */
public record WorkSession(WorkSessionId id, OwnerId ownerId, Instant start,
        Optional<Instant> end, String summary, long version) {
    public enum State { RUNNING, COMPLETED }
    public WorkSession {
        id = Values.required(id, "workSessionId");
        ownerId = Values.required(ownerId, "ownerId");
        start = Values.required(start, "start");
        end = end == null ? Optional.empty() : end;
        if (end.isPresent() && end.get().isBefore(start))
            throw new DomainValidationException("end", "end must not be before start");
        summary = summary == null ? "" : summary.trim();
        version = Values.version(version);
    }
    public State state() { return end.isEmpty() ? State.RUNNING : State.COMPLETED; }
    public static void validateStart(Instant start, Instant now) {
        Values.required(start, "start"); Values.required(now, "now");
        if (start.isAfter(now)) throw new DomainValidationException("start", "A work session cannot start in the future.");
    }
    public WorkSession stop(Instant stoppedAt) {
        if (end.isPresent()) throw new DomainValidationException("workSession", "work session is already stopped");
        return new WorkSession(id, ownerId, start, Optional.of(Values.required(stoppedAt, "stoppedAt")), summary, version + 1);
    }
    public WorkSession edit(Instant newStart, Optional<Instant> newEnd, String newSummary) {
        return new WorkSession(id, ownerId, newStart, newEnd, newSummary, version + 1);
    }
    public Duration duration(Instant asOf) {
        Instant until = end.orElse(Values.required(asOf, "asOf"));
        return until.isAfter(start) ? Duration.between(start, until) : Duration.ZERO;
    }
}
