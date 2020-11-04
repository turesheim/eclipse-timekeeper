/*******************************************************************************
 * Copyright © 2017-2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.report.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.TrackedTask;

/**
 * Work week representation for use in reporting. It has utility methods that
 * makes it easier to obtain data using templates.
 * 
 * @author Torkild Ulvøy Resheim
 */
public class WorkWeek {

	/** All the days of this week */
	private LocalDate[] dates;
	
	/** Date of the first day of this week */
	private LocalDate firstDayOfWeek;

	/** All tasks that have been active this week */
	protected Set<TrackedTask> tasks = Collections.emptySet();
	
	/**
	 * Creates a new {@link WorkWeek} instance
	 * 
	 * @param firstDayOfWeek
	 *            date of the first day of the week
	 * @param tasks
	 *            a list of tasks being active this week
	 */
	public WorkWeek(LocalDate firstDayOfWeek, Set<TrackedTask> tasks) {
		super();
		this.firstDayOfWeek = firstDayOfWeek;
		this.tasks = tasks;
		update();
	}

	/**
	 * Returns the list of dates that constitures this work week
	 * 
	 * @return the list of dates in the week
	 */
	public LocalDate[] getDates() {
		return dates;
	}

	/**
	 * Returns the first day of the week
	 * 
	 * @return the first day of the week
	 */
	public LocalDate getFirstDayOfWeek() {
		return firstDayOfWeek;
	}

	/**
	 * Returns a list of all projects names.
	 */
	public Map<Project, List<TrackedTask>> getProjects() {
		return tasks
				.stream()
				.collect(Collectors.groupingBy(TrackedTask::getProject));
	}
	
	/**
	 * Returns the total amount of hours spent on the given project for the entire week.
	 */
	public Duration getSum() {
		Duration total = Duration.ZERO;
		for (LocalDate date : dates) {
			Optional<Duration> d = tasks.stream()
				.map(t -> t.getDuration(date))
				.reduce(Duration::plus);	
			if (d.isPresent()) {
				total = total.plus(d.get());
			}
		}
		return total;
	}

	/**
	 * Returns the total amount of hours spent at the given task.
	 */
	public Duration getSum(TrackedTask task) {
		Duration total = Duration.ZERO;
		for (LocalDate date : dates) {
			total = total.plus(task.getDuration(date));								
		}
		return total;
	}

	/**
	 * Returns the total amount of hours spent at the given date.
	 * 
	 * @param date
	 *            the date to calculate the total for
	 */
	public Duration getSum(LocalDate date) {
		Optional<Duration> d = tasks.stream()
			.map(t -> t.getDuration(date))
			.reduce(Duration::plus);
		if (d.isPresent()) {
			return d.get();
		} else {
			return Duration.ZERO;
		}
	}

	/**
	 * Returns the total amount of hours spent on the given project for the entire
	 * week.
	 * 
	 * @param project
	 *            the project name and identifier
	 */
	public Duration getSum(Project project) {
		Duration total = Duration.ZERO;
		for (LocalDate date : dates) {
			total = total.plus(tasks.stream()
					.filter(t -> project.equals(t.getProject()))
					.map(t -> t.getDuration(date))
					.reduce(Duration::plus).orElse(Duration.ZERO));			
		}
		return total;
	}

	/**
	 * Returns the total amount of hours spent on the given project at the given date.
	 */
	public Duration getSum(Project project, LocalDate date) {
		return tasks.stream()
			.filter(t -> project.equals(t.getProject()))
			.map(t -> t.getDuration(date))
			.reduce(Duration::plus).orElse(Duration.ZERO);
	}

	private void update() {
		dates = new LocalDate[7];
		WeekFields weekFields = WeekFields.of(Locale.getDefault());
		// Current day in the week
		long day = firstDayOfWeek.get(weekFields.dayOfWeek());
		// First date of the week
		LocalDate first = firstDayOfWeek.minusDays(day - 1l);
		for (int i = 0; i < 7; i++) {
			dates[i] = first;
			first = first.plusDays(1);
		}
	}

}
