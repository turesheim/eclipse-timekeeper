/*******************************************************************************
 * Copyright (c) 2014-2020 Torkild U. Resheim.
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

import java.util.HashMap;

import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;

public class TaskActivationListener implements ITaskActivationListener {

	HashMap<ITask, Long> activations;

	public TaskActivationListener() {
		activations = new HashMap<>();
	}

	@Override
	public void preTaskActivated(ITask task) {
		TimekeeperPlugin.getDefault().startMylynTask(task);
	}

	@Override
	public void preTaskDeactivated(ITask task) {
		TimekeeperPlugin.getDefault().endMylynTask(task);
	}

	@Override
	public void taskActivated(ITask task) {
		// no need to do anything here
	}

	@Override
	public void taskDeactivated(ITask task) {
		// no need to do anything here
	}
}
