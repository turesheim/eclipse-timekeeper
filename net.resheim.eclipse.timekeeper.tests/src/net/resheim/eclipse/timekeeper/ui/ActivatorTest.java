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

import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.junit.BeforeClass;
import org.junit.Test;

@SuppressWarnings("restriction")
public class ActivatorTest {

	// private static SWTWorkbenchBot bot;

	@BeforeClass
	public static void setUp() {
		// bot = new SWTWorkbenchBot();
	}

	@Test
	public void testStartTime() {
		LocalTask task = new LocalTask("1", "testStartTime");
		LocalDateTime expected = LocalDateTime.now();
		TasksUi.getTaskActivityManager().activateTask(task);
		LocalDateTime actual = Activator.getStartTime(task);
		// Accept only 500ms difference here
		assertTrue(Duration.between(expected, actual).toMillis() < 500);
	}

	@Test
	public void testActiveTime() {
		LocalTask task = new LocalTask("2", "testActiveTime");
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
		LocalTask task = new LocalTask("3", "testRemainder");
		LocalDate date = LocalDate.now();
		Activator.setRemainder(450);
		Activator.accumulateRemainder(task, date);
		int activeTime = Activator.getActiveTime(task, LocalDate.now());
		assertEquals(0, activeTime);
	}

	@Test
	public void testRemainder_More() {
		LocalTask task = new LocalTask("4", "testRemainder");
		LocalDate date = LocalDate.now();
		Activator.setRemainder(550);
		Activator.accumulateRemainder(task, date);
		int activeTime = Activator.getActiveTime(task, LocalDate.now());
		assertEquals(1, activeTime);
	}
}
