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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

import net.resheim.eclipse.timekeeper.db.DatabaseChangeListener;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.Label;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Workweek data consists of immutable service snapshots, never live JPA entities. */
public abstract class WeekViewContentProvider implements ITreeContentProvider, DatabaseChangeListener {
	public static final WeeklySummary WEEKLY_SUMMARY = new WeeklySummary();
	private LocalDate firstDayOfWeek;
	private boolean showAllTasks = true;
	private final ZoneId zoneId;
	protected Set<Task> filtered = Collections.emptySet();
	private Map<ProjectId, Project> projects = Map.of();
	private Map<TaskId, Task> tasks = Map.of();
	private Map<LabelId, Label> labels = Map.of();
	private List<Activity> activities = List.of();
	private Viewer viewer;
	private volatile Display viewerDisplay;

	protected WeekViewContentProvider() { this(TimekeeperUiPlugin.getCalendarZone()); }
	protected WeekViewContentProvider(ZoneId zoneId) { this.zoneId = zoneId; }
	ZoneId getZoneId() { return zoneId; }
	public Set<Task> getFiltered() { return filtered; }
	public void setShowAllTasks(boolean showAllTasks) { this.showAllTasks = showAllTasks; }
	public Optional<Task> task(TaskId id) { return Optional.ofNullable(tasks.get(id)); }
	public List<Label> labels(Activity activity) {
		return activity.labelIds().stream().map(labels::get).filter(java.util.Objects::nonNull).toList();
	}

	@Override public void dispose() {
		viewerDisplay = null;
		viewer = null;
		TimekeeperPlugin.getDefault().removeListener(this);
	}
	@Override public void inputChanged(Viewer v, Object oldInput, Object newInput) {
		viewer = v;
		viewerDisplay = v.getControl().getDisplay();
	}

	@Override public Object[] getChildren(Object parent) {
		if (parent instanceof Project project) {
			return filtered.stream().filter(task -> task.projectId().equals(Optional.of(project.id())))
					.filter(task -> task.parentTaskId().isEmpty()).toArray();
		}
		if (parent instanceof Task task) {
			return java.util.stream.Stream.concat(filtered.stream()
					.filter(child -> child.parentTaskId().equals(Optional.of(task.id()))),
					activities.stream().filter(activity -> activity.taskId().equals(task.id()))).toArray();
		}
		return new Object[0];
	}
	@Override public Object[] getElements(Object parent) {
		if (projects.isEmpty() && filtered.isEmpty()) return new Object[0];
		return java.util.stream.Stream.concat(java.util.stream.Stream.concat(projects.values().stream(),
				filtered.stream().filter(task -> task.projectId().isEmpty() && task.parentTaskId().isEmpty())),
				java.util.stream.Stream.of(WEEKLY_SUMMARY)).toArray();
	}
	@Override public Object getParent(Object element) {
		if (element instanceof Task task) return task.parentTaskId().map(tasks::get)
				.<Object>map(value -> value).orElseGet(() -> task.projectId().map(projects::get).orElse(null));
		if (element instanceof Activity activity) return tasks.get(activity.taskId());
		return null;
	}
	@Override public boolean hasChildren(Object element) {
		return element instanceof Project || element instanceof Task;
	}

	protected void filter() {
		if (firstDayOfWeek == null || !TimekeeperPlugin.getDefault().isReady()) {
			filtered = Set.of();
			projects = Map.of();
			tasks = Map.of();
			activities = List.of();
			return;
		}
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		projects = service.projects().stream().collect(Collectors.toMap(Project::id, Function.identity(),
				(a, b) -> a, LinkedHashMap::new));
		tasks = service.tasks().stream().collect(Collectors.toMap(Task::id, Function.identity()));
		labels = service.labels().stream().collect(Collectors.toMap(Label::id, Function.identity()));
		activities = service.activities(new ActivityQuery(firstDayOfWeek.atStartOfDay(zoneId).toInstant(),
				firstDayOfWeek.plusDays(7).atStartOfDay(zoneId).toInstant(), Optional.empty(), Optional.empty(),
				Optional.empty(), zoneId)).activities();
		Set<TaskId> visible = showAllTasks ? new java.util.HashSet<>(tasks.keySet())
				: activities.stream().map(Activity::taskId).collect(Collectors.toSet());
		for (TaskId id : List.copyOf(visible)) {
			Task task = tasks.get(id);
			Set<TaskId> visited = new java.util.HashSet<>();
			while (task != null && task.parentTaskId().isPresent() && visited.add(task.id())) {
				TaskId parent = task.parentTaskId().orElseThrow();
				visible.add(parent);
				task = tasks.get(parent);
			}
		}
		filtered = tasks.values().stream().filter(task -> visible.contains(task.id()))
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	public long seconds(Object element, LocalDate date) {
		Instant now = Instant.now();
		return activities.stream().filter(activity -> {
			if (element instanceof Activity selected) return activity.id().equals(selected.id());
			if (element instanceof Task task) return activity.taskId().equals(task.id());
			if (element instanceof Project project) return task(activity.taskId())
					.map(task -> task.projectId().equals(Optional.of(project.id()))).orElse(false);
			return element instanceof WeeklySummary;
		}).mapToLong(activity -> activity.duration(date, zoneId, now).getSeconds()).sum();
	}
	public boolean active(Object element) {
		return activities.stream().filter(activity -> activity.end().isEmpty()).anyMatch(activity -> {
			if (element instanceof Activity selected) return activity.id().equals(selected.id());
			if (element instanceof Task task) return activity.taskId().equals(task.id());
			if (element instanceof Project project) return task(activity.taskId())
					.map(task -> task.projectId().equals(Optional.of(project.id()))).orElse(false);
			return false;
		});
	}
	public LocalDate getFirstDayOfWeek() { return firstDayOfWeek; }
	public void setFirstDayOfWeek(LocalDate date) { firstDayOfWeek = date; }
	LocalDate getDate(int weekday) { return firstDayOfWeek.plusDays(weekday); }

	@Override public void databaseStateChanged() {
		Display display = viewerDisplay;
		if (display == null) return;
		try {
			display.asyncExec(new Runnable() {
				@Override public void run() {
					if (viewerDisplay != display || viewer == null || viewer.getControl().isDisposed()) return;
					if (viewer instanceof TreeViewer tree && tree.isCellEditorActive()) {
						display.timerExec(100, this);
						return;
					}
					filter();
					viewer.refresh();
					if (viewer instanceof TreeViewer tree) tree.expandAll();
				}
			});
		} catch (SWTException e) {
			if (e.code != SWT.ERROR_DEVICE_DISPOSED) throw e;
		}
	}
}
