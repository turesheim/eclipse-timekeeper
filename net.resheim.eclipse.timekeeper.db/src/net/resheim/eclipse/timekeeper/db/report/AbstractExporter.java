/*******************************************************************************
 * Copyright © 2015-2020 Torkild U. Resheim
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.db.report;

import java.time.LocalDate;
import java.util.Set;

import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.TrackedTask;

/**
 * This type contains common features for the various exporters.
 *
 * @author Torkild U. Resheim
 */
public abstract class AbstractExporter {

	/**
	 * Calculates the total amount of seconds accumulated on the project for the
	 * specified date.
	 *
	 * @param tasks
	 *            a list of tasks to get the amount for
	 * @param date
	 *            the date to calculate for
	 * @param project
	 *            the project to calculate for
	 * @return the total amount of seconds accumulated
	 */
	protected long getSum(Set<TrackedTask> tasks, LocalDate date, Project project) {
		return tasks
				.stream()
				.filter(t -> project.equals(t.getProject()))
				.mapToLong(t -> t.getDuration(date).getSeconds())
				.sum();
	}

	/**
	 * Calculates the total amount of seconds accumulated on specified date.
	 *
	 * @param tasks
	 *            a list of tasks to get the sum for
	 * @param date
	 *            the date to calculate for
	 * @return the total amount of seconds accumulated
	 */
	protected long getSum(Set<TrackedTask> tasks, LocalDate date) {
		return tasks
				.stream()
				.mapToLong(t -> t.getDuration(date).getSeconds())
				.sum();
	}

	/**
	 * Returns the exported data as a string.
	 *
	 * @param firstDateOfWeek
	 * @return the workweek formatted as a string
	 */
	public abstract String getData(LocalDate firstDateOfWeek);

}
