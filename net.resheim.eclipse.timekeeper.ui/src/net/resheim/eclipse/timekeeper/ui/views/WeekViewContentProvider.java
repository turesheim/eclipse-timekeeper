/*******************************************************************************
 * Copyright (c) 2014-2017 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.views;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.DatabaseChangeListener;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;
import net.resheim.eclipse.timekeeper.ui.Activator;

@SuppressWarnings("restriction")
public abstract class WeekViewContentProvider implements ITreeContentProvider, DatabaseChangeListener {

	public static final WeeklySummary WEEKLY_SUMMARY = new WeeklySummary();

	private LocalDate firstDayOfWeek;

	protected List<ITask> filtered = Collections.emptyList();

	private Viewer viewer;

	public List<ITask> getFiltered() {
		return filtered;
	}

	public void dispose() {
		TimekeeperPlugin.getDefault().removeListener(this);
	}

	public void inputChanged(Viewer v, Object oldInput, Object newInput) {
		this.viewer = v;
	}

	@Override
	public Object[] getChildren(Object parentElement) {
		if (parentElement instanceof String) {
			String p = (String) parentElement;
			Object[] tasks = filtered
					.stream()
					.filter(t -> p.equals(Activator.getProjectName(t)))
					.toArray(size -> new ITask[size]);
			return tasks;
		} else if (parentElement instanceof ITask) {
			TrackedTask task = TimekeeperPlugin.getDefault().getTask((ITask) parentElement);
			return task.getActivities()
					.stream()
					.filter(a -> hasData(a))
					.toArray();
		}
		return new Object[0];
	}

	public Object[] getElements(Object parent) {
		Object[] projects = filtered
				.stream()
				.collect(Collectors.groupingBy(t -> Activator.getProjectName(t))).keySet()
				.toArray();
		if (projects.length == 0) {
			return new Object[0];
		}
		Object[] elements = new Object[projects.length + 1];
		System.arraycopy(projects, 0, elements, 0, projects.length);
		elements[projects.length] = WEEKLY_SUMMARY;
		return elements;
	}

	@Override
	public Object getParent(Object element) {
		if (element instanceof ITask) {
			return Activator.getProjectName((ITask) element);
		}
		return null;
	}

	@Override
	public boolean hasChildren(Object element) {
		// Projects are guaranteed to have tasks as children, tasks are
		// guaranteed to not have any children.
		if (element instanceof String) {
			return true;
		}
		if (element instanceof ITask) {
			return hasData((ITask) element, firstDayOfWeek);
		}
		return false;
	}

	private boolean hasData/* this week */(ITask task, LocalDate startDate) {
		LocalDate endDate = startDate.plusDays(7);
		TrackedTask ttask = TimekeeperPlugin.getDefault().getTask(task);
		// this will typically only be NULL if the database has not started yet.
		// See databaseStateChanged()
		if (ttask == null) {
			return false;
		}
		Stream<Activity> filter = ttask.getActivities().stream()
				.filter(a -> a.getDuration(startDate, endDate) != Duration.ZERO);
		return filter.count() > 0;
	}

	private boolean hasData(Activity activity) {
		LocalDate endDate = firstDayOfWeek.plusDays(7);
		return activity.getDuration(firstDayOfWeek, endDate) != Duration.ZERO;
	}

	protected void filter() {
		filtered = TasksUiPlugin.getTaskList().getAllTasks().stream()
				.filter(t -> hasData(t, getFirstDayOfWeek()) || t.isActive())
				.collect(Collectors.toList());
	}

	public LocalDate getFirstDayOfWeek() {
		return firstDayOfWeek;
	}

	public void setFirstDayOfWeek(LocalDate firstDayOfWeek) {
		this.firstDayOfWeek = firstDayOfWeek;
	}

	/**
	 * Returns a string representation of the date.
	 *
	 * @param weekday
	 * @return
	 */
	LocalDate getDate(int weekday) {
		return getFirstDayOfWeek().plusDays(weekday);
	}

	@Override
	public void databaseStateChanged() {
		filter();
		if (viewer != null) {
			viewer.getControl().getDisplay().asyncExec(new Runnable() {
				@Override
				public void run() {
					viewer.refresh();
				}
			});
		}
	}

}
