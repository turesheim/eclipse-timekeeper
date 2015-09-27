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
import org.junit.Test;

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
		LocalDateTime actual = Activator.getStartTime(task);
		// Accept only 500ms difference here
		assertTrue(Duration.between(expected, actual).toMillis() < 500);
	}

	/**
	 * Verifies that the active time is correctly registered when a task has
	 * been activated.
	 */
	@Test
	public void testActiveTime() {
		TasksUi.getTaskActivityManager().activateTask(task);
		long start = System.currentTimeMillis();
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		long end = System.currentTimeMillis();
		TasksUi.getTaskActivityManager().deactivateTask(task);
		int activeTime = Activator.getActiveTime(task, LocalDate.now());
		assertEquals(((end - start) / 1000), activeTime);
	}

	@Test
	public void testRemainder_Less() {
		LocalDate date = LocalDate.now();
		Activator.setRemainder(450);
		Activator.accumulateRemainder(task, date);
		int activeTime = Activator.getActiveTime(task, LocalDate.now());
		assertEquals(0, activeTime);
	}

	@Test
	public void testRemainder_More() {
		LocalDate date = LocalDate.now();
		Activator.setRemainder(550);
		Activator.accumulateRemainder(task, date);
		int activeTime = Activator.getActiveTime(task, LocalDate.now());
		assertEquals(1, activeTime);
	}
}
