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

package net.resheim.eclipse.timekeeper.ui.views;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.Display;

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;
import net.resheim.eclipse.timekeeper.ui.Activator;

/**
 * Provides label decorations for the columns containing time spent on each
 * task.
 */
abstract class TimeColumnLabelProvider extends ColumnLabelProvider {

	private final WeekViewContentProvider contentProvider;

	public TimeColumnLabelProvider(WeekViewContentProvider contentProvider) {
		this.contentProvider = contentProvider;
	}

	@Override
	public Color getBackground(Object element) {
		if (element instanceof WeeklySummary) {
			return Display.getCurrent().getSystemColor(SWT.COLOR_INFO_BACKGROUND);
		}
		return super.getBackground(element);
	}

	@Override
	public Font getFont(Object element) {
		if (element instanceof ITask) {
			if (((ITask) element).isActive()) {
				return JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT);
			}
		}
		if (element instanceof Activity) {
			TrackedTask trackedTask = ((Activity) element).getTrackedTask();
			ITask task =  trackedTask.getTask() == null ? 
					TimekeeperPlugin.getDefault().getTask(trackedTask) : trackedTask.getTask();
			if (task != null && task.isActive()) {
				if (trackedTask.getCurrentActivity().isPresent()
						&& trackedTask.getCurrentActivity().get().equals(element)) {
					return JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT);
				}
			}
		}
		if (element instanceof String) {
			String p = (String) element;
			if (contentProvider
					.getFiltered()
					.stream()
					.filter(t -> p.equals(Activator.getProjectName(t)))
					.anyMatch(t -> t.isActive())) {
				return JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT);
			}

		}
		return JFaceResources.getDialogFont();
	}

	@Override
	public String getToolTipText(Object element) {
		if (element instanceof Activity) {
			StringBuilder sb = new StringBuilder();
			sb.append("Started at ");
			LocalDateTime start = ((Activity) element).getStart();
			sb.append(start.format(DateTimeFormatter.ofPattern("HH:mm")));
			sb.append(" on the ");
			sb.append(start.format(DateTimeFormatter.ofPattern("d")));
			sb.append("th");
			LocalDateTime end = ((Activity) element).getEnd();
			if (end != null) {
				sb.append(", ended at ");
				sb.append(end.format(DateTimeFormatter.ofPattern("HH:mm")));
				// will not work if the activity lastet for a year
				if (start.getDayOfYear() != end.getDayOfYear()) {
					sb.append(" on the ");
					sb.append(end.format(DateTimeFormatter.ofPattern("d")));
					sb.append("th");
				}
			}
			return sb.toString();
		}
		return super.getToolTipText(element);
	}

}