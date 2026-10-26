package net.resheim.eclipse.timekeeper.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Series rule and individually addressed occurrence exceptions. */
public record TaskRecurrence(Optional<TaskSeries> series, List<TaskOccurrence> occurrences) {
	public TaskRecurrence {
		series=series==null?Optional.empty():series;
		occurrences=occurrences==null?List.of():List.copyOf(occurrences);
		if(series.isEmpty() && !occurrences.isEmpty()) throw new DomainValidationException("occurrences","occurrences require a series");
		if(occurrences.size()>1000) throw new DomainValidationException("occurrences","too many occurrence exceptions");
		Set<TaskMoment> slots=new HashSet<>();
		if(occurrences.stream().anyMatch(value -> !slots.add(value.original())))
			throw new DomainValidationException("occurrences","original occurrence slots must be unique");
	}
	public static TaskRecurrence empty() { return new TaskRecurrence(Optional.empty(),List.of()); }
}
