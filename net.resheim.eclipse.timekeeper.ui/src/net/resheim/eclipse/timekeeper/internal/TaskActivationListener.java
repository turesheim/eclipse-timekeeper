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
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

import net.resheim.eclipse.timekeeper.ui.Activator;

import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;

public class TaskActivationListener implements ITaskActivationListener {

	HashMap<ITask, Long> activations;

	public TaskActivationListener() {
		activations = new HashMap<>();
	}

	@Override
	public void preTaskActivated(ITask task) {
		LocalDateTime now = LocalDateTime.now();
		Activator.setValue(task, Activator.START, now.toString());
	}

	@Override
	public void preTaskDeactivated(ITask task) {
		String startString = Activator.getValue(task, Activator.START);
		if (startString != null) {
			LocalDateTime parse = LocalDateTime.parse(startString);
			LocalDateTime now = LocalDateTime.now();
			long seconds = parse.until(now, ChronoUnit.SECONDS);
			String time = parse.toLocalDate().toString();
			String accumulatedString = Activator.getValue(task, time);
			if (accumulatedString != null) {
				long accumulated = Long.parseLong(accumulatedString);
				accumulated = accumulated + seconds;
				Activator.setValue(task, time, Long.toString(accumulated));
			} else {
				Activator.setValue(task, time, Long.toString(seconds));
			}
		}
		Activator.clearValue(task, Activator.START);
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
