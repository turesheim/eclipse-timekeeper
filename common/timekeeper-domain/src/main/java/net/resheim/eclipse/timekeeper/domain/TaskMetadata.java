package net.resheim.eclipse.timekeeper.domain;

import java.util.List;
import java.util.Set;

/** Apple-style tags, Outlook categories, and links remain separate concepts. */
public record TaskMetadata(Set<String> tags, Set<String> categories, List<TaskLink> links) {
	public TaskMetadata {
		tags=tags==null?Set.of():Set.copyOf(tags);
		categories=categories==null?Set.of():Set.copyOf(categories);
		links=links==null?List.of():List.copyOf(links);
		if(tags.size()>100 || categories.size()>100 || links.size()>100
				|| tags.stream().anyMatch(value -> invalid(value)) || categories.stream().anyMatch(value -> invalid(value))
				|| links.stream().map(TaskLink::id).distinct().count()!=links.size())
			throw new DomainValidationException("taskMetadata","invalid tags, categories, or links");
	}
	private static boolean invalid(String value) {
		return value==null || value.isBlank() || value.length()>200 || value.codePoints().anyMatch(Character::isISOControl);
	}
	public static TaskMetadata empty() { return new TaskMetadata(Set.of(),Set.of(),List.of()); }
}
