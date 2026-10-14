package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

/** Identity of an owner's independent work session. */
public record WorkSessionId(UUID value) {
    public WorkSessionId { Values.required(value, "workSessionId"); }
    public static WorkSessionId random() { return new WorkSessionId(UUID.randomUUID()); }
}
