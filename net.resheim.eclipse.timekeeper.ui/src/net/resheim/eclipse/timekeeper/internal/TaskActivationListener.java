/*******************************************************************************
 * Copyright (c) 2014 Torkild U. Resheim.
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.internal;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;

import net.resheim.eclipse.timekeeper.ui.Activator;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;
import org.eclipse.swt.widgets.Display;

public class TaskActivationListener implements ITaskActivationListener {

	HashMap<ITask, Long> activations;

	public TaskActivationListener() {
		activations = new HashMap<>();
	}

	@Override
	public void preTaskActivated(ITask task) {
		LocalDateTime now = LocalDateTime.now();
		String startString = Activator.getValue(task, Activator.START);
		if (startString != null) {
			LocalDateTime started = LocalDateTime.parse(startString);
			LocalDateTime stopped = LocalDateTime.now();
			long seconds = started.until(stopped, ChronoUnit.SECONDS);
			String time = DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true);
			// XXX: This can be a bit confusing
			boolean confirm = MessageDialog.openConfirm(Display.getCurrent().getActiveShell(),
					"Add elapsed time?",
					"Work was already started on this task on "
							+ started.format(DateTimeFormatter.ofPattern("EEE e, HH:mm", Locale.US))
							+ ". Continue and add the elapsed time since (" + time
							+ ") to the task total?");
			if (confirm) {
				accumulateTime(task, startString);
			}
		}
		Activator.setValue(task, Activator.START, now.toString());
	}

	@Override
	public void preTaskDeactivated(ITask task) {
		String startString = Activator.getValue(task, Activator.START);
		if (startString != null) {
			accumulateTime(task, startString);
		}
		Activator.clearValue(task, Activator.START);
	}

	private void accumulateTime(ITask task, String startString) {
		LocalDateTime started = LocalDateTime.parse(startString);
		LocalDateTime stopped = LocalDateTime.now();
		long seconds = started.until(stopped, ChronoUnit.SECONDS);
		// Store the elapsed time at the date
		String time = started.toLocalDate().toString();
		String accumulatedString = Activator.getValue(task, time);
		if (accumulatedString != null) {
			long accumulated = Long.parseLong(accumulatedString);
			accumulated = accumulated + seconds;
			Activator.setValue(task, time, Long.toString(accumulated));
		} else {
			Activator.setValue(task, time, Long.toString(seconds));
		}
	}

	@Override
	public void taskActivated(ITask task) {
		// Do nothing
	}

	@Override
	public void taskDeactivated(ITask task) {
		// Do nothing
	}
}
