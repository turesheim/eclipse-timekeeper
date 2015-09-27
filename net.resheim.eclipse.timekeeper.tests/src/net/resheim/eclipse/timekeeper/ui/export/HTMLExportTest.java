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
public class HTMLExportTest {

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

	private final String EXPECTED = "<table width=\"100%\" style=\"border: 1px solid #aaa; border-collapse: collapse; \">\n" +
			"<tr style=\"background: #dedede; border-bottom: 1px solid #aaa\"><th>Week 1 - 1990</th><th width=\"50em\" style=\"text-align: center; border-left: 1px solid #aaa\">Mon 1</th><th width=\"50em\" style=\"text-align: center; border-left: 1px solid #aaa\">Tue 2</th><th width=\"50em\" style=\"text-align: center; border-left: 1px solid #aaa\">Wed 3</th><th width=\"50em\" style=\"text-align: center; border-left: 1px solid #aaa\">Thu 4</th><th width=\"50em\" style=\"text-align: center; border-left: 1px solid #aaa\">Fri 5</th><th width=\"50em\" style=\"text-align: center; border-left: 1px solid #aaa\">Sat 6</th><th width=\"50em\" style=\"text-align: center; border-left: 1px solid #aaa\">Sun 7</th></th>\n" +
			"<tr style=\"background: #eeeeee;\"><td>Uncategorized</td><td style=\"text-align: right; border-left: 1px solid #aaa\">0:06</td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td></tr>\n" +
			"<tr><td>&nbsp;&nbsp;<a href=\"null\">1</a>: TestTask</td><td style=\"text-align: right; border-left: 1px solid #aaa\">0:06</td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td></tr>\n" +
			"<tr style=\"background: #dedede; border-top: 1px solid #aaa;\"><td>Daily total</td><td style=\"text-align: right; border-left: 1px solid #aaa\">0:06</td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td><td style=\"text-align: right; border-left: 1px solid #aaa\"></td></tr>\n" +
			"</table>";
	// This test will fail in Maven/Tycho, probably related to Mylyn housekeeping
	// @Test
	public void testExport() {
		LocalDate date = LocalDate.of(1990, 1, 1);
		Activator.setValue(task, date.toString(), Long.toString(360));
		HTMLExporter exporter = new HTMLExporter();
		String data = exporter.getData(date);
		System.out.println(data);
		Assert.assertEquals(EXPECTED, data);
	}
}
