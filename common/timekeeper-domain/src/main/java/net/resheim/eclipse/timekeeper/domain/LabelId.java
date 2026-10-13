package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

public record LabelId(UUID value) {
	public LabelId {
		Values.required(value, "labelId");
	}

	public static LabelId random() {
		return new LabelId(UUID.randomUUID());
	}
}
