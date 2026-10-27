package net.resheim.eclipse.timekeeper.domain;

/** Priority, flag, and urgency are independent task properties. */
public record TaskImportance(TaskPriority priority, boolean flagged, boolean urgent) {
	public TaskImportance {
		priority = Values.required(priority, "priority");
	}
	public static TaskImportance ordinary() { return new TaskImportance(TaskPriority.UNSPECIFIED, false, false); }
}
