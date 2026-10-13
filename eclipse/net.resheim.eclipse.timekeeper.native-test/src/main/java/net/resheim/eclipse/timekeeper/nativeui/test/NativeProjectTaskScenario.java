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
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
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
	public void nativeTasksActivateAndSwitchWithoutActivityDialog() throws Exception {
		SWTWorkbenchBot bot = new SWTWorkbenchBot();
		await(bot, () -> TimekeeperPlugin.getDefault().isReady(), "Database is not ready");
		var preferences = TimekeeperUiPlugin.getDefault().getPreferenceStore();
		boolean previousShowAll = preferences.getBoolean(PreferenceConstants.SHOW_ALL_TASKS);
		onUi(() -> preferences.setValue(PreferenceConstants.SHOW_ALL_TASKS, true));
		reopenWorkweek();
		var view = bot.viewByTitle("Workweek");
		view.setFocus();
		view.toolbarButton("Show current week").click();
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var previous = service.activeActivity(net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL);
		var previousTask = previous.flatMap(activity -> service.task(activity.taskId()));
		var integration = TimekeeperUiPlugin.getDefault().getTaskIntegration();
		boolean previouslyLinkedActive = previousTask.filter(task -> integration
				.filter(value -> value.isActive(task)).isPresent()).isPresent();
		if (previouslyLinkedActive) onUi(() -> integration.orElseThrow().deactivate(previousTask.orElseThrow().id()));
		previous.flatMap(activity -> service.activity(activity.id())).filter(activity -> activity.end().isEmpty())
				.ifPresent(activity -> service.stopActivity(new StopActivity(activity.id(), activity.version())));
		var project = service.createProject(new CreateProject("Direct activation project"));
		var first = service.createTask(new CreateTask(Optional.of(project.id()), "First native activation", Optional.empty()));
		var second = service.createTask(new CreateTask(Optional.of(project.id()), "Second native activation", Optional.empty()));
		try {
			assertUnprojected(project, first, second);
			var before = java.time.Instant.now();
			parentRow(bot, project.name(), first.summary()).contextMenu("Activate task").click();
			var initial = service.activeActivity(net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL).orElseThrow();
			assertEquals(first.id(), initial.taskId());
			assertFalse(initial.start().isBefore(before.minusMillis(1)));
			assertFalse(initial.start().isAfter(java.time.Instant.now().plusMillis(1)));
			assertFalse(initial.manual());
			onUi(() -> {
				assertTrue(java.util.Arrays.stream(PlatformUI.getWorkbench().getDisplay().getShells())
						.noneMatch(shell -> shell.isVisible() && (shell.getText().equals("New activity")
								|| shell.getText().equals("Edit activity"))));
				assertTrue((parentRow(bot, project.name(), first.summary()).widget.getFont(0).getFontData()[0].getStyle() & SWT.BOLD) != 0);
			});
			parentRow(bot, project.name(), first.summary()).expand().getItems()[0].contextMenu("Edit activity...").click();
			bot.shell("Edit activity").activate();
			bot.textWithLabel("Summary").setText("Edited while tracking");
			bot.button("Save").click();
			var edited = service.activity(initial.id()).orElseThrow();
			assertEquals("Edited while tracking", edited.summary());
			assertEquals(initial.start(), edited.start());
			assertTrue(edited.end().isEmpty());
			parentRow(bot, project.name(), second.summary()).contextMenu("Activate task").click();
			var successor = service.activeActivity(net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL).orElseThrow();
			assertEquals(second.id(), successor.taskId());
			var closed = service.activity(initial.id()).orElseThrow();
			assertTrue(closed.end().isPresent());
			assertEquals(edited.summary(), closed.summary());
			view.toolbarButton("Show previous week").click();
			parentRow(bot, project.name(), second.summary()).contextMenu("Deactivate task").click();
			assertTrue(service.activeActivity(net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL).isEmpty());
			assertTrue(service.activity(successor.id()).orElseThrow().end().isPresent());
			view.toolbarButton("Show current week").click();
			// A task can become active after its menu was opened. Activation must
			// reuse that interval rather than reporting a conflict or duplicating it.
			var pendingActivation = parentRow(bot, project.name(), second.summary()).contextMenu("Activate task");
			var concurrent = service.startActivity(new StartActivity(second.id(),
					net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL, "Already activated", java.util.Set.of()));
			pendingActivation.click();
			assertEquals(concurrent, service.activeActivity(net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL).orElseThrow());
			parentRow(bot, project.name(), second.summary()).contextMenu("Deactivate task").click();
			parentRow(bot, project.name(), second.summary()).contextMenu("New activity").click();
			bot.shell("New activity").activate();
			bot.button("Cancel").click();
			assertEquals(2, service.activities(query(second)).activities().size());
		} finally {
			cleanup(service, project, java.util.List.of(first.id(), second.id()));
			previous.ifPresent(original -> service.activity(original.id()).ifPresent(current ->
					service.updateActivity(new UpdateActivity(current.id(), current.version(), original.start(),
							original.end(), original.summary(), original.labelIds()))));
			if (previouslyLinkedActive) onUi(() -> integration.orElseThrow().activate(previousTask.orElseThrow()));
			onUi(() -> preferences.setValue(PreferenceConstants.SHOW_ALL_TASKS, previousShowAll));
			reopenWorkweek();
			bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
		}
	}

	@Test
	public void workweekTaskFilterIncludesInactiveTasksAndPreservesTotals() throws Exception {
		SWTWorkbenchBot bot = new SWTWorkbenchBot();
		await(bot, () -> TimekeeperPlugin.getDefault().isReady(), "Database is not ready");
		var preferences = TimekeeperUiPlugin.getDefault().getPreferenceStore();
		boolean previous = preferences.getBoolean(PreferenceConstants.SHOW_ALL_TASKS);
		onUi(() -> preferences.setValue(PreferenceConstants.SHOW_ALL_TASKS, true));
		reopenWorkweek();
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var project = service.createProject(new CreateProject("Task filter project"));
		var parent = service.createTask(new CreateTask(Optional.of(project.id()), "Parent without direct time", Optional.empty()));
		var child = service.createTask(new CreateTask(Optional.of(project.id()), Optional.of(parent.id()),
				"Child with time this week", Optional.empty()));
		var idle = service.createTask(new CreateTask(Optional.of(project.id()), "Native without time", Optional.empty()));
		var linked = service.createTask(new CreateTask(Optional.of(project.id()), Optional.empty(),
				"Linked with old time", Optional.empty(), java.util.Set.of(
						new net.resheim.eclipse.timekeeper.domain.ExternalTaskReference("fixture", "https://example.test", "OLD-160", Optional.empty()))));
		var never = service.createTask(new CreateTask(Optional.of(project.id()), Optional.empty(),
				"Linked without time", Optional.empty(), java.util.Set.of(
						new net.resheim.eclipse.timekeeper.domain.ExternalTaskReference("fixture", "https://example.test", "NEW-160", Optional.empty()))));
		var unassigned = service.createTask(new CreateTask(Optional.empty(), "Unassigned without time", Optional.empty()));
		LocalDate firstDay = LocalDate.now(TimekeeperUiPlugin.getCalendarZone())
				.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
		var start = firstDay.atStartOfDay(TimekeeperUiPlugin.getCalendarZone()).toInstant();
		var current = service.createActivity(new CreateActivity(child.id(), net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL,
				start, Optional.of(start.plusSeconds(3600)), "This week's time", java.util.Set.of()));
		var oldStart = firstDay.minusWeeks(1).atTime(9, 0).atZone(TimekeeperUiPlugin.getCalendarZone()).toInstant();
		var history = service.createActivity(new CreateActivity(linked.id(), net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL,
				oldStart, Optional.of(oldStart.plusSeconds(3600)), "Last week's time", java.util.Set.of()));
		String toggle = "Show all tasks, including those without time in the displayed week";
		try {
			var view = bot.viewByTitle("Workweek");
			view.setFocus();
			view.toolbarButton("Show current week").click();
			assertTrue(preferences.getDefaultBoolean(PreferenceConstants.SHOW_ALL_TASKS));
			assertTrue(view.toolbarToggleButton(toggle).isChecked());
			assertTrue(taskRowExists(bot, project.name(), idle.summary()));
			assertTrue(taskRowExists(bot, project.name(), "OLD-160: " + linked.summary()));
			assertTrue(taskRowExists(bot, project.name(), "NEW-160: " + never.summary()));
			assertTrue(rowExists(bot, unassigned.summary()));
			assertEquals("1:00", view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).cell(1));
			view.toolbarToggleButton(toggle).deselect();
			await(bot, () -> !taskRowExists(bot, project.name(), idle.summary()), "Inactive native task is still displayed");
			assertFalse(taskRowExists(bot, project.name(), "OLD-160: " + linked.summary()));
			assertFalse(taskRowExists(bot, project.name(), "NEW-160: " + never.summary()));
			assertFalse(rowExists(bot, unassigned.summary()));
			var parentRow = parentRow(bot, project.name(), parent.summary()).expand();
			assertEquals(child.summary(), parentRow.getNode(child.summary()).getText());
			assertEquals("1:00", parentRow.getNode(child.summary()).cell(1));
			assertEquals("1:00", view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).cell(1));
			assertFalse(preferences.getBoolean(PreferenceConstants.SHOW_ALL_TASKS));
			reopenWorkweek();
			view = bot.viewByTitle("Workweek");
			assertFalse("The choice must survive view recreation", view.toolbarToggleButton(toggle).isChecked());
			assertFalse(taskRowExists(bot, project.name(), idle.summary()));
			view.toolbarButton("Show previous week").click();
			assertTrue(taskRowExists(bot, project.name(), "OLD-160: " + linked.summary()));
			assertFalse(taskRowExists(bot, project.name(), parent.summary()));
			assertEquals("1:00", view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).cell(1));
			view.toolbarToggleButton(toggle).select();
			assertTrue(taskRowExists(bot, project.name(), idle.summary()));
			assertTrue(taskRowExists(bot, project.name(), "NEW-160: " + never.summary()));
			view.toolbarButton("Show current week").click();
			assertEquals("1:00", view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).cell(1));
			assertEquals(current, service.activity(current.id()).orElseThrow());
			assertEquals(history, service.activity(history.id()).orElseThrow());
		} finally {
			cleanup(service, project, java.util.List.of(parent.id(), child.id(), idle.id(), linked.id(), never.id(), unassigned.id()));
			onUi(() -> preferences.setValue(PreferenceConstants.SHOW_ALL_TASKS, previous));
			reopenWorkweek();
		}
	}

	private static void reopenWorkweek() throws Exception {
		onUi(() -> {
			var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			var view = page.findView(WorkWeekView.VIEW_ID);
			if (view != null) page.hideView(view);
		});
		showWorkweek();
	}

	@Test
	public void labelImagesRemainVisibleWhenOtherOwnersClose() throws Exception {
		onUi(() -> {
			var label = new net.resheim.eclipse.timekeeper.domain.Label(
					new net.resheim.eclipse.timekeeper.domain.LabelId(java.util.UUID.randomUUID()),
					"Visible label", Optional.of("210,55,55"), 0);
			var viewPainter = new net.resheim.eclipse.timekeeper.ui.ActivityLabelPainter();
			var otherPainter = new net.resheim.eclipse.timekeeper.ui.ActivityLabelPainter();
			try {
				var viewImage = viewPainter.getLabelImage(label, 16, true);
				var data = viewImage.getImageData();
				assertTrue("The label center must be opaque", data.getAlpha(8, 9) > 0);
				assertEquals("The image corners must remain transparent", 0, data.getAlpha(0, 0));
				assertEquals(new org.eclipse.swt.graphics.RGB(210, 55, 55), data.palette.getRGB(data.getPixel(8, 9)));
				otherPainter.getLabelImage(label, 16, true);
				otherPainter.disposeImages();
				assertFalse("Another owner's disposal must not hide Workweek labels",
						viewPainter.getLabelImage(label, 16, true).isDisposed());
				assertSame(viewImage, viewPainter.getLabelImage(label, 16, true));
				assertEquals(24, viewPainter.getLabelImage(label, 24, true).getBounds().width);
				viewPainter.disposeImages();
				assertTrue(viewImage.isDisposed());
				assertFalse("A disposed cache must be safe to reuse", viewPainter.getLabelImage(label, 16, true).isDisposed());
			} finally {
				viewPainter.disposeImages();
				otherPainter.disposeImages();
			}
		});
	}

	@Test
	public void workweekDisplaysAssignedLabelsAfterMenuPreferencesAndReopen() throws Exception {
		SWTWorkbenchBot bot = new SWTWorkbenchBot();
		await(bot, () -> TimekeeperPlugin.getDefault().isReady(), "Database is not ready");
		showWorkweek();
		bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var project = service.createProject(new CreateProject("Label display project"));
		var task = service.createTask(new CreateTask(Optional.of(project.id()), "Label display task", Optional.empty()));
		var red = service.createLabel(new CreateLabel("Acceptance red", Optional.of("210,55,55")));
		var blue = service.createLabel(new CreateLabel("Acceptance blue", Optional.of("45,90,210")));
		var start = LocalDate.now(TimekeeperUiPlugin.getCalendarZone())
				.atStartOfDay(TimekeeperUiPlugin.getCalendarZone()).toInstant();
		var activity = service.createActivity(new CreateActivity(task.id(), net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL,
				start, Optional.of(start.plusSeconds(3600)), "Visible activity labels", java.util.Set.of(red.id(), blue.id())));
		try {
			assertRenderedLabels(bot, project, task, activity.summary(), new org.eclipse.swt.graphics.RGB(210, 55, 55),
					new org.eclipse.swt.graphics.RGB(45, 90, 210));
			screenshot(bot, "", "standalone-workweek-labels.png");
			PlatformUI.getWorkbench().getDisplay().asyncExec(() -> org.eclipse.ui.dialogs.PreferencesUtil
					.createPreferenceDialogOn(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
							"net.resheim.eclipse.timekeeper.ui.preferences.LabelPreferences", null, null).open());
			bot.shell("Preferences").activate();
			var labelTable = bot.table().widget;
			onUi(() -> assertTrue(java.util.Arrays.stream(labelTable.getItems())
					.anyMatch(item -> item.getText().equals(red.name()))));
			bot.button("Cancel").click();
			assertRenderedLabels(bot, project, task, activity.summary(), new org.eclipse.swt.graphics.RGB(210, 55, 55),
					new org.eclipse.swt.graphics.RGB(45, 90, 210));
			parentRow(bot, project.name(), task.summary()).expand().getNode(activity.summary()).contextMenu(red.name()).click();
			await(bot, () -> !service.activity(activity.id()).orElseThrow().labelIds().contains(red.id()),
					"The label menu did not remove the label");
			assertEquals(java.util.Set.of(blue.id()), service.activity(activity.id()).orElseThrow().labelIds());
			assertRenderedLabels(bot, project, task, activity.summary(), new org.eclipse.swt.graphics.RGB(45, 90, 210));
			parentRow(bot, project.name(), task.summary()).expand().getNode(activity.summary()).contextMenu(red.name()).click();
			await(bot, () -> service.activity(activity.id()).orElseThrow().labelIds().contains(red.id()),
					"The label menu did not restore the label");
			service.updateLabel(new UpdateLabel(blue.id(), blue.version(), blue.name(), Optional.of("40,180,90")));
			onUi(() -> ((WorkWeekView) PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
					.findView(WorkWeekView.VIEW_ID)).refreshAll());
			assertRenderedLabels(bot, project, task, activity.summary(), new org.eclipse.swt.graphics.RGB(210, 55, 55),
					new org.eclipse.swt.graphics.RGB(40, 180, 90));
			reopenWorkweek();
			assertRenderedLabels(bot, project, task, activity.summary(), new org.eclipse.swt.graphics.RGB(210, 55, 55),
					new org.eclipse.swt.graphics.RGB(40, 180, 90));
		} finally {
			cleanup(service, project, java.util.List.of(task.id()));
			for (var label : java.util.List.of(red, blue)) {
				service.label(label.id()).ifPresent(value -> service.deleteLabel(new DeleteLabel(value.id(), value.version())));
			}
		}
	}

	private static void assertRenderedLabels(SWTWorkbenchBot bot, Project project, Task task, String summary,
			org.eclipse.swt.graphics.RGB... colors) throws Exception {
		SWTWorkbenchBot workbenchBot = bot;
		bot.waitUntil(new DefaultCondition() {
			private Throwable lastFailure;
			@Override public boolean test() throws Exception {
				try {
					captureRenderedLabels(workbenchBot, project, task, summary, colors);
					return true;
				} catch (java.util.concurrent.ExecutionException failure) {
					if (!(failure.getCause() instanceof AssertionError)) throw failure;
					lastFailure = failure.getCause();
					return false;
				}
			}
			@Override public String getFailureMessage() { return "Label rendering did not settle: " + lastFailure; }
		}, 5000);
	}

	private static void captureRenderedLabels(SWTWorkbenchBot bot, Project project, Task task, String summary,
			org.eclipse.swt.graphics.RGB... colors) throws Exception {
		var row = parentRow(bot, project.name(), task.summary()).expand().getNode(summary);
		row.select();
		Tree tree = bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").widget;
		onUi(() -> {
			tree.deselectAll();
			// The session header reduces the initial docked view height. Scroll the
			// inspected row into view before checking pixels, as a user would.
			assertTrue("Workweek must retain a visible activity row", tree.getClientArea().height > tree.getHeaderHeight() + tree.getItemHeight());
			tree.setTopItem(row.widget);
			tree.showItem(row.widget);
			tree.redraw();
			tree.update();
			var image = new org.eclipse.swt.graphics.Image(tree.getDisplay(), tree.getSize().x, tree.getSize().y);
			var gc = new org.eclipse.swt.graphics.GC(tree.getDisplay());
			try {
				var origin = tree.toDisplay(0, 0);
				gc.copyArea(image, origin.x, origin.y);
				var data = image.getImageData();
				var bounds = row.widget.getBounds(0);
				// Display capture uses the same control coordinates as TreeItem bounds,
				// avoiding Cocoa scroll-view offsets from a GC constructed on Tree.
				bounds = bounds.intersection(new org.eclipse.swt.graphics.Rectangle(0, 0, data.width, data.height));
				// Inspect only the marker area, avoiding the activity icon and text.
				bounds.x += Math.max(0, bounds.width - 32);
				bounds.width = Math.min(32, bounds.width);
				var loader = new org.eclipse.swt.graphics.ImageLoader();
				loader.data = new org.eclipse.swt.graphics.ImageData[] { data };
				var directory = Path.of(System.getProperty("screenshots", System.getProperty("java.io.tmpdir")));
				try { Files.createDirectories(directory); }
				catch (java.io.IOException failure) { throw new AssertionError(failure); }
				loader.save(directory.resolve("workweek-label-pixels.png").toString(), SWT.IMAGE_PNG);
				for (var color : colors) {
					int pixels = 0;
					for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
						for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
							var actual = data.palette.getRGB(data.getPixel(x, y));
							// Cocoa snapshots convert through the display's color profile. The
							// painter test checks exact RGB; here we check visible colored areas.
							int tolerance = "cocoa".equals(SWT.getPlatform()) ? 60 : 0;
							if (Math.abs(color.red - actual.red) <= tolerance
									&& Math.abs(color.green - actual.green) <= tolerance
									&& Math.abs(color.blue - actual.blue) <= tolerance) pixels++;
						}
					}
					assertTrue("The activity row " + bounds + " must visibly paint label color " + color + ", found " + pixels,
							pixels >= 8);
				}
			} finally {
				gc.dispose();
				image.dispose();
			}
		});
	}

	@Test
	public void collapseAndExpandWorkweekPreserveStateAcrossRefresh() throws Exception {
		SWTWorkbenchBot bot = new SWTWorkbenchBot();
		await(bot, () -> TimekeeperPlugin.getDefault().isReady(), "Database is not ready");
		showWorkweek();
		var view = bot.viewByTitle("Workweek");
		view.setFocus();
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var project = service.createProject(new CreateProject("Tree toolbar project"));
		var task = service.createTask(new CreateTask(Optional.of(project.id()), "Tree toolbar task", Optional.empty()));
		view.toolbarButton("Show current week").click();
		view.toolbarButton("Show previous week").click();
		var start = LocalDate.now(TimekeeperUiPlugin.getCalendarZone()).minusWeeks(1)
				.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
				.atTime(9, 0).atZone(TimekeeperUiPlugin.getCalendarZone()).toInstant();
		var activity = service.createActivity(new CreateActivity(task.id(), net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL,
				start, Optional.of(start.plusSeconds(1800)), "Tree toolbar activity", java.util.Set.of()));
		Project[] refreshProof = new Project[1];
		try {
			// JFace may reuse TreeItems for other roots when refresh changes sorting.
			java.util.function.Supplier<org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem> row =
					() -> view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name());
			row.get();
			view.toolbarButton("Expand all projects and tasks").click();
			await(bot, () -> row.get().isExpanded(), "Expand all did not open the project");
			assertEquals(task.summary(), row.get().getNode(task.summary()).getText());
			assertEquals(activity.summary(), row.get().getNode(task.summary()).getNode(activity.summary()).getText());
			view.toolbarButton("Hide activities and keep projects and tasks visible").click();
			assertTrue("Hiding activities must keep tasks visible", row.get().isExpanded());
			var hiddenTask = row.get().getNode(task.summary());
			onUi(() -> assertEquals("Activities must be hidden", 0, hiddenTask.widget.getItemCount()));
			// A new root makes the asynchronous database refresh observable.
			refreshProof[0] = service.createProject(new CreateProject("Tree refresh proof"));
			view.bot().treeWithId("workweek-editor-tree").getTreeItem(refreshProof[0].name());
			assertTrue("Database updates must keep tasks visible", row.get().isExpanded());
			var refreshedTask = row.get().getNode(task.summary());
			onUi(() -> assertEquals("Database updates must keep activities hidden", 0, refreshedTask.widget.getItemCount()));
			onUi(() -> {
				var workweek = (WorkWeekView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
						.getActivePage().findView(WorkWeekView.VIEW_ID);
				workweek.refreshAll();
			});
			assertTrue("Explicit refresh must keep tasks visible", row.get().isExpanded());
			var explicitTask = row.get().getNode(task.summary());
			onUi(() -> assertEquals("Explicit refresh must keep activities hidden", 0, explicitTask.widget.getItemCount()));
			view.toolbarButton("Expand all projects and tasks").click();
			await(bot, () -> row.get().isExpanded(), "Expand all did not reopen the refreshed project");
			assertEquals(task.summary(), row.get().getNode(task.summary()).getText());
			assertEquals(activity.summary(), row.get().getNode(task.summary()).getNode(activity.summary()).getText());
		} finally {
			view.toolbarButton("Expand all projects and tasks").click();
			cleanup(service, project, java.util.List.of(task.id()));
			if (refreshProof[0] != null) service.deleteProject(new DeleteProject(refreshProof[0].id(), refreshProof[0].version()));
			view.toolbarButton("Show current week").click();
		}
	}

	@Test
	public void activityNameRequiresSelectionBeforeEditing() throws Exception {
		verifyActivityCellActivation(0);
	}

	@Test
	public void futureActivityStartsKeepNativeDraftsAndStoredRecordsUnchanged() throws Exception {
		var bot = new SWTWorkbenchBot();
		await(bot, () -> TimekeeperPlugin.getDefault().isReady(), "Database is not ready");
		showWorkweek();bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var project = service.createProject(new CreateProject("Future start project"));
		var task = service.createTask(new CreateTask(Optional.of(project.id()), "Future start task", Optional.empty()));
		var future = LocalDate.now(TimekeeperUiPlugin.getCalendarZone()).plusDays(1);
		try {
			parentRow(bot, project.name(), task.summary()).contextMenu("New activity").click();
			var dialog = bot.shell("New activity");dialog.activate();
			bot.textWithLabel("Summary").setText("Preserved future draft");
			bot.textWithLabel("Start (yyyy-MM-dd HH:mm)").setText(future + " 09:00");
			bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(future + " 10:00");
			bot.button("Save").click();
			assertTrue(dialog.isOpen());
			assertEquals("Preserved future draft",bot.textWithLabel("Summary").getText());
			assertTrue(service.activities(query(task)).activities().isEmpty());
			bot.button("Cancel").click();
			var start = LocalDate.now(TimekeeperUiPlugin.getCalendarZone()).atStartOfDay(TimekeeperUiPlugin.getCalendarZone()).toInstant();
			var activity = service.createActivity(new CreateActivity(task.id(),net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL,
					start,Optional.of(start.plusSeconds(60)),"Original recorded time",java.util.Set.of()));
			parentRow(bot, project.name(), task.summary()).expand().getNode(activity.summary()).contextMenu("Edit activity...").click();
			var edit = bot.shell("Edit activity");edit.activate();
			bot.textWithLabel("Start (yyyy-MM-dd HH:mm)").setText(future + " 09:00");
			bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(future + " 10:00");
			bot.button("Save").click();
			assertTrue(edit.isOpen());assertEquals(activity,service.activity(activity.id()).orElseThrow());
			bot.button("Cancel").click();
			var folder = Files.createTempDirectory("timekeeper-future-import-");
			TimekeeperPlugin.getDefault().exportTo(folder);
			var csv = folder.resolve("activity.csv");var exported = Files.readString(csv);
			assertTrue(exported.contains(activity.start().toString()));
			Files.writeString(csv,exported.replace(activity.start().toString(),future.atTime(9,0)
					.atZone(TimekeeperUiPlugin.getCalendarZone()).toInstant().toString()));
			var projectsBefore = service.projects();var tasksBefore = service.tasks();
			assertTrue(assertThrows(java.io.IOException.class,()->TimekeeperPlugin.getDefault().importFrom(folder))
					.getMessage().contains("future"));
			assertEquals(activity,service.activity(activity.id()).orElseThrow());
			assertEquals(projectsBefore,service.projects());assertEquals(tasksBefore,service.tasks());
		} finally {cleanup(service,project,java.util.List.of(task.id()));}
	}

	@Test
	public void activityTimeRequiresSelectionBeforeEditing() throws Exception {
		verifyActivityCellActivation(1);
	}

	/** Runs with and without the optional Mylyn adapter, including Linux CI. */
	private void verifyActivityCellActivation(int column) throws Exception {
		SWTWorkbenchBot bot = new SWTWorkbenchBot();
		await(bot, () -> TimekeeperPlugin.getDefault().isReady(), "Database is not ready");
		showWorkweek();
		bot.viewByTitle("Workweek").setFocus();
		bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
		bot.viewByTitle("Workweek").toolbarButton("Show previous week").click();
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var project = service.createProject(new CreateProject("Activity selection " + column));
		var task = service.createTask(new CreateTask(Optional.of(project.id()), "Select before editing", Optional.empty()));
		LocalDate firstDay = LocalDate.now(TimekeeperUiPlugin.getCalendarZone())
				.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1).minusWeeks(1);
		var start = firstDay.atTime(9, 0).atZone(TimekeeperUiPlugin.getCalendarZone()).toInstant();
		var activity = service.createActivity(new CreateActivity(task.id(), net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL,
				start, Optional.of(start.plusSeconds(3600)), "First activity", java.util.Set.of()));
		service.createActivity(new CreateActivity(task.id(), net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL,
				start.plusSeconds(3600), Optional.of(start.plusSeconds(7200)), "Other activity", java.util.Set.of()));
		Tree tree = bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").widget;
		try {
			var parent = parentRow(bot, project.name(), task.summary()).expand();
			var row = parent.getNode(activity.summary());
			onUi(tree::deselectAll);
			row.click(column);
			onUi(() -> {
				assertNull("First click must only select", cellEditor(tree));
				assertEquals(activity.id(), ((net.resheim.eclipse.timekeeper.domain.Activity)
						tree.getSelection()[0].getData()).id());
			});
			parent.getNode("Other activity").click(column);
			onUi(() -> assertNull("Switching rows must only select", cellEditor(tree)));
			row.click(column);
			onUi(() -> assertNull("Returning to a previously selected row must only select", cellEditor(tree)));
			row.click(column);
			String original = column == 0 ? activity.summary() : "09:00-10:00";
			String changed = column == 0 ? "Edited activity name" : "09:15-10:30";
			onUi(() -> {
				Text editor = cellEditor(tree);
				assertNotNull("Second click must edit the clicked cell", editor);
				assertEquals(original, editor.getText());
				editor.setText(changed);
				key(editor, SWT.CR);
			});
			var saved = service.activity(activity.id()).orElseThrow();
			assertEquals(column == 0 ? changed : activity.summary(), saved.summary());
			assertEquals(column == 0 ? activity.start() : start.plusSeconds(900), saved.start());
			assertEquals(column == 0 ? activity.end() : Optional.of(start.plusSeconds(5400)), saved.end());
			await(bot, () -> {
				try { return parentRow(bot, project.name(), task.summary()).expand().getNode(saved.summary()) != null; }
				catch (RuntimeException missing) { return false; }
			}, "Saved activity is not displayed");
			row = parentRow(bot, project.name(), task.summary()).expand().getNode(saved.summary());
			// Focus the time or name cell with a selection-only click, then edit with either Enter key.
			onUi(tree::deselectAll);
			row.click(column);
			for (int enter : new int[] { SWT.CR, SWT.KEYPAD_CR }) {
				onUi(() -> {
					assertNull(cellEditor(tree));
					key(tree, enter);
					Text editor = cellEditor(tree);
					assertNotNull("Enter must edit the focused selected cell", editor);
					assertEquals(changed, editor.getText());
					editor.setText(column == 0 ? "Cancelled name" : "08:00-11:00");
					key(editor, SWT.ESC);
					assertNull("Escape must close the editor", cellEditor(tree));
				});
				assertEquals("Escape must preserve the saved activity", saved, service.activity(activity.id()).orElseThrow());
			}
			row.doubleClick(column);
			onUi(() -> {
				assertNotNull("A quick second click must also edit", cellEditor(tree));
				key(cellEditor(tree), SWT.ESC);
			});
			onUi(() -> {
				var bounds = tree.getSelection()[0].getBounds(column);
				Event mouse = new Event();
				mouse.x = bounds.x + bounds.width / 2;
				mouse.y = bounds.y + bounds.height / 2;
				mouse.button = 3;
				mouse.count = 1;
				tree.notifyListeners(SWT.MouseDown, mouse);
				assertNull("Right-click must not edit", cellEditor(tree));
				mouse.button = 1;
				mouse.stateMask = SWT.SHIFT;
				tree.notifyListeners(SWT.MouseDown, mouse);
				assertNull("Selection modifiers must not edit", cellEditor(tree));
			});
			var other = parentRow(bot, project.name(), task.summary()).expand().getNode("Other activity");
			var selected = row;
			onUi(() -> {
				tree.setSelection(new org.eclipse.swt.widgets.TreeItem[] { selected.widget, other.widget });
				key(tree, SWT.CR);
				assertNull("Enter with multiple rows selected must not edit", cellEditor(tree));
			});
		} finally {
			onUi(() -> { Text editor = cellEditor(tree); if (editor != null) key(editor, SWT.ESC); });
			cleanup(service, project, java.util.List.of(task.id()));
			bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
		}
	}

	private static Text cellEditor(Tree tree) {
		return java.util.Arrays.stream(tree.getChildren()).filter(Text.class::isInstance).map(Text.class::cast)
				.filter(Text::isVisible).findFirst().orElse(null);
	}

	private static void key(org.eclipse.swt.widgets.Control control, int keyCode) {
		Event event = new Event();
		event.keyCode = keyCode;
		event.character = keyCode == SWT.KEYPAD_CR ? SWT.CR : (char) keyCode;
		control.notifyListeners(SWT.KeyDown, event);
		control.notifyListeners(SWT.KeyUp, event);
		if (control instanceof Text && (keyCode == SWT.CR || keyCode == SWT.KEYPAD_CR)) {
			control.notifyListeners(SWT.DefaultSelection, new Event());
		}
	}

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
			bot.textWithLabel("Start (yyyy-MM-dd HH:mm)").setText(monday + " 00:00");
			bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(monday + " 01:00");
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
		var start = monday.atStartOfDay(TimekeeperUiPlugin.getCalendarZone()).toInstant();
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
