package net.resheim.eclipse.timekeeper.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.DomainValidationException;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.TaskId;

/** Query inputs and immutable reporting results. */
public final class Queries {
	private Queries() { }

	public record ActivityQuery(Instant fromInclusive, Instant toExclusive, Optional<OwnerId> ownerId,
			Optional<TaskId> taskId, Optional<ProjectId> projectId, ZoneId zoneId) {
		public ActivityQuery {
			if (fromInclusive == null) throw invalid("fromInclusive", "fromInclusive must not be null");
			if (toExclusive == null) throw invalid("toExclusive", "toExclusive must not be null");
			if (!toExclusive.isAfter(fromInclusive)) {
				throw invalid("toExclusive", "toExclusive must be after fromInclusive");
			}
			if (zoneId == null) throw invalid("zoneId", "zoneId must not be null");
			ownerId = optional(ownerId);
			taskId = optional(taskId);
			projectId = optional(projectId);
		}
	}

	public record ActivityReport(ActivityQuery query, Instant generatedAt, List<Activity> activities,
			Duration total) {
		public ActivityReport {
			activities = List.copyOf(activities);
		}
	}

	private static DomainValidationException invalid(String field, String message) {
		return new DomainValidationException(field, message);
	}

	private static <T> Optional<T> optional(Optional<T> value) {
		return value == null ? Optional.empty() : value;
	}
}
