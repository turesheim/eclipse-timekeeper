package net.resheim.eclipse.timekeeper.service;

import java.time.Instant;

/** Persistence-neutral change event emitted by the application service. */
public record TimekeeperEvent(EntityType entityType, ChangeType changeType, String entityId, Instant occurredAt) {
	public enum EntityType { PROJECT, TASK, ACTIVITY, LABEL, WORK_SESSION }
	public enum ChangeType { CREATED, UPDATED, DELETED }
}
