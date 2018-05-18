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

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.tasks.core.ITask;

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;
import net.resheim.eclipse.timekeeper.db.report.AbstractExporter;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.views.WeekViewContentProvider;
import net.resheim.eclipse.timekeeper.ui.views.WeeklySummary;

@Deprecated
@SuppressWarnings("restriction")
public class HTMLExporter extends AbstractExporter {
	private static final DateTimeFormatter weekFormat = DateTimeFormatter.ofPattern("w - YYYY");
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

		sb.append("<table width=\"100%\" style=\"border: 1px solid #aaa; border-collapse: collapse; \">");
		sb.append(System.lineSeparator());
		sb.append("<tr style=\"background: #dedede; border-bottom: 1px solid #aaa\">");
		sb.append("<th>");
		sb.append("Week ");
		sb.append(firstDayOfWeek.format(weekFormat));
		sb.append("</th>");
		String[] headings = TimekeeperUiPlugin.getDefault().getHeadings(firstDayOfWeek);
		for (String heading : headings) {
			sb.append("<th width=\"50em\" style=\"text-align: center; border-left: 1px solid #aaa\">");
			sb.append(heading);
			sb.append("</th>");
		}
		sb.append("</th>");
		sb.append(System.lineSeparator());

		Object[] elements = provider.getElements(null);
		for (Object object : elements) {
			append(firstDayOfWeek, sb, object);
			getSubElements(firstDayOfWeek, sb, object);
		}

		sb.append("</table>");
		return sb.toString();
	}

	private void getSubElements(LocalDate firstDayOfWeek, StringBuilder sb, Object object) {
		Object[] subElements = provider.getChildren(object);
		for (Object subElement : subElements) {
			append(firstDayOfWeek, sb, subElement);
			getSubElements(firstDayOfWeek, sb, subElement);
		}
	}

	private void append(LocalDate firstDayOfWeek, StringBuilder sb, Object object) {
		if (object instanceof String) {
			sb.append("<tr style=\"background: #eeeeee;\"><td>");
			sb.append(object);
			for (int i = 0; i < 7; i++) {
				sb.append("</td><td style=\"text-align: right; border-left: 1px solid #aaa\">");
				LocalDate weekday = firstDayOfWeek.plusDays(i);
				long seconds = getSum(provider.getFiltered(), weekday, (String) object);
				if (seconds > 60) {
					sb.append(DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true));
				}
			}
			sb.append("</td></tr>");
		}
		if (object instanceof WeeklySummary) {
			sb.append("<tr style=\"background: #dedede; border-top: 1px solid #aaa;\"><td>");
			sb.append("Daily total");
			for (int i = 0; i < 7; i++) {
				sb.append("</td><td style=\"text-align: right; border-left: 1px solid #aaa\">");
				LocalDate weekday = firstDayOfWeek.plusDays(i);
				long seconds = getSum(provider.getFiltered(), weekday);
				if (seconds > 60) {
					sb.append(DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true));
				}
			}
			sb.append("</td></tr>");
		}
		if (object instanceof Activity) {
			sb.append("<tr><td>&nbsp;&nbsp;");
			Activity task = (Activity) object;
			sb.append(task.getSummary());
			sb.append("</td>");
			for (int i = 0; i < 7; i++) {
				sb.append("</td><td style=\"text-align: right; border-left: 1px solid #aaa\">");
				LocalDate weekday = firstDayOfWeek.plusDays(i);
				Duration duration = task.getDuration(weekday);
				long seconds = duration.getSeconds();
				if (seconds > 60) {
					sb.append(DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true));
				}
			}

		}
		if (object instanceof ITask) {
			sb.append("<tr><td>&nbsp;&nbsp;");
			AbstractTask task = (AbstractTask) object;
			String taskKey = task.getTaskId();
			if (taskKey != null) {
				sb.append("<a href=\"" + task.getUrl() + "\">");
				sb.append(taskKey);
				sb.append("</a>");
				sb.append(": ");
			}
			sb.append(task.getSummary());
			for (int i = 0; i < 7; i++) {
				sb.append("</td><td style=\"text-align: right; border-left: 1px solid #aaa\">");
				LocalDate weekday = firstDayOfWeek.plusDays(i);
				TrackedTask ttask = TimekeeperPlugin.getDefault().getTask(task);
				long seconds = ttask.getDuration(weekday).getSeconds();
				if (seconds > 60) {
					sb.append(DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true));
				}
			}
			sb.append("</td></tr>");
		}
		sb.append(System.lineSeparator());
	}
}
