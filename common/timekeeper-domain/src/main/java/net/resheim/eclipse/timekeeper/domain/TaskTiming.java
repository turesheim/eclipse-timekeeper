package net.resheim.eclipse.timekeeper.domain;

import java.util.Optional;

/** Start and due values do not imply reminders or a scheduled day. */
public record TaskTiming(Optional<TaskMoment> start, Optional<TaskMoment> due) {
	public TaskTiming {
		start = start == null ? Optional.empty() : start;
		due = due == null ? Optional.empty() : due;
	}
	public static TaskTiming empty() { return new TaskTiming(Optional.empty(), Optional.empty()); }
}
