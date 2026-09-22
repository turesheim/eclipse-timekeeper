package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

public record TaskId(UUID value) {
	public TaskId {
		Values.required(value, "taskId");
	}

	public static TaskId random() {
		return new TaskId(UUID.randomUUID());
	}
}
