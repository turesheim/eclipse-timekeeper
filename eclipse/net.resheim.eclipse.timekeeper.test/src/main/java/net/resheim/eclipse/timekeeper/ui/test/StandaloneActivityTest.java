/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteProject;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteTask;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.mylyn.MylynTaskSynchronizer;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

/** Exercises the real Workweek controls while no Mylyn projection exists. */
public class StandaloneActivityTest {
	@Test
	public void recordEditStartStopAndDeleteWithoutMylynTask() throws Exception {
		SWTWorkbenchBot bot = new SWTWorkbenchBot();
		bot.waitUntil(new DefaultCondition() {
			@Override public boolean test() { return TimekeeperPlugin.getDefault().isReady(); }
			@Override public String getFailureMessage() { return "Database did not become ready"; }
		}, 30000);
		onUi(() -> {
			try {
				var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				window.getShell().setSize(1100, 700);
				window.getShell().forceActive();
				window.getActivePage().showView(WorkWeekView.VIEW_ID);
			}
			catch (Exception failure) { throw new AssertionError(failure); }
		});
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		MylynTaskSynchronizer synchronizer = net.resheim.eclipse.timekeeper.mylyn.MylynIntegration.getDefault().synchronizer();
		ITask[] previous = new ITask[1];
		onUi(() -> {
			synchronizer.stop();
			previous[0] = TasksUi.getTaskActivityManager().getActiveTask();
			if (previous[0] != null) TasksUi.getTaskActivityManager().deactivateTask(previous[0]);
		});
		var project = service.createProject(new CreateProject("Standalone activity checks"));
		var task = service.createTask(new CreateTask(Optional.of(project.id()), "Unlinked task", Optional.empty()));
		LocalDate today = LocalDate.now(TimekeeperUiPlugin.getCalendarZone());
		// Keep the complete editable interval in the past, even on early first-day CI runs.
		LocalDate firstDay = today.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1).minusWeeks(1);
		String date = DateTimeFormatter.ISO_LOCAL_DATE.format(firstDay);
		try {
			bot.viewByTitle("Workweek").setFocus();
			bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
			bot.viewByTitle("Workweek").toolbarButton("Show previous week").click();
			bot.waitUntil(new DefaultCondition() {
				@Override public boolean test() {
					try { return bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary()) != null; }
					catch (RuntimeException failure) { return false; }
				}
				@Override public String getFailureMessage() { return "Unlinked task did not appear"; }
			});
			assertTrue(org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getTaskList().getAllTasks().stream()
					.noneMatch(value -> task.id().value().toString().equals(value.getAttribute(net.resheim.eclipse.timekeeper.mylyn.MylynMetadata.ATTR_TIMEKEEPER_TASK_ID))));
			bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary())
					.contextMenu("New activity").click();
			bot.shell("New activity").activate();
			bot.textWithLabel("Summary").setText("Recorded without Mylyn");
			bot.textWithLabel("Start (yyyy-MM-dd HH:mm)").setText(date + " 09:00");
			bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(date + " 10:00");
			var label = service.labels().get(0);
			bot.tableWithId("activity-labels").getTableItem(label.name()).check();
			var screenshots = java.nio.file.Path.of(System.getProperty("screenshots", System.getProperty("java.io.tmpdir")));
			java.nio.file.Files.createDirectories(screenshots);
			onUi(() -> TestUtility.takeScreenshot(screenshots.toFile(),
					bot.shell("New activity").widget, "standalone-activity-dialog.png"));
			bot.shell("New activity").bot().button("Save").click();
			bot.waitUntil(new DefaultCondition() {
				@Override public boolean test() { return recorded(service, task.id(), firstDay).isPresent(); }
				@Override public String getFailureMessage() { return "Recorded activity was not saved"; }
			});
			Activity created = recorded(service, task.id(), firstDay).orElseThrow();
			assertEquals(firstDay.atTime(9, 0).atZone(TimekeeperUiPlugin.getCalendarZone()).toInstant(), created.start());
			assertTrue(created.labelIds().contains(label.id()));
			assertTrue(created.manual());
			bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary())
					.expand().getNode(created.summary()).contextMenu("Edit activity...").click();
			bot.shell("Edit activity").activate();
			bot.textWithLabel("Summary").setText("Edited without Mylyn");
			bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(date + " 08:00");
			bot.button("Save").click();
			assertTrue(bot.shell("Edit activity").isOpen());
			assertEquals(created, service.activity(created.id()).orElseThrow());
			var concurrent = service.updateActivity(new net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity(
					created.id(), created.version(), created.start(), created.end(), "Concurrent edit", created.labelIds()));
			bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(date + " 11:00");
			bot.button("Save").click();
			assertTrue(bot.shell("Edit activity").isOpen());
			assertEquals(concurrent, service.activity(created.id()).orElseThrow());
			bot.button("Cancel").click();
			bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary())
					.expand().getNode(concurrent.summary()).contextMenu("Edit activity...").click();
			bot.shell("Edit activity").activate();
			bot.textWithLabel("Summary").setText("Edited without Mylyn");
			bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(date + " 11:00");
			bot.button("Save").click();
			Activity edited = service.activity(created.id()).orElseThrow();
			assertEquals("Edited without Mylyn", edited.summary());
			assertEquals(7200, service.activities(query(task.id(), firstDay)).total().getSeconds());
			var precise = service.updateActivity(new net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity(
					edited.id(), edited.version(), edited.start().plusNanos(123456789),
					Optional.of(edited.end().orElseThrow().plusSeconds(17).plusNanos(987654321)),
					"Precise interval", edited.labelIds()));
			bot.waitUntil(new DefaultCondition() {
				@Override public boolean test() {
					try { return bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary())
							.expand().getNode(precise.summary()) != null; }
					catch (RuntimeException failure) { return false; }
				}
				@Override public String getFailureMessage() { return "Precise interval did not appear"; }
			});
			bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary())
					.getNode(precise.summary()).contextMenu("Edit activity...").click();
			bot.shell("Edit activity").activate();
			bot.textWithLabel("Summary").setText("Edited without Mylyn");
			bot.button("Save").click();
			edited = service.activity(created.id()).orElseThrow();
			assertEquals(precise.start(), edited.start());
			assertEquals(precise.end(), edited.end());
			var exported = java.nio.file.Files.createTempDirectory("timekeeper-standalone-csv-");
			TimekeeperPlugin.getDefault().exportTo(exported);
			String csv = java.nio.file.Files.readString(exported.resolve("activity.csv"));
			assertTrue(csv.contains(edited.id().value().toString()));
			assertTrue(csv.contains("Edited without Mylyn"));
			bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary())
					.expand().getNode(edited.summary()).contextMenu("Delete activity").click();
			assertTrue(service.activity(created.id()).isEmpty());
			bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
			bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary())
					.contextMenu("Activate task").click();
			Activity ongoing = service.activeActivity(OwnerId.LOCAL).orElseThrow();
			assertEquals(task.id(), ongoing.taskId());
			bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary())
					.expand().getItems()[0].contextMenu("Stop activity").click();
			assertTrue(service.activity(ongoing.id()).orElseThrow().end().isPresent());
			service.deleteActivity(new net.resheim.eclipse.timekeeper.service.Commands.DeleteActivity(ongoing.id(),
					service.activity(ongoing.id()).orElseThrow().version()));
		} finally {
			// Restore the production adapter and Mylyn state for the remaining suites.
			service.activeActivity(OwnerId.LOCAL).filter(value -> value.taskId().equals(task.id())).ifPresent(value ->
					service.deleteActivity(new net.resheim.eclipse.timekeeper.service.Commands.DeleteActivity(value.id(), value.version())));
			for (Activity value : service.activities(query(task.id(), firstDay)).activities()) {
				service.deleteActivity(new net.resheim.eclipse.timekeeper.service.Commands.DeleteActivity(value.id(), value.version()));
			}
			service.deleteTask(new DeleteTask(task.id(), service.task(task.id()).orElseThrow().version()));
			service.deleteProject(new DeleteProject(project.id(), service.project(project.id()).orElseThrow().version()));
			onUi(() -> {
				synchronizer.start();
				if (previous[0] != null) TasksUi.getTaskActivityManager().activateTask(previous[0]);
			});
		}
	}
	private static Optional<Activity> recorded(net.resheim.eclipse.timekeeper.service.TimekeeperService service,
			net.resheim.eclipse.timekeeper.domain.TaskId task, LocalDate firstDay) {
		return service.activities(query(task, firstDay)).activities().stream().findFirst();
	}
	private static ActivityQuery query(net.resheim.eclipse.timekeeper.domain.TaskId task, LocalDate firstDay) {
		var zone = TimekeeperUiPlugin.getCalendarZone();
		return new ActivityQuery(firstDay.atStartOfDay(zone).toInstant(), firstDay.plusDays(7).atStartOfDay(zone).toInstant(),
				Optional.empty(), Optional.of(task), Optional.empty(), zone);
	}
	private static void onUi(Runnable action) throws Exception {
		FutureTask<Void> task = new FutureTask<>(action, null);
		PlatformUI.getWorkbench().getDisplay().syncExec(task);
		task.get(5, TimeUnit.SECONDS);
	}
}
