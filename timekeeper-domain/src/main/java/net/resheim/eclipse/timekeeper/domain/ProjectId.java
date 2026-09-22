package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

public record ProjectId(UUID value) {
	public ProjectId {
		Values.required(value, "projectId");
	}

	public static ProjectId random() {
		return new ProjectId(UUID.randomUUID());
	}
}
