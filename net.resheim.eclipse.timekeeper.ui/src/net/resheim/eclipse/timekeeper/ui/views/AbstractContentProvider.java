/*******************************************************************************
 * Copyright (c) 2014 Torkild U. Resheim
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
import java.util.List;
import java.util.stream.Collectors;

import net.resheim.eclipse.timekeeper.ui.Activator;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;

@SuppressWarnings("restriction")
public abstract class AbstractContentProvider implements ITreeContentProvider {

	protected List<ITask> filtered;
	private LocalDate firstDayOfWeek;

	public List<ITask> getFiltered() {
		return filtered;
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
		}
		return new Object[0];
	}

	public Object[] getElements(Object parent) {
		Object[] projects = filtered
				.stream()
				.collect(Collectors.groupingBy(t -> Activator.getProjectName(t)))
				.keySet().toArray();
		if (projects.length==0){
			return new Object[0];
		}
		Object[] elements = new Object[projects.length+1];
		System.arraycopy(projects, 0, elements, 0, projects.length);
		elements[projects.length] = new WeeklySummary();
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
		return false;
	}

	private boolean hasData(ITask task, LocalDate startDate) {
		int sum = 0;
		for (int i = 0; i < 7; i++) {
			LocalDate d = startDate.plusDays(i);
			sum += Activator.getActiveTime(task, d);
		}
		return sum > 0;
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
}
