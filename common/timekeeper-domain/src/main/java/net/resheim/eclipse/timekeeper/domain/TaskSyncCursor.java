package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Opaque, owner-scoped connector state for list discovery or one provider list. */
public record TaskSyncCursor(UUID id, UUID connectionId, Optional<UUID> listId, TaskSyncMode mode,
		Optional<String> cursor, Optional<Instant> lastObservedAt, Optional<Instant> lastCompleteAt, long version) {
	public TaskSyncCursor {
		id = Values.required(id, "cursorId");
		connectionId = Values.required(connectionId, "connectionId");
		listId = listId == null ? Optional.empty() : listId;
		mode = Values.required(mode, "mode");
		cursor = cursor == null ? Optional.empty() : cursor;
		lastObservedAt = lastObservedAt == null ? Optional.empty() : lastObservedAt;
		lastCompleteAt = lastCompleteAt == null ? Optional.empty() : lastCompleteAt;
		if (cursor.filter(value -> value.isEmpty() || value.length() > 32768).isPresent())
			throw new DomainValidationException("cursor", "sync cursor must be nonempty and at most 32768 characters");
		if ((mode == TaskSyncMode.INCREMENTAL) != cursor.isPresent())
			throw new DomainValidationException("cursor", "only incremental mode has an opaque cursor");
		version = Values.version(version);
	}

	public TaskSyncCursor update(TaskSyncMode newMode, Optional<String> newCursor,
			Optional<Instant> observed, Optional<Instant> completed) {
		return new TaskSyncCursor(id, connectionId, listId, newMode, newCursor, observed, completed, version + 1);
	}
}
