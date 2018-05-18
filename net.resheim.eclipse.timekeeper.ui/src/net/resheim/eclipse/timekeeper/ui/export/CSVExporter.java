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

package net.resheim.eclipse.timekeeper.ui.export;

import java.text.NumberFormat;
import java.time.LocalDate;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.tasks.core.ITask;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;
import net.resheim.eclipse.timekeeper.db.report.AbstractExporter;
import net.resheim.eclipse.timekeeper.ui.views.WeekViewContentProvider;

@Deprecated
@SuppressWarnings("restriction")
public class CSVExporter extends AbstractExporter {

	private static final String SEPARATOR = ";";
	private WeekViewContentProvider provider;

	@Override
	public String getData(LocalDate firstDayOfWeek) {

		provider = new WeekViewContentProvider() {

			@Override
			public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
				this.setFirstDayOfWeek(firstDayOfWeek);
				filter();
			}

			@Override
			public void dispose() {
				// ignore
			}
		};

		provider.inputChanged(null, null, null);
		StringBuilder sb = new StringBuilder();

		Object[] elements = provider.getElements(null);
		for (Object object : elements) {
			append(firstDayOfWeek, sb, object);
		}

		return sb.toString();

	}

	private void append(LocalDate firstDayOfWeek, StringBuilder sb, Object object) {
		// Task
		if (object instanceof ITask) {
			AbstractTask task = (AbstractTask) object;
			String taskId = task.getTaskId();
			if (taskId != null) {
				sb.append("\"");
				sb.append(taskId);
				sb.append("\"");
			}
			sb.append(SEPARATOR);
			sb.append("\"");
			sb.append(task.getSummary());
			sb.append("\"");
			for (int i = 0; i < 7; i++) {
				sb.append(SEPARATOR);
				LocalDate weekday = firstDayOfWeek.plusDays(i);
				TrackedTask ttask = TimekeeperPlugin.getDefault().getTask(task);
				double seconds = ttask.getDuration(weekday).getSeconds();
				if (seconds > 60) {
					// Duration as ISO-8601 won't work, Numbers and Excel
					// expects fraction of a 24-hour period
					sb.append(NumberFormat.getInstance().format(seconds / 86400));
				}
			}
			sb.append(System.lineSeparator());
		}
		if (object instanceof String) {
			Object[] children = provider.getChildren(object);
			for (Object o : children) {
				append(firstDayOfWeek, sb, o);
			}
		}
	}
}
