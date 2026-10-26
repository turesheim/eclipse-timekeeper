package net.resheim.eclipse.timekeeper.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Ordered checklist state with stable item identities. */
public record TaskChecklist(List<TaskChecklistItem> items) {
	public TaskChecklist {
		if (items == null) items = List.of();
		if (items.stream().anyMatch(Objects::isNull))
			throw new DomainValidationException("checklistItems", "checklist items must not be null");
		if (items.size() > 1000) throw new DomainValidationException("checklistItems", "too many checklist items");
		items = List.copyOf(items);
		Set<UUID> ids = new HashSet<>();
		Set<Integer> positions = new HashSet<>();
		for (TaskChecklistItem item : items) {
			if (!ids.add(item.id())) throw new DomainValidationException("checklistItems", "checklist item IDs must be unique");
			if (!positions.add(item.position())) throw new DomainValidationException("checklistItems", "checklist positions must be unique");
		}
		for (int position = 0; position < items.size(); position++) {
			if (items.get(position).position() != position)
				throw new DomainValidationException("checklistItems", "checklist items must be in contiguous position order");
		}
	}

	public static TaskChecklist empty() { return new TaskChecklist(List.of()); }
}
