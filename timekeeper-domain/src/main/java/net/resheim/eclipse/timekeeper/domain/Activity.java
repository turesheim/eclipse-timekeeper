package net.resheim.eclipse.timekeeper.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

/** Immutable recorded interval owned by a user or service. */
public record Activity(ActivityId id, TaskId taskId, OwnerId ownerId, Instant start,
		Optional<Instant> end, String summary, Set<LabelId> labelIds, boolean manual, long version) {
	public Activity {
		id = Values.required(id, "activityId");
		taskId = Values.required(taskId, "taskId");
		ownerId = Values.required(ownerId, "ownerId");
		start = Values.required(start, "start");
		end = end == null ? Optional.empty() : end;
		if (end.isPresent() && end.get().isBefore(start)) {
			throw new DomainValidationException("end", "end must not be before start");
		}
		summary = summary == null ? "" : summary.trim();
		labelIds = Values.set(labelIds, "labelIds");
		version = Values.version(version);
	}

	public Activity stop(Instant stoppedAt) {
		if (end.isPresent()) throw new DomainValidationException("activity", "activity is already stopped");
		return new Activity(id, taskId, ownerId, start,
				Optional.of(Values.required(stoppedAt, "stoppedAt")), summary, labelIds, manual,
				version + 1);
	}

	public Activity edit(Instant newStart, Instant newEnd, String newSummary, Set<LabelId> newLabelIds) {
		return new Activity(id, taskId, ownerId, newStart, Optional.ofNullable(newEnd), newSummary,
				newLabelIds, true, version + 1);
	}

	public Duration duration(LocalDate date, ZoneId zoneId, Instant asOf) {
		Values.required(date, "date");
		Values.required(zoneId, "zoneId");
		return durationBetween(date.atStartOfDay(zoneId).toInstant(),
				date.plusDays(1).atStartOfDay(zoneId).toInstant(), asOf);
	}

	public Duration durationBetween(Instant rangeStart, Instant rangeEnd, Instant asOf) {
		Values.required(rangeStart, "rangeStart");
		Values.required(rangeEnd, "rangeEnd");
		Values.required(asOf, "asOf");
		if (!rangeEnd.isAfter(rangeStart)) {
			throw new DomainValidationException("rangeEnd", "rangeEnd must be after rangeStart");
		}
		Instant actualEnd = end.orElse(asOf);
		if (!start.isBefore(rangeEnd) || !actualEnd.isAfter(rangeStart)) return Duration.ZERO;
		Instant clippedStart = start.isAfter(rangeStart) ? start : rangeStart;
		Instant clippedEnd = actualEnd.isBefore(rangeEnd) ? actualEnd : rangeEnd;
		return clippedEnd.isAfter(clippedStart) ? Duration.between(clippedStart, clippedEnd) : Duration.ZERO;
	}
}
