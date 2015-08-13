/*******************************************************************************
 * Copyright (c) 2014-2015 Torkild U. Resheim.
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
import java.util.HashMap;

import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;

import net.resheim.eclipse.timekeeper.ui.Activator;

public class TaskActivationListener implements ITaskActivationListener {

	HashMap<ITask, Long> activations;

	public TaskActivationListener() {
		activations = new HashMap<>();
	}

	@Override
	public void preTaskActivated(ITask task) {
		LocalDateTime now = LocalDateTime.now();
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
