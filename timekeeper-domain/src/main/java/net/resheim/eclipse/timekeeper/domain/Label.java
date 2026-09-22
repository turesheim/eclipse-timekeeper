package net.resheim.eclipse.timekeeper.domain;

import java.util.Optional;

/** Immutable activity-label aggregate state. */
public record Label(LabelId id, String name, Optional<String> color, long version) {
	public Label {
		id = Values.required(id, "labelId");
		name = Values.required(name, "name");
		color = color == null ? Optional.empty() : color.map(String::trim).filter(value -> !value.isEmpty());
		version = Values.version(version);
	}

	public Label update(String newName, Optional<String> newColor) {
		return new Label(id, newName, newColor, version + 1);
	}
}
