/*******************************************************************************
 * Copyright (c) 2015-2017 Torkild U. Resheim
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
import java.util.List;

import org.eclipse.mylyn.tasks.core.ITask;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;

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
	protected long getSum(List<ITask> tasks, LocalDate date, String project) {
		return tasks
				.stream()
				.filter(t -> project.equals(TimekeeperPlugin.getProjectName(t)))
				.map(t -> TimekeeperPlugin.getDefault().getTask(t))
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
	protected long getSum(List<ITask> tasks, LocalDate date) {
		return tasks
				.stream()
				.map(t -> TimekeeperPlugin.getDefault().getTask(t))
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