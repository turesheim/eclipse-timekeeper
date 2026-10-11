/*******************************************************************************
 * Copyright (c) 2015-2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.views;

import org.eclipse.mylyn.commons.ui.CommonImages;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.ui.TaskElementLabelProvider;
import org.eclipse.mylyn.tasks.ui.TasksUiImages;
import org.eclipse.swt.graphics.Image;

import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/**
 * Provides decorations for the task and activity information column.
 */
class TitleColumnLabelProvider extends TimeColumnLabelProvider {

	public TitleColumnLabelProvider(WeekViewContentProvider contentProvider) {
		super(contentProvider);
	}

	private final TaskElementLabelProvider mylynLabels = new TaskElementLabelProvider();

	@Override
	public Image getImage(Object element) {
		if (element instanceof Activity) {
			return TimekeeperUiPlugin.getDefault().getImageRegistry().get(TimekeeperUiPlugin.OBJ_ACTIVITY);
		}
		if (element instanceof Project) {
			return CommonImages.getImage(TasksUiImages.CATEGORY);
		}
		if (element instanceof Task) {
			ITask linked = WorkWeekView.mylynTask((Task) element);
			return linked == null ? CommonImages.getImage(TasksUiImages.TASK) : mylynLabels.getImage(linked);
		}
		return null;
	}

	@Override
	public void dispose() {
		mylynLabels.dispose();
		super.dispose();
	}

	@Override
	public String getText(Object element) {
		if (element instanceof Project) {
			return ((Project) element).name();
		}
		if (element instanceof Task) {
			Task task = (Task) element;
			String externalId = task.externalReferences().stream()
					.map(reference -> reference.externalId()).sorted().findFirst().orElse(null);
			return externalId == null ? task.summary() : externalId + ": " + task.summary();
		}
		if (element instanceof Activity) {
			return ((Activity) element).summary();
		}
		if (element instanceof WeeklySummary) {
			return "Daily total";
		}
		return null;
	}

	@Override
	public String getToolTipText(Object element) {
		if (element instanceof Task task) {
			if (task.externalReferences().isEmpty()) return "Native Timekeeper task";
			String references = task.externalReferences().stream().map(reference -> reference.providerId()
					+ ": " + reference.externalId()).sorted().collect(java.util.stream.Collectors.joining("\n"));
			return WorkWeekView.mylynTask(task) == null ? references + "\nNo linked Mylyn task is available." : references;
		}
		if (element instanceof Activity) {
			Activity activity = (Activity) element;
			StringBuilder sb = new StringBuilder();
			sb.append("Started on ");
			sb.append(activity.start().atZone(getZoneId()).toLocalDateTime());
			if (activity.end().isPresent()) {
				sb.append(", ended on ");
				sb.append(activity.end().orElseThrow().atZone(getZoneId()).toLocalDateTime());
			}
			return sb.toString();
		}
		return super.getToolTipText(element);
	}

}
