package net.resheim.eclipse.timekeeper.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/** A civil day or a local wall time with its original provider time-zone ID. */
public record TaskMoment(LocalDate date, Optional<LocalTime> time, Optional<String> timeZone) {
	public TaskMoment {
		date = Values.required(date, "taskDate");
		time = time == null ? Optional.empty() : time;
		timeZone = timeZone == null ? Optional.empty() : timeZone.map(String::trim);
		if (time.isPresent() != timeZone.isPresent())
			throw new DomainValidationException("taskTime", "time and time zone must both be present or absent");
		if (timeZone.isPresent()) {
			String zone = timeZone.get();
			if (zone.isEmpty() || zone.length() > 128 || zone.codePoints().anyMatch(Character::isISOControl))
				throw new DomainValidationException("taskTimeZone", "invalid time zone ID");
		}
	}

	public static TaskMoment dateOnly(LocalDate date) { return new TaskMoment(date, Optional.empty(), Optional.empty()); }
	public static TaskMoment zoned(LocalDate date, LocalTime time, String zone) {
		return new TaskMoment(date, Optional.of(Values.required(time, "taskTime")), Optional.of(Values.required(zone, "taskTimeZone")));
	}
	public boolean dateOnly() { return time.isEmpty(); }
}
