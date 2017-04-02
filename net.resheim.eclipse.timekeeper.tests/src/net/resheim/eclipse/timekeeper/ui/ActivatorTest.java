/*******************************************************************************
 * Copyright (c) 2015 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;

@SuppressWarnings("restriction")
public class ActivatorTest {

	private LocalTask task;

	@Before
	public void before() {
		task = new LocalTask("1", "TestTask");
	}

	@After
	public void after() {
		try {
			TasksUi.getTaskDataManager().discardEdits(task);
		} catch (CoreException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testStartTime() {
		LocalDateTime expected = LocalDateTime.now();
		TasksUi.getTaskActivityManager().activateTask(task);
//		TrackedTask ttask = TimekeeperPlugin.getDefault().getTask(task);
//		LocalDateTime actual = ttask.getCurrentActivity().get().getStart();
//		// Accept only 500ms difference here
//		assertTrue(Duration.between(expected, actual).toMillis() < 500);
	}

	/**
	 * Verifies that the active time is correctly registered when a task has
	 * been activated.
	 */
	@Test
	@Ignore
	public void testActiveTime() {
		TasksUi.getTaskActivityManager().activateTask(task);
		LocalDateTime start = LocalDateTime.now();
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		LocalDateTime end = LocalDateTime.now();
		TasksUi.getTaskActivityManager().deactivateTask(task);
//		TrackedTask ttask = TimekeeperPlugin.getDefault().getTask(task);
//		Duration measured = Duration.between(start, end);
//		Duration actual = ttask.getCurrentActivity().get().getDuration();
//		assertEquals(measured, actual);
	}
}
