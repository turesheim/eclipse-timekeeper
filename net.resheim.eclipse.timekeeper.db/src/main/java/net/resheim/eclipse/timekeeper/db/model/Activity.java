/*******************************************************************************
 * Copyright © 2016-2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.model;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.eclipse.persistence.annotations.UuidGenerator;

import net.resheim.eclipse.timekeeper.db.converters.InstantAttributeConverter;

/**
 * The {@link Activity} type represents a period of work on a task. It holds the
 * start time, and the stop time a description and optionally a set of labels.
 * An activity can stretch over several days, however that should typically not
 * be the case. Multiple activities can be assign to the same {@link Task}, on
 * the same day.
 * 
 * @since 1.0
 * @author Torkild U. Resheim
 */
@Entity
@Table(name = "ACTIVITY")
@UuidGenerator(name = "uuid")
public class Activity implements Comparable<Activity>, Serializable {

	private static final long serialVersionUID = 7770745026684660897L;

	@Id
	@GeneratedValue(generator = "uuid")
	@Column(name = "ID")
	private String id;

	/** The time the activity was started */
	@Column(name = "START_TIME", columnDefinition = "VARCHAR(30)")
	@Convert(converter = InstantAttributeConverter.class)
	private Instant start;

	/** The time the activity was stopped */
	@Column(name = "END_TIME", columnDefinition = "VARCHAR(30)")
	@Convert(converter = InstantAttributeConverter.class)
	private Instant end;

	/** Whether or not activity properties have been manually adjusted or created */
	@Column(name = "ADJUSTED")
	private boolean manual = false;

	/** The task the activity is associated with */
	@ManyToOne
	@JoinColumn(name = "TASK_ID", referencedColumnName = "ID")
	private Task task;

	/** Identity of the person or service that owns this time record. */
	@Column(name = "OWNER_ID", nullable = false, updatable = false)
	private String ownerId = OwnerIdentity.LOCAL.value();

	/** The project this activity is associated with, if not associated with a tracked task */
	@ManyToOne
	@JoinColumn(name = "ACTIVITY_PROJECT")
	private Project project;

	// Labels are shared: removing an activity must never remove its labels.
	@ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
	@JoinTable(name = "ACTIVITY_ACTIVITYLABEL",
			joinColumns = @JoinColumn(name = "Activity_ID", referencedColumnName = "ID"),
			inverseJoinColumns = @JoinColumn(name = "labels_ID", referencedColumnName = "ID"))
	private List<ActivityLabel> labels = new ArrayList<>();

	/** A short summary of the activity */
	@Column(name = "SUMMARY")
	private String summary;

	public Activity() {
	}

	public Activity(Task task, Instant start) {
		this(task, OwnerIdentity.LOCAL, start);
	}

	public Activity(Task task, OwnerIdentity owner, Instant start) {
		this.task = task;
		this.ownerId = Objects.requireNonNull(owner, "owner").value();
		this.start = Objects.requireNonNull(start, "start");
		summary = "Activity started at " + start;
	}

	/**
	 * Returns the total duration of work.
	 * 
	 * @return the duration of work
	 */
	public Duration getDuration() {
		return Duration.between(getStart(), getEnd());
	}

	/**
	 * Returns the duration of work on the given date if any.
	 * 
	 * @param date the date to calculate for
	 * @param zoneId the calendar time zone that defines the date boundaries
	 * @return the amount of work occuring on the given date
	 */
	public Duration getDuration(LocalDate date, ZoneId zoneId) {
		return getDuration(date, date.plusDays(1), zoneId);
	}

	/**
	 * Returns the duration of the activity between the given dates.
	 * 
	 * @param start the start date
	 * @param end   the end date
	 * @param zoneId the calendar time zone that defines the date boundaries
	 * @return the duration of work between the two days
	 */
	public Duration getDuration(LocalDate start, LocalDate end, ZoneId zoneId) {
		Objects.requireNonNull(zoneId, "zoneId");
		Instant min = start.atStartOfDay(zoneId).toInstant();
		Instant max = end.atStartOfDay(zoneId).toInstant();

		Instant s = getStart();
		Instant e = getEnd();

		// end time has not been specified so the task must be currently
		// active, using current time as the end date.
		if (e == null) {
			e = Instant.now();
		}

		if (!s.isBefore(max) || !e.isAfter(min)) {
			return Duration.ZERO;
		}
		Instant clippedStart = s.isBefore(min) ? min : s;
		Instant clippedEnd = e.isAfter(max) ? max : e;
		return Duration.between(clippedStart, clippedEnd);
	}

	/**
	 * Specifies the duration of the activity by modifying the end time. Also sets
	 * the <i>manual</i> flag indicating that this activity was manually created or
	 * edited.
	 * 
	 * @param duration the duration
	 */
	public void setDuration(Duration duration) {
		end = start.plus(duration);
		manual = true;
	}

	public Instant getEnd() {
		return end;
	}

	public Instant getStart() {
		return start;
	}

	public void setEnd(Instant end) {
		this.end = end;
	}

	public void setStart(Instant start) {
		this.start = start;
	}

	public OwnerIdentity getOwner() {
		return new OwnerIdentity(ownerId);
	}

	public boolean isEdited() {
		return manual;
	}

	public Task getTrackedTask() {
		return task;
	}

	public String getSummary() {
		return summary;
	}

	public void setSummary(String summary) {
		this.summary = summary;
	}

	@Override
	public int compareTo(Activity o) {
		return this.getStart().compareTo(o.getStart());
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((end == null) ? 0 : end.hashCode());
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + (manual ? 1231 : 1237);
		result = prime * result + ((ownerId == null) ? 0 : ownerId.hashCode());
		result = prime * result + ((start == null) ? 0 : start.hashCode());
		result = prime * result + ((summary == null) ? 0 : summary.hashCode());
		result = prime * result + ((task == null) ? 0 : task.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Activity other = (Activity) obj;
		if (end == null) {
			if (other.end != null)
				return false;
		} else if (!end.equals(other.end))
			return false;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (manual != other.manual)
			return false;
		if (!Objects.equals(ownerId, other.ownerId))
			return false;
		if (start == null) {
			if (other.start != null)
				return false;
		} else if (!start.equals(other.start))
			return false;
		if (summary == null) {
			if (other.summary != null)
				return false;
		} else if (!summary.equals(other.summary))
			return false;
		if (task == null) {
			if (other.task != null)
				return false;
		} else if (!task.equals(other.task))
			return false;
		return true;
	}

	public List<ActivityLabel> getLabels() {
		return labels;
	}
	
	public void toggleLabel(ActivityLabel label) {
		Objects.requireNonNull(label, "label");
		Optional<ActivityLabel> hasLabel = labels.stream()
				.filter(l -> l == label || (label.getId() != null && label.getId().equals(l.getId())))
				.findFirst();
		if (hasLabel.isEmpty()) {
			labels.add(label);
		} else {
			labels.remove(hasLabel.get());
		}
		
	}

}
