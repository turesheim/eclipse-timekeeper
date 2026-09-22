/*******************************************************************************
 * Copyright © 2019 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.concurrent.FutureTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.core.runtime.Platform;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.eclipse.finder.waits.Conditions;
import org.eclipse.swtbot.eclipse.finder.widgets.SWTBotView;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.junit.SWTBotJunit4ClassRunner;
import org.eclipse.swtbot.swt.finder.utils.SWTBotPreferences;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.TaskLinkStatus;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

@SuppressWarnings("restriction")
@RunWith(SWTBotJunit4ClassRunner.class)
public class IntegrationTest {
	
	
	private static final Logger log = LoggerFactory.getLogger(IntegrationTest.class);
	static {
		SWTBotPreferences.TIMEOUT = 20000;
	}

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final String TEST_MAIN_CATEGORY = "Timekeeper for Eclipse";
	private static final String TEST_MAIN_TASK = "152: Set up test rig for user interface tests";
	private static final String TEST_MAIN_ACTIVITY = "Add tests for the 'Workweek' view";
	private static final String MAIN_VIEW_NAME = "Workweek";
	private static SWTWorkbenchBot bot;

	/** Location for documentation screenshots */
	private static File screenshotsDir;
	
	private static TaskList tl;
	
	// TODO: Put this to work
//	RunListener rl = new RunListener() {
//
//		@Override
//		public void testRunFinished(Result result) throws Exception {
//			// disable the dialog that asks for confimation before closing the last window
//			IEclipsePreferences node= DefaultScope.INSTANCE.getNode(IDEWorkbenchPlugin.getDefault().getBundle().getSymbolicName());
//			node.putBoolean(IDEInternalPreferences.EXIT_PROMPT_ON_CLOSE_LAST_WINDOW, false);
//			// close the application and wait until it's is done
//			SWTBotShell activeShell = bot.activeShell();
//			activeShell.close();
//			waitUntilShellIsClosed(bot, activeShell);
//		}
//		
//	};
		
	@BeforeClass
	public static void beforeClass() {
		// make sure we have a supported keyboard
		SWTBotPreferences.KEYBOARD_LAYOUT = "EN_US";
		
		bot = new SWTWorkbenchBot();
		
		String screenshots = System.getProperty("screenshots");
		if (screenshots == null) {
			screenshots = "../docs/screenshots";
		}
		screenshotsDir = new File(screenshots);
		if (!screenshotsDir.exists()) {
			screenshotsDir.mkdirs();
		}
		// Poll the latched state: a one-shot listener can miss startup completion.
		bot.waitUntil(new DefaultCondition() {
			@Override
			public boolean test() {
				return TimekeeperPlugin.getDefault().isReady();
			}

			@Override
			public String getFailureMessage() {
				return "Timekeeper database did not become ready";
			}
		}, 30000);
		log.info("Database is ready, proceeding with tests.");
		tl = TasksUiPlugin.getTaskList();
		closeWelcome();
		bot.getDisplay().syncExec(() ->  {
			// TODO: why is this required?
			//TasksUi.getTaskActivityManager().activateTask(ttask_1.getTask());
			Task ttask = TestUtility.createTask(tl, "Timekeeper for Eclipse", "152", "Set up test rig for user interface tests");
			TestUtility.createActivity(1, ttask, "Rig test plug-in and make it take screenshots");
			TestUtility.createActivity(1, ttask, "Add tests for the 'Workweek' view");
			TestUtility.createActivity(3, ttask, "Add test for the preferences dialog");
			TasksUi.getTaskActivityManager().activateTask(ttask.getMylynTask());
			bot.sleep(500);
			Task ttask_1 = TestUtility.createTask(tl, "Eclipse Science", "1", "Eclipse Science web site");
			TestUtility.createActivity(3, ttask_1, "Send out e-mail about GitHub repo");
			//TimekeeperUiPlugin.getActiveTrackedTask().getCurrentActivity().get().setSummary("Add some sensible data for the screenshot");
		});
		// force a save of the workspace so that our save participant is triggered
//		ResourcesPlugin.getWorkspace().save(true, new NullProgressMonitor());
		
	}
		
	@Test
	public void testEmbeddedServiceIsResolvedThroughOsgi() {
		assertNotNull(TimekeeperUiPlugin.getDefault().getTimekeeperService());
	}

	@Test
	public void testNavigateWorkweekView() throws Exception {
		prepareWorkweekView();
		assertTrue(bot.viewByTitle(MAIN_VIEW_NAME).isActive());
		// try the various toolbar buttons
		bot.activePart().toolbarButton("Show previous week").click();
		bot.activePart().toolbarButton("Show previous week").click();
		bot.activePart().toolbarButton("Show next week").click();
		bot.activePart().toolbarButton("Show current week").click();
		// copy to the clipboard using the default template
		bot.activePart().toolbarDropDownButton("Export selected week to clipboard").click();
		// copy to the clipboard using the basic HTML template
		bot.activePart().toolbarDropDownButton("Export selected week to clipboard")
				.menuItem("Copy as").click().menu("Basic HTML").click();		
	}
	
	@Test
	public void testTaskActivationAndDeactivation() throws Exception {
		prepareWorkweekView();
		Field lastActiveTime = TimekeeperUiPlugin.class.getDeclaredField("lastActiveTime");
		lastActiveTime.setAccessible(true);
		Field lastIdleTimeMillis = TimekeeperUiPlugin.class.getDeclaredField("lastIdleTimeMillis");
		lastIdleTimeMillis.setAccessible(true);
		Method updateStatus = WorkWeekView.class.getDeclaredMethod("updateStatus");
		updateStatus.setAccessible(true);
		ITask[] previous = new ITask[1];
		Task[] tracked = new Task[1];
		Object[] previousLastActive = new Object[1];
		long[] previousLastIdle = new long[1];
		try {
			runOnUi(() -> {
				previous[0] = TasksUi.getTaskActivityManager().getActiveTask();
				try {
					previousLastActive[0] = lastActiveTime.get(TimekeeperUiPlugin.getDefault());
					previousLastIdle[0] = lastIdleTimeMillis.getLong(null);
				} catch (IllegalAccessException e) {
					throw new AssertionError(e);
				}
				ITask task = TestUtility.createTask(tl, "Lifecycle checks", "3001",
						"Track an activity").getMylynTask();
				TasksUi.getTaskActivityManager().activateTask(task);
				try {
					lastActiveTime.set(TimekeeperUiPlugin.getDefault(), null);
					lastIdleTimeMillis.setLong(null, Long.MAX_VALUE);
					assertFalse("Idle status requires a last-active sample",
							TimekeeperUiPlugin.getDefault().isIdle());
					assertNull("Idle start is unknown until the idle detector has sampled activity",
							TimekeeperUiPlugin.getDefault().getIdleSince());
					WorkWeekView view = (WorkWeekView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
							.getActivePage().findView(WorkWeekView.VIEW_ID);
					assertNotNull(view);
					updateStatus.invoke(view);
				} catch (ReflectiveOperationException e) {
					throw new AssertionError(e);
				}
				tracked[0] = TimekeeperPlugin.getDefault().getTask(task);
				assertEquals("Lifecycle checks", tracked[0].getProject().getName());
				Activity activity = tracked[0].getCurrentActivity().orElseThrow();
				assertEquals(1, tracked[0].getActivities().size());
				Assert.assertNull(activity.getEnd());
				activity.setSummary("Lifecycle activity");
				activity.setStart(Instant.now().minusSeconds(120));
				TasksUi.getTaskActivityManager().deactivateTask(task);
				assertTrue(tracked[0].getCurrentActivity().isEmpty());
				assertNotNull(activity.getEnd());
				assertTrue(activity.getEnd().isAfter(activity.getStart()));
				// Duplicate deactivation must not try to persist a null activity.
				TimekeeperPlugin.getDefault().endMylynTask(task);
			});
			int today = LocalDate.now().get(WeekFields.of(Locale.getDefault()).dayOfWeek());
			var project = bot.treeWithId("workweek-editor-tree").getTreeItem("Lifecycle checks");
			var task = project.getNode("3001: Track an activity");
			assertEquals("0:02", project.cell(today));
			assertEquals("0:02", task.cell(today));
			assertEquals("0:02", task.getNode("Lifecycle activity").cell(today));
		} finally {
			runOnUi(() -> {
				try {
					lastActiveTime.set(TimekeeperUiPlugin.getDefault(), previousLastActive[0]);
					lastIdleTimeMillis.setLong(null, previousLastIdle[0]);
				} catch (IllegalAccessException e) {
					throw new AssertionError(e);
				}
				ITask active = TasksUi.getTaskActivityManager().getActiveTask();
				if (active != null) TasksUi.getTaskActivityManager().deactivateTask(active);
				if (previous[0] != null) TasksUi.getTaskActivityManager().activateTask(previous[0]);
			});
		}
	}

	@Test
	public void testDeletedMylynTaskRemainsVisible() throws Exception {
		prepareWorkweekView();
		runOnUi(() -> {
			Task historical = TestUtility.createTask(tl, "Historical records", "3002", "Keep recorded time");
			TestUtility.createActivity(1, historical, "Recorded before deletion");
			tl.deleteTask(historical.getMylynTask());
			LocalDate first = LocalDate.now().with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1);
			Task reloaded = TimekeeperPlugin.getTasks(first, TimekeeperUiPlugin.getCalendarZone())
					.filter(t -> "3002".equals(t.getTaskId())).findFirst().orElseThrow();
			Assert.assertNull(reloaded.getMylynTask());
			assertEquals(TaskLinkStatus.UNLINKED, reloaded.getTaskLinkStatus());
			assertEquals("Keep recorded time", reloaded.getTaskSummary());
			assertEquals("Historical records", reloaded.getProject().getName());
		});
		bot.activePart().toolbarButton("Show current week").click();
		var row = bot.treeWithId("workweek-editor-tree").getTreeItem("Historical records")
				.getNode("3002: Keep recorded time");
		assertEquals("1:00", row.cell(1));
		assertEquals("1:00", row.getNode("Recorded before deletion").cell(1));
		var menu = row.contextMenu("New activity");
		assertTrue(menu.isEnabled());
		menu.hide();
	}

	@Test
	public void testStandaloneTaskWorkflowUsesTheClientService() throws Exception {
		SWTBotView workweek = prepareWorkweekView();
		ITask previous = TasksUi.getTaskActivityManager().getActiveTask();
		if (previous != null) runOnUi(() -> TasksUi.getTaskActivityManager().deactivateTask(previous));

		workweek.toolbarButton("Manage Timekeeper tasks").click();
		bot.waitUntil(Conditions.shellIsActive("Timekeeper Tasks"));
		SWTBotShell manager = bot.activeShell();
		var managerBot = manager.bot();
		managerBot.button("New...").click();
		bot.waitUntil(Conditions.shellIsActive("New Timekeeper Task"));
		bot.textWithId("standalone-task-summary").setText("Draft standalone task documentation");
		bot.textWithId("standalone-task-url").setText("https://example.test/standalone");
		bot.button("Create").click();
		bot.waitUntil(Conditions.shellIsActive("Timekeeper Tasks"));

		var row = managerBot.tableWithId("standalone-task-table")
				.getTableItem("Draft standalone task documentation");
		row.select();
		assertEquals("https://example.test/standalone", row.getText(1));
		assertEquals("Standalone", row.getText(2));
		assertTrue(managerBot.button("Open URL").isEnabled());
		var created = TimekeeperUiPlugin.getDefault().getTimekeeperService().tasks().stream()
				.filter(task -> "Draft standalone task documentation".equals(task.summary()))
				.findFirst().orElseThrow();
		var identity = created.id();
		assertTrue(created.projectId().isEmpty());

		managerBot.button("Edit...").click();
		bot.waitUntil(Conditions.shellIsActive("Edit Timekeeper Task"));
		bot.textWithId("standalone-task-summary").setText("Document standalone tasks");
		bot.textWithId("standalone-task-url").setText("https://example.test/renamed");
		bot.button("Save").click();
		bot.waitUntil(Conditions.shellIsActive("Timekeeper Tasks"));
		row = managerBot.tableWithId("standalone-task-table").getTableItem("Document standalone tasks");
		row.select();

		managerBot.button("Link...").click();
		bot.waitUntil(Conditions.shellIsActive("Link External Task"));
		bot.textWithId("external-provider").setText("github");
		bot.textWithId("external-repository").setText("turesheim/eclipse-timekeeper");
		bot.textWithId("external-task-id").setText("183");
		bot.textWithId("external-task-url").setText("https://github.com/turesheim/eclipse-timekeeper/issues/183");
		bot.button("Link").click();
		bot.waitUntil(Conditions.shellIsActive("Timekeeper Tasks"));
		row = managerBot.tableWithId("standalone-task-table").getTableItem("Document standalone tasks");
		row.select();
		assertEquals("github:183", row.getText(2));
		runOnUi(() -> TestUtility.takeScreenshot(screenshotsDir, manager.widget.getChildren()[0],
				"standalone-tasks.png"));
		managerBot.button("Unlink...").click();
		assertEquals("Standalone", managerBot.tableWithId("standalone-task-table")
				.getTableItem("Document standalone tasks").getText(2));

		managerBot.tableWithId("standalone-task-table").getTableItem("Document standalone tasks").select();
		managerBot.button("Start Activity").click();
		assertEquals(identity, TimekeeperUiPlugin.getDefault().getTimekeeperService()
				.activeActivity(net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL).orElseThrow().taskId());
		bot.sleep(1100);
		managerBot.button("Stop Activity").click();
		assertTrue(TimekeeperUiPlugin.getDefault().getTimekeeperService()
				.activeActivity(net.resheim.eclipse.timekeeper.domain.OwnerId.LOCAL).isEmpty());
		assertEquals(identity, TimekeeperUiPlugin.getDefault().getTimekeeperService().task(identity).orElseThrow().id());

		managerBot.button("Close").click();
		waitUntilShellIsClosed(bot, manager);
		workweek.setFocus();
		workweek.toolbarButton("Show current week").click();
		assertNotNull(workweek.bot().treeWithId("workweek-editor-tree")
				.getTreeItem("Document standalone tasks"));
		runOnUi(() -> TestUtility.takeScreenshot(screenshotsDir,
				workweek.getViewReference().getPage().getWorkbenchWindow().getShell(),
				"standalone-task-workweek.png"));
	}

	private static void runOnUi(Runnable action) throws Exception {
		// Propagate assertion failures to JUnit instead of Eclipse's event-loop log.
		FutureTask<Void> invocation = new FutureTask<>(action, null);
		bot.getDisplay().syncExec(invocation);
		invocation.get();
	}

	@Test
	public void testEditTimeRange() {
		// ignore this test as it always fails on Travis-CI due to the bot not
		// being able to locate the tree and gets stuck on the "Find Actions"
		// text editor instead.
		if (!Platform.getOS().equals(Platform.OS_LINUX)) {
			prepareWorkweekView();
			assertTrue(bot.viewByTitle(MAIN_VIEW_NAME).isActive());
			// verify that a text field can be edited, first day of week
			bot.treeWithId("workweek-editor-tree").getTreeItem(TEST_MAIN_CATEGORY)
				.getNode(TEST_MAIN_TASK)
					.getNode(TEST_MAIN_ACTIVITY).select().click(1);
			bot.text().setText("17:00-20:12");
			bot.getDisplay().syncExec(() -> {
				//bot.text().pressShortcut(KeyStroke.getInstance(SWT.LF));
				bot.text().pressShortcut(SWT.CR, SWT.LF);
			});
			log.info("Verify changed value");
			String value = bot.treeWithId("workweek-editor-tree").getTreeItem(TEST_MAIN_CATEGORY)
				.getNode(TEST_MAIN_TASK)
					.getNode(TEST_MAIN_ACTIVITY).select().cell(1);
			assertEquals("Time range is not correctly updated", "3:12", value);
		}
	}
	
	@Test
	public void testOpenPreferences() {
		prepareWorkweekView();
		log.info("Opening preferences dialog");		
		// WTF: Creates InvalidThreadException
		// bot.getDisplay().syncExec(PreferencesUtil.createPreferenceDialogOn(bot.getDisplay().getActiveShell(), null, null, null)::open);
		// return immediately to avoid blocking
		bot.getDisplay().asyncExec(() -> PreferencesUtil.createPreferenceDialogOn(new Shell(bot.getDisplay()), null, null, null).open());
		bot.waitUntil(Conditions.shellIsActive("Preferences")); 
		bot.tree().getTreeItem("Timekeeper").select();
		bot.getDisplay().syncExec(() -> {
			Composite main = (Composite)((Composite)bot.activeShell().widget.getChildren()[0]).getChildren()[0];			
			TestUtility.takeScreenshot(screenshotsDir, main.getChildren()[3], "preferences-timekeeper.png");
		});
		bot.tree().getTreeItem("Timekeeper").expand().getNode("Database").select();
		bot.getDisplay().syncExec(() -> {
			Composite main = (Composite)((Composite)bot.activeShell().widget.getChildren()[0]).getChildren()[0];
			TestUtility.takeScreenshot(screenshotsDir, main.getChildren()[3], "preferences-database.png");
		});
		bot.tree().getTreeItem("Timekeeper").expand().getNode("Report Templates").select();
		bot.list().select("Default HTML");
		bot.sleep(200);
		bot.styledText().navigateTo(50, 10);
		bot.sleep(100);
		bot.getDisplay().syncExec(() -> {
			Composite main = (Composite)((Composite)bot.activeShell().widget.getChildren()[0]).getChildren()[0];
			TestUtility.takeScreenshot(screenshotsDir, main.getChildren()[3], "preferences-templates.png");
		});
		// Verify that the complete preference page can be stored on current JFace.
		// The JDBC URL editor is optional for the selected shared database location.
		SWTBotShell activeShell = bot.activeShell();
		bot.button("Apply and Close").click();
		waitUntilShellIsClosed(bot, activeShell);
	}
	
	/*
	 * This is not a UI test but we put it here as everything is nicely rigged
	 */
	@Test
	public void testExport() {
		try {
			File newFolder = folder.newFolder();
			Path path = newFolder.toPath();
			TimekeeperPlugin.getDefault().exportTo(path);
			// probably don't have to verify that the content is correct as this is actually
			// done by H2
			Assert.assertEquals("\"ID\",\"TASK_SUMMARY\",\"TASK_URL\",\"TICK\",\"VERSION\",\"TASK_PROJECT\",\"CURRENTACTIVITY_ID\"",
					Files.readAllLines(path.resolve("trackedtask.csv")).get(0));
			Assert.assertEquals(
					"\"ID\",\"END_TIME\",\"ADJUSTED\",\"OWNER_ID\",\"START_TIME\",\"SUMMARY\",\"ACTIVITY_PROJECT\",\"TASK_ID\"",
					Files.readAllLines(path.resolve("activity.csv")).get(0));
			Assert.assertEquals("\"TASK_ID\",\"ACTIVITIES_ID\"",
					Files.readAllLines(path.resolve("trackedtask_activity.csv")).get(0));
			Assert.assertEquals("\"ID\",\"EXTERNAL_ID\",\"EXTERNAL_URL\",\"PROVIDER_ID\",\"REPOSITORY_ID\",\"TASK_ID\"",
					Files.readAllLines(path.resolve("external_task_reference.csv")).get(0));
			int imported = TimekeeperPlugin.getDefault().importFrom(path);
			Assert.assertTrue("Current-schema CSV round trip imported no rows", imported > 0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}	

	public static void waitUntilShellIsClosed(SWTBot bot, SWTBotShell shell) {
		bot.waitUntil(new DefaultCondition() {
			@Override
			public String getFailureMessage() {
				return "Shell " + shell.getText() + " did not close"; //$NON-NLS-1$
			}

			@Override
			public boolean test() throws Exception {
				return !shell.isOpen();
			}
		});
	}

	public static void closeWelcome() {
		SWTBotView activeView = bot.activeView();
		if (activeView != null && activeView.getTitle().equals("Welcome")) {
			activeView.close();
		}
	}

	public static SWTBotView openViewById(String viewId) {
		UIThreadRunnable.syncExec(bot.getDisplay(), () -> {
			IWorkbenchWindow activeWindow = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			assertNotNull(activeWindow);
			try {
				activeWindow.getActivePage().showView(viewId);
			} catch (PartInitException e) {
				// viewById() will fail in calling thread
				e.printStackTrace();
			}
		});
		return bot.viewById(viewId);
	}

	@SuppressWarnings("deprecation")
	private SWTBotView prepareWorkweekView() {
		bot.resetWorkbench();
		bot.getDisplay().syncExec(() -> {
			Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
			shell.setSize(1024, 400);
			shell.forceActive();
		});
		SWTBotView view = openViewById("net.resheim.eclipse.timekeeper.ui.views.workWeek");
		UIThreadRunnable.syncExec(bot.getDisplay(), () -> {
			ActionFactory.IWorkbenchAction maximizeAction = ActionFactory.MAXIMIZE
					.create(view.getViewReference().getPage().getWorkbenchWindow());
			maximizeAction.run();			
		});
		view.setFocus();
		// Take a screenshot for documentation
		bot.getDisplay().syncExec(() -> {
			TestUtility.takeScreenshot(screenshotsDir,
					view.getViewReference().getPage().getWorkbenchWindow().getShell(), "workweek-view.png");
		});
		return view;
	}
	
}
