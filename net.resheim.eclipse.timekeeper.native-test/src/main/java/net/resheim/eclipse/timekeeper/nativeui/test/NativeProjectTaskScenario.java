/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.nativeui.test;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.eclipse.swtbot.swt.finder.junit.SWTBotJunit4ClassRunner;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

/**
 * Runs native CRUD in a separate Eclipse runtime where no Mylyn bundles are
 * installed.
 */
@RunWith(SWTBotJunit4ClassRunner.class)
public abstract class NativeProjectTaskScenario {
	@Test
	public void createEditAndDeleteNativeHierarchyThroughWorkweek() throws Exception {
		SWTWorkbenchBot bot = new SWTWorkbenchBot();
		await(bot, () -> TimekeeperPlugin.getDefault().isReady(), "Database is not ready");
		showWorkweek();
		var preferences = TimekeeperUiPlugin.getDefault().getPreferenceStore();
		assertFalse(preferences.getDefaultBoolean(PreferenceConstants.PROJECT_NATIVE_TASKS));
		assertFalse(preferences.getBoolean(PreferenceConstants.PROJECT_NATIVE_TASKS));
		TimekeeperService service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		String name = "Standalone UI project";
		Project[] createdProject = new Project[1];
		var createdTasks = new java.util.ArrayList<net.resheim.eclipse.timekeeper.domain.TaskId>();
		try {
			bot.viewByTitle("Workweek").setFocus();
			bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
			bot.viewByTitle("Workweek").toolbarButton("Create a Timekeeper project").click();
			bot.shell("New project").activate();
			bot.button("OK").click();
			assertTrue(bot.shell("New project").isOpen());
			assertTrue(service.projects().stream().noneMatch(value -> value.name().equals(name)));
			bot.textWithLabel("Name").setText(name);
			bot.button("OK").click();
			createdProject[0] = service.projects().stream().filter(value -> value.name().equals(name)).findFirst()
					.orElseThrow();
			var project = createdProject[0];
			bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(name)
					.contextMenu("Edit project...").click();
			bot.shell("Edit project").activate();
			var concurrentProject = service
					.updateProject(new UpdateProject(project.id(), project.version(), "Concurrent project edit"));
			bot.textWithLabel("Name").setText("Stale project edit");
			bot.button("OK").click();
			assertTrue(bot.shell("Edit project").isOpen());
			assertEquals(concurrentProject, service.project(project.id()).orElseThrow());
			bot.button("Cancel").click();
			await(bot, () -> rowExists(bot, "Concurrent project edit"), "Concurrent project rename is not displayed");
			bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem("Concurrent project edit")
					.contextMenu("Edit project...").click();
			bot.shell("Edit project").activate();
			bot.textWithLabel("Name").setText(name);
			bot.button("OK").click();
			bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(name)
					.contextMenu("New task...").click();
			bot.shell("New task").activate();
			assertEquals(name, bot.comboBoxWithId("task-project").selection());
			bot.textWithLabel("Summary").setText("Standalone parent");
			bot.textWithLabel("URL (optional)").setText("https://example.test/native");
			screenshot(bot, "New task", "standalone-task-dialog.png");
			bot.button("OK").click();
			Task parent = task(service, "Standalone parent");
			createdTasks.add(parent.id());
			assertEquals(Optional.of(project.id()), parent.projectId());
			assertEquals(Optional.of("https://example.test/native"), parent.url());
			assertTrue(parent.externalReferences().isEmpty());
			assertUnprojected(project, parent);
			parentRow(bot, name, parent.summary()).contextMenu("New subtask...").click();
			bot.shell("New task").activate();
			assertEquals(parent.summary(), bot.comboBoxWithId("task-parent").selection());
			bot.textWithLabel("Summary").setText("Standalone child");
			bot.button("OK").click();
			Task child = task(service, "Standalone child");
			createdTasks.add(child.id());
			assertEquals(Optional.of(parent.id()), child.parentTaskId());
			assertUnprojected(project, parent, child);
			parentRow(bot, name, parent.summary()).expand().getNode(child.summary()).contextMenu("Edit task...")
					.click();
			bot.shell("Edit task").activate();
			bot.comboBoxWithId("task-project").setSelection("No project");
			assertEquals("Top-level task", bot.comboBoxWithId("task-parent").selection());
			var editor = bot.shell("Edit task");
			bot.button("OK").click();
			bot.waitUntil(org.eclipse.swtbot.eclipse.finder.waits.Conditions.shellCloses(editor));
			bot.viewByTitle("Workweek").setFocus();
			assertTrue(service.task(child.id()).orElseThrow().projectId().isEmpty());
			assertTrue(service.task(child.id()).orElseThrow().parentTaskId().isEmpty());
			bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(child.summary())
					.contextMenu("Edit task...").click();
			bot.shell("Edit task").activate();
			bot.comboBoxWithId("task-project").setSelection(name);
			bot.comboBoxWithId("task-parent").setSelection(parent.summary());
			bot.button("OK").click();
			assertEquals(Optional.of(parent.id()), service.task(child.id()).orElseThrow().parentTaskId());
			parentRow(bot, name, parent.summary()).contextMenu("Edit task...").click();
			bot.shell("Edit task").activate();
			assertEquals(1, bot.comboBoxWithId("task-parent").items().length); // self and descendants excluded
			bot.textWithLabel("Summary").setText("");
			bot.button("OK").click();
			assertTrue(bot.shell("Edit task").isOpen());
			assertEquals(parent, service.task(parent.id()).orElseThrow());
			var concurrentTask = service.updateTask(new UpdateTask(parent.id(), parent.version(), parent.projectId(),
					parent.parentTaskId(), "Concurrent task edit", parent.url()));
			bot.textWithLabel("Summary").setText("Stale task edit");
			bot.button("OK").click();
			assertTrue(bot.shell("Edit task").isOpen());
			assertEquals(concurrentTask, service.task(parent.id()).orElseThrow());
			bot.button("Cancel").click();
			await(bot, () -> taskRowExists(bot, name, "Concurrent task edit"),
					"Concurrent task rename is not displayed");
			parentRow(bot, name, "Concurrent task edit").contextMenu("Edit task...").click();
			bot.shell("Edit task").activate();
			bot.textWithLabel("Summary").setText(parent.summary());
			bot.textWithLabel("URL (optional)").setText("https://example.test/renamed");
			bot.button("OK").click();
			assertEquals(Optional.of("https://example.test/renamed"), service.task(parent.id()).orElseThrow().url());
			parentRow(bot, name, parent.summary()).contextMenu("Delete task...").click();
			confirm(bot, "Delete task");
			confirm(bot, "Timekeeper");
			assertTrue(service.task(parent.id()).isPresent());
			assertEquals(Optional.of(parent.id()), service.task(child.id()).orElseThrow().parentTaskId());
			bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(name)
					.contextMenu("Delete project...").click();
			confirm(bot, "Delete project");
			confirm(bot, "Timekeeper");
			assertTrue(service.project(project.id()).isPresent());
			// A completed interval is created using only the native UI.
			parentRow(bot, name, parent.summary()).contextMenu("New activity").click();
			bot.shell("New activity").activate();
			bot.textWithLabel("Summary").setText("Native UI recorded interval");
			LocalDate monday = LocalDate.now(TimekeeperUiPlugin.getCalendarZone())
					.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
			bot.textWithLabel("Start (yyyy-MM-dd HH:mm)").setText(monday + " 09:00");
			bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(monday + " 10:00");
			bot.button("Save").click();
			assertEquals(3600, service.activities(query(parent)).total().getSeconds());
			assertUnprojected(project, parent, child);
			var exported = Files.createTempDirectory("timekeeper-native-ui-");
			TimekeeperPlugin.getDefault().exportTo(exported);
			assertTrue(Files.readString(exported.resolve("project.csv")).contains(name));
			assertTrue(Files.readString(exported.resolve("trackedtask.csv")).contains(parent.summary()));
			assertTrue(Files.readString(exported.resolve("activity.csv")).contains("Native UI recorded interval"));
			screenshot(bot, "", "standalone-workweek.png");
			parentRow(bot, name, parent.summary()).expand().getNode(child.summary()).contextMenu("Delete task...")
					.click();
			confirm(bot, "Delete task");
			assertTrue(service.task(child.id()).isEmpty());
			parentRow(bot, name, parent.summary()).contextMenu("Delete task...").click();
			confirm(bot, "Delete task");
			confirm(bot, "Timekeeper");
			assertTrue(service.task(parent.id()).isPresent());
			parentRow(bot, name, parent.summary()).expand().getNode("Native UI recorded interval")
					.contextMenu("Delete activity").click();
			parentRow(bot, name, parent.summary()).contextMenu("Delete task...").click();
			confirm(bot, "Delete task");
			assertTrue(service.task(parent.id()).isEmpty());
			bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(name)
					.contextMenu("Delete project...").click();
			confirm(bot, "Delete project");
			assertTrue(service.project(project.id()).isEmpty());
		} finally {
			if (createdProject[0] != null)
				cleanup(service, createdProject[0], createdTasks);
		}
	}

	@Test
	public void editTaskWithUnavailableExternalReferencePreservesIdentityAndHistory() throws Exception {
		SWTWorkbenchBot bot = new SWTWorkbenchBot();
		await(bot, () -> TimekeeperPlugin.getDefault().isReady(), "Database is not ready");
		showWorkweek();
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var project = service.createProject(new CreateProject("Unavailable provider project"));
		var task = service
				.createTask(new CreateTask(Optional.of(project.id()), "Unavailable provider task", Optional.empty()));
		var reference = new net.resheim.eclipse.timekeeper.domain.ExternalTaskReference("jira",
				"https://example.test/issues", "TK-213", "https://example.test/TK-213");
		task = service.linkExternalReference(new LinkExternalReference(task.id(), task.version(), reference));
		LocalDate monday = LocalDate.now(TimekeeperUiPlugin.getCalendarZone())
				.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
		var start = monday.atTime(9, 0).atZone(TimekeeperUiPlugin.getCalendarZone()).toInstant();
		var activity = service.createActivity(
				new CreateActivity(task.id(), net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL, start,
						Optional.of(start.plusSeconds(3600)), "History with a missing provider", java.util.Set.of()));
		try {
			bot.viewByTitle("Workweek").setFocus();
			bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
			parentRow(bot, project.name(), "TK-213: " + task.summary()).contextMenu("Edit task...").click();
			var editor = bot.shell("Edit task");
			editor.activate();
			assertTrue(bot.textWithLabel("External links").getText().contains("jira: TK-213"));
			bot.textWithLabel("Summary").setText("Edited unavailable task");
			bot.textWithLabel("URL (optional)").setText("https://example.test/native-url");
			bot.button("OK").click();
			bot.waitUntil(org.eclipse.swtbot.eclipse.finder.waits.Conditions.shellCloses(editor));
			var edited = service.task(task.id()).orElseThrow();
			assertEquals("Edited unavailable task", edited.summary());
			assertEquals(Optional.of("https://example.test/native-url"), edited.url());
			assertEquals(java.util.Set.of(reference), edited.externalReferences());
			assertEquals(activity, service.activity(activity.id()).orElseThrow());
			assertUnprojected(project, edited);
		} finally {
			cleanup(service, project, java.util.List.of(task.id()));
		}
	}

	private static void cleanup(TimekeeperService service, Project project,
			java.util.List<net.resheim.eclipse.timekeeper.domain.TaskId> ids) {
		var tasks = ids.stream().map(service::task).flatMap(Optional::stream).toList();
		for (Task task : tasks)
			for (var activity : service.activities(query(task)).activities()) {
				service.deleteActivity(new DeleteActivity(activity.id(), activity.version()));
			}
		for (Task task : tasks)
			if (task.parentTaskId().isPresent()) {
				service.deleteTask(new DeleteTask(task.id(), service.task(task.id()).orElseThrow().version()));
			}
		for (Task task : tasks)
			service.task(task.id()).ifPresent(value -> service.deleteTask(new DeleteTask(value.id(), value.version())));
		service.project(project.id())
				.ifPresent(value -> service.deleteProject(new DeleteProject(value.id(), value.version())));
	}

	private static ActivityQuery query(Task task) {
		return new ActivityQuery(java.time.Instant.EPOCH, java.time.Instant.now().plusSeconds(86400), Optional.empty(),
				Optional.of(task.id()), Optional.empty(), TimekeeperUiPlugin.getCalendarZone());
	}

	private static Task task(TimekeeperService service, String summary) {
		return service.tasks().stream().filter(value -> value.summary().equals(summary)).findFirst().orElseThrow();
	}

	protected abstract void assertUnprojected(Project project, Task... tasks) throws Exception;

	private static org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem parentRow(SWTWorkbenchBot bot, String project,
			String summary) {
		return bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(project).expand()
				.getNode(summary);
	}

	private static boolean rowExists(SWTWorkbenchBot bot, String name) {
		try {
			return bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(name) != null;
		} catch (RuntimeException failure) {
			return false;
		}
	}

	private static boolean taskRowExists(SWTWorkbenchBot bot, String project, String summary) {
		try {
			return parentRow(bot, project, summary) != null;
		} catch (RuntimeException failure) {
			return false;
		}
	}

	private static void confirm(SWTWorkbenchBot bot, String title) {
		bot.shell(title).activate();
		bot.button("OK").click();
	}

	protected void screenshot(SWTWorkbenchBot bot, String title, String name) throws Exception {
		// The companion Mylyn-enabled runtime captures the same dialogs.
	}

	private static void await(SWTWorkbenchBot bot, BooleanSupplier check, String failure) {
		bot.waitUntil(new DefaultCondition() {
			@Override
			public boolean test() {
				return check.getAsBoolean();
			}

			@Override
			public String getFailureMessage() {
				return failure;
			}
		}, 30000);
	}

	private static void showWorkweek() throws Exception {
		onUi(() -> {
			try {
				var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				window.getShell().setSize(1100, 750);
				window.getShell().forceActive();
				window.getActivePage().showView(WorkWeekView.VIEW_ID);
			} catch (Exception failure) {
				throw new AssertionError(failure);
			}
		});
	}

	private static void onUi(Runnable action) throws Exception {
		FutureTask<Void> task = new FutureTask<>(action, null);
		PlatformUI.getWorkbench().getDisplay().syncExec(task);
		task.get(5, TimeUnit.SECONDS);
	}
}
