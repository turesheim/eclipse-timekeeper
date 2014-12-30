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

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;

@SuppressWarnings("restriction")
public class ExportToClipboard {

	private boolean hasData(AbstractTask task, LocalDate startDate) {
		int sum = 0;
		for (int i = 0; i < 7; i++) {
			String ds = startDate.plusDays(i).toString();
			sum += Activator.getIntValue(task, ds);
		}
		return sum > 0;
	}


	public void exportAsHTML(LocalDate firstDayOfWeek) {
		StringBuilder sb = new StringBuilder();
		List<AbstractTask> tasks =
				TasksUiPlugin.getTaskList().getAllTasks()
				.stream()
				.filter(t -> t.getAttribute(Activator.ATTR_ID) != null)
				.filter(t -> hasData(t, firstDayOfWeek))
				.collect(Collectors.toList());
		sb.append("<table>");
		sb.append(System.lineSeparator());
		sb.append("<th style=\"background: #eeeeee\">");
		String[] headings = Activator.getDefault().getHeadings(firstDayOfWeek);
		for (String heading : headings) {
			sb.append("<td style=\"background: #eeeeee\">");
			sb.append(heading);
			sb.append("</td>");
		}
		sb.append("</th>");
		sb.append(System.lineSeparator());
		for (AbstractTask task : tasks) {
			sb.append("<tr><td>");
			sb.append("<a href=\"" + task.getUrl() + "\">");
			String taskKey = task.getTaskKey();
			if (taskKey != null) {
				sb.append(taskKey);
				sb.append(": ");
			}
			sb.append(task.getSummary());
			sb.append("</a>");
			for (int i = 0; i < 7; i++) {
				sb.append("</td><td>");
				String weekday = firstDayOfWeek.plusDays(i).toString();
				int seconds = Activator.getIntValue(task, weekday);
				if (seconds > 60) {
					sb.append(DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true));
				}
			}
			sb.append("</td></tr>");
			sb.append(System.lineSeparator());
		}
		sb.append("</table>");
		HTMLTransfer textTransfer = HTMLTransfer.getInstance();
		Clipboard clipboard = new Clipboard(Display.getCurrent());
		clipboard.setContents(new String[] { sb.toString() }, new Transfer[] { textTransfer });
		clipboard.dispose();
	}

}
