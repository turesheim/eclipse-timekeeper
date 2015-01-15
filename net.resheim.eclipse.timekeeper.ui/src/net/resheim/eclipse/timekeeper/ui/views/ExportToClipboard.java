/*******************************************************************************
 * Copyright (c) 2014, 2015 Torkild U. Resheim
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
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.resheim.eclipse.timekeeper.ui.Activator;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;

@SuppressWarnings("restriction")
public class ExportToClipboard {

	private static final DateTimeFormatter weekFormat = DateTimeFormatter.ofPattern("w - YYYY");
	private AbstractContentProvider provider;

	public void copyTaskAsHTML(ITask task) {
		StringBuilder sb = new StringBuilder();
		String taskKey = task.getTaskKey();
		if (taskKey != null) {
			sb.append("<a href=\"" + task.getUrl() + "\">");
			sb.append(taskKey);
			sb.append("</a>");
			sb.append(": ");
		}
		sb.append(task.getSummary());
		HTMLTransfer textTransfer = HTMLTransfer.getInstance();
		TextTransfer tt = TextTransfer.getInstance();
		Clipboard clipboard = new Clipboard(Display.getCurrent());
		clipboard.setContents(new String[] { sb.toString(), sb.toString() }, new Transfer[] { textTransfer, tt });
		clipboard.dispose();
	}

	public void copyWeekAsHTML(LocalDate firstDayOfWeek) {

		provider = new AbstractContentProvider() {

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
		String[] headings = Activator.getDefault().getHeadings(firstDayOfWeek);
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
		}

		sb.append("</table>");
		HTMLTransfer textTransfer = HTMLTransfer.getInstance();
		TextTransfer tt = TextTransfer.getInstance();
		Clipboard clipboard = new Clipboard(Display.getCurrent());
		clipboard.setContents(new String[] { sb.toString(), sb.toString() }, new Transfer[] { textTransfer, tt });
		clipboard.dispose();
	}

	private void append(LocalDate firstDayOfWeek, StringBuilder sb, Object object) {
		if (object instanceof String) {
			sb.append("<tr style=\"background: #eeeeee;\"><td>");
			sb.append(object);
			for (int i = 0; i < 7; i++) {
				sb.append("</td><td style=\"text-align: right; border-left: 1px solid #aaa\">");
				LocalDate weekday = firstDayOfWeek.plusDays(i);
				int seconds = getSum(provider.getFiltered(), weekday, (String) object);
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
				int seconds = getSum(provider.getFiltered(), weekday);
				if (seconds > 60) {
					sb.append(DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true));
				}
			}
			sb.append("</td></tr>");
		}
		if (object instanceof ITask) {
			sb.append("<tr><td>&nbsp;&nbsp;");
			AbstractTask task = (AbstractTask) object;
			String taskKey = task.getTaskKey();
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
				int seconds = Activator.getActiveTime(task, weekday);
				if (seconds > 60) {
					sb.append(DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true));
				}
			}
			sb.append("</td></tr>");
		}
		sb.append(System.lineSeparator());
		if (object instanceof String) {
			Object[] children = provider.getChildren(object);
			for (Object o : children) {
				append(firstDayOfWeek, sb, o);
			}
		}
	}

	/**
	 * Calculates the total amount of seconds accumulated on the project for the
	 * specified date.
	 *
	 * @param date
	 *            the date to calculate for
	 * @param project
	 *            the project to calculate for
	 * @return the total amount of seconds accumulated
	 */
	private int getSum(List<ITask> filtered, LocalDate date, String project) {
		return filtered
				.stream()
				.filter(t -> project.equals(Activator.getProjectName(t)))
				.mapToInt(t -> Activator.getActiveTime(t, date)).sum();
	}

	/**
	 * Calculates the total amount of seconds accumulated on specified date.
	 *
	 * @param date
	 *            the date to calculate for
	 * @return the total amount of seconds accumulated
	 */
	private int getSum(List<ITask> filtered, LocalDate date) {
		return filtered
				.stream().mapToInt(t -> Activator.getActiveTime(t, date))
				.sum();
	}

}
