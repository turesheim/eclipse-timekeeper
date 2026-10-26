package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

/** Distinct reminder triggers. A non-time trigger cannot become a timed alert by accident. */
public sealed interface TaskReminder permits TaskReminder.Absolute, TaskReminder.Relative, TaskReminder.Location,
		TaskReminder.Car, TaskReminder.Message {
	UUID id();

	enum Anchor { START, DUE }
	enum Transition { ARRIVE, DEPART }
	enum CarEvent { CONNECT, DISCONNECT }

	record Absolute(UUID id, TaskMoment at) implements TaskReminder {
		public Absolute {
			id=Values.required(id,"reminderId");at=Values.required(at,"reminderAt");
			if(at.dateOnly()) throw new DomainValidationException("reminderAt","absolute alerts require a time and time zone");
		}
	}

	/** Positive offset means minutes before the anchor; negative means after. */
	record Relative(UUID id, Anchor anchor, int offsetMinutes) implements TaskReminder {
		public Relative {
			id=Values.required(id,"reminderId");anchor=Values.required(anchor,"reminderAnchor");
			if(offsetMinutes < -525600 || offsetMinutes > 525600)
				throw new DomainValidationException("offsetMinutes","relative alert exceeds one year");
		}
	}

	record Location(UUID id, double latitude, double longitude, double radiusMeters, Transition transition) implements TaskReminder {
		public Location {
			id=Values.required(id,"reminderId");transition=Values.required(transition,"reminderTransition");
			if(!Double.isFinite(latitude) || latitude < -90 || latitude > 90 || !Double.isFinite(longitude) || longitude < -180 || longitude > 180
					|| !Double.isFinite(radiusMeters) || radiusMeters <= 0 || radiusMeters > 100000)
				throw new DomainValidationException("reminderLocation","invalid geofence");
		}
	}

	record Car(UUID id, CarEvent event) implements TaskReminder {
		public Car { id=Values.required(id,"reminderId");event=Values.required(event,"reminderCarEvent"); }
	}

	record Message(UUID id, String contactKey) implements TaskReminder {
		public Message {
			id=Values.required(id,"reminderId");contactKey=Values.required(contactKey,"reminderContactKey").trim();
			if(contactKey.length()>1000 || contactKey.codePoints().anyMatch(Character::isISOControl))
				throw new DomainValidationException("reminderContactKey","invalid contact key");
		}
	}
}
