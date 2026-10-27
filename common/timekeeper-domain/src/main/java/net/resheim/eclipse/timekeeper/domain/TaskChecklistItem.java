package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** A checklist item is part of a task, not an independently tracked task. */
public record TaskChecklistItem(UUID id, String text, boolean completed, Optional<Instant> completedAt, int position) {
	public TaskChecklistItem {
		id = Values.required(id, "checklistItemId");
		text = Values.required(text, "checklistItemText");
		if (text.length() > 1024) throw new DomainValidationException("checklistItemText", "checklist item text is too long");
		completedAt = completedAt == null ? Optional.empty() : completedAt;
		if (!completed && completedAt.isPresent())
			throw new DomainValidationException("checklistItemCompletedAt", "completion time requires a completed checklist item");
		if (position < 0) throw new DomainValidationException("checklistItemPosition", "position must not be negative");
	}
}
