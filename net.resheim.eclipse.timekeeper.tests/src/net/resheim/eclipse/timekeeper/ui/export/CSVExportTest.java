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

package net.resheim.eclipse.timekeeper.ui.export;

import java.time.LocalDate;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import net.resheim.eclipse.timekeeper.ui.Activator;

@SuppressWarnings("restriction")
public class CSVExportTest {

	private LocalTask task;

	@Before
	public void before(){
		task = new LocalTask("1", "TestTask");
		// Acivate the task so that Mylyn will add it to it's inventory
		TasksUi.getTaskActivityManager().activateTask(task);
	}

	@After
	public void after(){
		try {
			TasksUi.getTaskDataManager().discardEdits(task);
		} catch (CoreException e) {
			e.printStackTrace();
		}
	}

	// This test will fail in Maven/Tycho, probably related to Mylyn housekeeping
	// @Test
	public void testExport() {
		LocalDate date = LocalDate.of(1969, 3, 14);
		Activator.setValue(task, date.toString(), Long.toString(360));
		CSVExporter exporter = new CSVExporter();
		String data = exporter.getData(date);
		Assert.assertEquals("\"1\";\"TestTask\";0,004;;;;;;\n", data);
	}

}
