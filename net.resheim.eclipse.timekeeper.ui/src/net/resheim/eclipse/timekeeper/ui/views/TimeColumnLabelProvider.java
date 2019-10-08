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
import java.util.Optional;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;

/**
 * Provides label decorations for the columns containing time spent on each
 * task.
 */
abstract class TimeColumnLabelProvider extends ColumnLabelProvider {

	private final WeekViewContentProvider contentProvider;

	private final IThemeManager themeManager = PlatformUI.getWorkbench().getThemeManager();

	public TimeColumnLabelProvider(WeekViewContentProvider contentProvider) {
		this.contentProvider = contentProvider;
	}

	@Override
	public Color getBackground(Object element) {
		if (element instanceof WeeklySummary) {
			return themeManager.getCurrentTheme().getColorRegistry().get(JFacePreferences.INFORMATION_BACKGROUND_COLOR);
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
			ITask task = trackedTask.getTask() == null ? TimekeeperPlugin.getDefault().getTask(trackedTask)
					: trackedTask.getTask();
			if (task != null && task.isActive()) {
				if (trackedTask.getCurrentActivity().equals(Optional.of(element))) {
					return JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT);
				}
			}
		}
		if (element instanceof String) {
			String p = (String) element;
			if (contentProvider
					.getFiltered()
					.stream()
					.filter(t -> p.equals(TimekeeperPlugin.getProjectName(t)))
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