/*******************************************************************************
 * Copyright (c) 2014-2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.views;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

import net.resheim.eclipse.timekeeper.db.DatabaseChangeListener;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

public abstract class WeekViewContentProvider implements ITreeContentProvider, DatabaseChangeListener {

	public static final WeeklySummary WEEKLY_SUMMARY = new WeeklySummary();

	private LocalDate firstDayOfWeek;
	private final ZoneId zoneId;

	protected Set<Task> filtered = Collections.emptySet();

	private Viewer viewer;
	// Published on the UI thread; notification threads must not dereference the viewer.
	private volatile Display viewerDisplay;

	protected WeekViewContentProvider() {
		this(TimekeeperUiPlugin.getCalendarZone());
	}

	protected WeekViewContentProvider(ZoneId zoneId) {
		this.zoneId = zoneId;
	}

	ZoneId getZoneId() {
		return zoneId;
	}

	public Set<Task> getFiltered() {
		return filtered;
	}

	@Override
	public void dispose() {
		viewerDisplay = null;
		viewer = null;
		TimekeeperPlugin.getDefault().removeListener(this);
	}

	@Override
	public void inputChanged(Viewer v, Object oldInput, Object newInput) {
		this.viewer = v;
		viewerDisplay = v.getControl().getDisplay();
	}

	@Override
	public Object[] getChildren(Object parentElement) {
		if (parentElement instanceof Project) {
			Project p = (Project) parentElement;
			return filtered
					.stream()
					.filter(t -> p.equals(t.getProject()))
					.filter(t -> t.getParentTask() == null)
					.toArray(size -> new Task[size]);
		}
		if (parentElement instanceof Task) {
			Task task = (Task) parentElement;
			Object[] subtasks = filtered.stream()
					.filter(candidate -> task.equals(candidate.getParentTask()))
					.toArray();
			Object[] activities = task.getActivities()
					.stream()
					.filter(this::hasData)
					.toArray();
			Object[] children = new Object[subtasks.length + activities.length];
			System.arraycopy(subtasks, 0, children, 0, subtasks.length);
			System.arraycopy(activities, 0, children, subtasks.length, activities.length);
			return children;
		}
		return new Object[0];
	}

	public Object[] getElements(Object parent) {
		Object[] projects = java.util.stream.Stream.concat(filtered
				.stream()
				.map(Task::getProject), TimekeeperPlugin.getProjects()
						.filter(project -> project.getServiceId() != null))
				.filter(Objects::nonNull)
				.filter(distinctByKey(project -> project.getServiceId() == null
						? project.getName() : project.getServiceId()))
				.toArray();
		Object[] standaloneTasks = filtered.stream()
				.filter(task -> task.getProject() == null)
				.toArray();
		if (projects.length == 0 && standaloneTasks.length == 0) {
			return new Object[0];
		}
		Object[] elements = new Object[projects.length + standaloneTasks.length + 1];
		System.arraycopy(projects, 0, elements, 0, projects.length);
		System.arraycopy(standaloneTasks, 0, elements, projects.length, standaloneTasks.length);
		elements[elements.length - 1] = WEEKLY_SUMMARY;
		return elements;
	}

	private static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
		Map<Object, Boolean> map = new ConcurrentHashMap<>();
		return t -> map.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
	}

	@Override
	public Object getParent(Object element) {
		if (element instanceof Task) {
			Task task = (Task) element;
			return task.getParentTask() == null ? task.getProject() : task.getParentTask();
		}
		if (element instanceof Activity) {
			return ((Activity) element).getTrackedTask();
		}
		if (element instanceof ITask) {
			return TimekeeperPlugin.getMylynProjectName((ITask) element);
		}
		return null;
	}

	@Override
	public boolean hasChildren(Object element) {
		if (element instanceof Task) {
			return true;
		}
		if (element instanceof Project) {
			return true;
		}
		return false;
	}

	private boolean hasData(Activity activity) {
		LocalDate endDate = firstDayOfWeek.plusDays(7);
		return !activity.getDuration(firstDayOfWeek, endDate, zoneId).isZero();
	}

	protected void filter() {
		filtered = TimekeeperPlugin
				.getTasks(getFirstDayOfWeek(), zoneId)
				.collect(Collectors.toSet());
	}

	public LocalDate getFirstDayOfWeek() {
		return firstDayOfWeek;
	}

	public void setFirstDayOfWeek(LocalDate firstDayOfWeek) {
		this.firstDayOfWeek = firstDayOfWeek;
	}

	LocalDate getDate(int weekday) {
		return getFirstDayOfWeek().plusDays(weekday);
	}

	@Override
	public void databaseStateChanged() {
		Display display = viewerDisplay;
		if (display == null) {
			return;
		}
		try {
			display.asyncExec(new Runnable() {
				@Override
				public void run() {
					if (viewerDisplay != display || viewer == null || viewer.getControl().isDisposed()) {
						return;
					}
					filter();
					ITask activeTask = TasksUi.getTaskActivityManager().getActiveTask();
					if (activeTask != null) {
						Task tracked = TimekeeperPlugin.getDefault().getTask(activeTask);
						if (tracked != null) {
							filtered.add(tracked);
						}
					}
					viewer.refresh();
					if (viewer instanceof TreeViewer) {
						((AbstractTreeViewer) viewer).expandAll();
					}
				}
			});
		} catch (SWTException e) {
			// The display may shut down between capturing it and queueing the callback.
			if (e.code != SWT.ERROR_DEVICE_DISPOSED) {
				throw e;
			}
		}
	}

}
