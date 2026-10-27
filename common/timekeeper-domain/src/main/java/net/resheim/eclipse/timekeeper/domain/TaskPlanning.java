package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Date-only scheduling is distinct from a deadline or an alert. */
public record TaskPlanning(TaskStatus status, Optional<LocalDate> scheduledDate, Optional<Instant> completedAt) {
	public TaskPlanning {
		status = Values.required(status, "status");
		scheduledDate = scheduledDate == null ? Optional.empty() : scheduledDate;
		completedAt = completedAt == null ? Optional.empty() : completedAt;
		if (status != TaskStatus.COMPLETED && completedAt.isPresent()) {
			throw new DomainValidationException("completedAt", "completion time requires completed status");
		}
	}

	public static TaskPlanning unplanned() {
		return new TaskPlanning(TaskStatus.NOT_STARTED, Optional.empty(), Optional.empty());
	}
}
