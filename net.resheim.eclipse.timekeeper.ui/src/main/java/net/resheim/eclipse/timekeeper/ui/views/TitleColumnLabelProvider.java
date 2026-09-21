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

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.Task;
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
			ITask linked = ((Task) element).getMylynTask();
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
			return ((Project) element).getName();
		}
		if (element instanceof Task) {
			Task task = (Task) element;
			StringBuilder sb = new StringBuilder();
			if (task.getTaskId() != null) {
				sb.append(task.getTaskId());
				sb.append(": ");
			}
			sb.append(task.getTaskSummary());
			return sb.toString();
		}
		if (element instanceof Activity) {
			return ((Activity) element).getSummary();
		}
		if (element instanceof WeeklySummary) {
			return "Daily total";
		}
		return null;
	}

	@Override
	public String getToolTipText(Object element) {
		if (element instanceof Activity) {
			StringBuilder sb = new StringBuilder();
			sb.append("Started on ");
			sb.append(((Activity) element).getStart());
			if (((Activity) element).getEnd() != null) {
				sb.append(", ended on ");
				sb.append(((Activity) element).getEnd());
			}
			return sb.toString();
		}
		return super.getToolTipText(element);
	}

}
