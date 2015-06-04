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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;
import org.eclipse.swt.widgets.Display;

import net.resheim.eclipse.timekeeper.ui.Activator;

public class TaskActivationListener implements ITaskActivationListener {

	HashMap<ITask, Long> activations;

	public TaskActivationListener() {
		activations = new HashMap<>();
	}

	@Override
	public void preTaskActivated(ITask task) {
		LocalDateTime now = LocalDateTime.now();
		String startString = Activator.getValue(task, Activator.START);
		String tickString = Activator.getValue(task, Activator.TICK);
		if (startString != null) {
			LocalDateTime ticked = LocalDateTime.parse(tickString);
			LocalDateTime stopped = LocalDateTime.now();
			long seconds = ticked.until(stopped, ChronoUnit.SECONDS);
			String time = DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true);
			boolean confirm = MessageDialog.openConfirm(
					Display.getCurrent().getActiveShell(),
					"Add elapsed time?",
					"Work was already started and task was last updated on "
							+ ticked.format(DateTimeFormatter.ofPattern("EEE e, HH:mm", Locale.US))
							+ ". Continue and add the elapsed time since (" + time + ") to the task total?");
			if (confirm) {
				Activator.accumulateTime(task, startString, ticked.until(LocalDateTime.now(), ChronoUnit.MILLIS));
			}
		}
		Activator.setValue(task, Activator.TICK, now.toString());
		Activator.setValue(task, Activator.START, now.toString());
	}

	@Override
	public void preTaskDeactivated(ITask task) {
		Activator.clearValue(task, Activator.START);
		Activator.clearValue(task, Activator.TICK);
	}

	@Override
	public void taskActivated(ITask task) {
		// Do nothing
	}

	@Override
	public void taskDeactivated(ITask task) {
		Activator.accumulateRemainder(task, LocalDate.now());
		// Do nothing
	}
}
