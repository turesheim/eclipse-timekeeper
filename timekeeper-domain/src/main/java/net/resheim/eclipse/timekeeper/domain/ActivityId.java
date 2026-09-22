package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

public record ActivityId(UUID value) {
	public ActivityId {
		Values.required(value, "activityId");
	}

	public static ActivityId random() {
		return new ActivityId(UUID.randomUUID());
	}
}
