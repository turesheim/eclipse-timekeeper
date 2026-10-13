package net.resheim.eclipse.timekeeper.domain;

/** Stable identity of the person or service that owns a time record. */
public record OwnerId(String value) {
	public static final OwnerId LOCAL = new OwnerId("local");

	public OwnerId {
		value = Values.required(value, "ownerId");
	}
}
