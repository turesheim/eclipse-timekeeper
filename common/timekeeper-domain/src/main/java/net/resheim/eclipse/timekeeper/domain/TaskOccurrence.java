package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;
import java.util.Optional;

/** Original recurrence slot is stable even when this instance is moved. */
public record TaskOccurrence(TaskMoment original, Optional<TaskMoment> movedTo,
		TaskOccurrenceState state, Optional<Instant> completedAt) {
	public TaskOccurrence {
		original=Values.required(original,"occurrenceOriginal");
		movedTo=movedTo==null?Optional.empty():movedTo;
		state=Values.required(state,"occurrenceState");
		completedAt=completedAt==null?Optional.empty():completedAt;
		if(state!=TaskOccurrenceState.COMPLETED && completedAt.isPresent())
			throw new DomainValidationException("occurrenceCompletedAt","completion time requires completed state");
		if(movedTo.isPresent() && movedTo.get().dateOnly()!=original.dateOnly())
			throw new DomainValidationException("occurrenceMovedTo","moving an occurrence cannot change all-day semantics");
	}
}
