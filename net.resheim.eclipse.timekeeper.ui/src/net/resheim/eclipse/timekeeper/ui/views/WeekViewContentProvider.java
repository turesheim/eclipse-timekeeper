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

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;

import net.resheim.eclipse.timekeeper.db.DatabaseChangeListener;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.TrackedTask;

@SuppressWarnings("restriction")
public abstract class WeekViewContentProvider implements ITreeContentProvider, DatabaseChangeListener {

	public static final WeeklySummary WEEKLY_SUMMARY = new WeeklySummary();

	private LocalDate firstDayOfWeek;

	protected Set<TrackedTask> filtered = Collections.emptySet();

	private Viewer viewer;

	public Set<TrackedTask> getFiltered() {
		return filtered;
	}

	@Override
	public void dispose() {
		TimekeeperPlugin.getDefault().removeListener(this);
	}

	@Override
	public void inputChanged(Viewer v, Object oldInput, Object newInput) {
		this.viewer = v;
	}

	@Override
	public Object[] getChildren(Object parentElement) {
		if (parentElement instanceof Project) {
			Project p = (Project) parentElement;
			return filtered
					.stream()
					.filter(t -> p.equals(t.getProject()))
					.toArray(size -> new TrackedTask[size]);
		}
		if (parentElement instanceof TrackedTask) {
			return ((TrackedTask) parentElement).getActivities()
					.stream()
					.filter(this::hasData)
					.toArray();
		}
		return new Object[0];
	}

	public Object[] getElements(Object parent) {
		Object[] projects = filtered
				.stream()
				.map(TrackedTask::getProject)
				.filter(distinctByKey(Project::getName))
				.toArray();
		if (projects.length == 0) {
			return new Object[0];
		}
		Object[] elements = new Object[projects.length + 1];
		System.arraycopy(projects, 0, elements, 0, projects.length);
		elements[projects.length] = WEEKLY_SUMMARY;
		return elements;
	}

	private static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
		Map<Object, Boolean> map = new ConcurrentHashMap<>();
		return t -> map.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
	}

	@Override
	public Object getParent(Object element) {
		if (element instanceof ITask) {
			return TimekeeperPlugin.getMylynProjectName((ITask) element);
		}
		return null;
	}

	@Override
	public boolean hasChildren(Object element) {
		if (element instanceof TrackedTask) {
			return true;
		}
		if (element instanceof Project) {
			return true;
		}
		return false;
	}

	private boolean hasData(Activity activity) {
		LocalDate endDate = firstDayOfWeek.plusDays(7);
		return activity.getDuration(firstDayOfWeek, endDate) != Duration.ZERO;
	}

	protected void filter() {
		filtered = TimekeeperPlugin
				.getTasks(getFirstDayOfWeek())
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
		filter();
		if (viewer != null) {
			viewer.getControl().getDisplay().asyncExec(new Runnable() {
				@Override
				public void run() {
					ITask activeTask = TasksUiPlugin.getTaskActivityManager().getActiveTask();
					if (activeTask != null) {
						filtered.add(TimekeeperPlugin.getDefault().getTask(activeTask));
					}
					viewer.refresh();
					if (viewer instanceof TreeViewer) {
						((AbstractTreeViewer) viewer).expandAll();
					}
				}
			});
		}
	}

}
