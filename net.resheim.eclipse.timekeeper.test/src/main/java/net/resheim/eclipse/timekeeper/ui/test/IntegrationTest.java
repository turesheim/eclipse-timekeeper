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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.eclipse.core.runtime.Platform;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.ui.TasksUi;
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
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import net.resheim.eclipse.timekeeper.db.DatabaseChangeListener;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.Task;

@SuppressWarnings("restriction")
@RunWith(SWTBotJunit4ClassRunner.class)
public class IntegrationTest {
	
	
	private static final Logger log = Logger.getLogger(IntegrationTest.class);
	static {
		BasicConfigurator.configure();
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
		// we need to wait until the database is ready
		Object object = new Object();
		log.info("Preparing database");
		TimekeeperPlugin.getDefault().addListener(new DatabaseChangeListener() {			
			@Override
			public void databaseStateChanged() {
				synchronized (object) {
					object.notifyAll();
				}
			}
		});
		synchronized (object) {
			try {
				log.info("Waiting for database to become ready");
				object.wait();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();				
			}
		}
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
	
	//@Test
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
		SWTBotShell activeShell = bot.activeShell();
		activeShell.close();
		waitUntilShellIsClosed(bot, activeShell);
	}
	
	/*
	 * This is not a UI test but we put it here as everything is nicely rigged
	 */
	@Test
	@Ignore // TODO: fix this test
	public void testExport() {
		try {
			File newFolder = folder.newFolder();
			Path path = newFolder.toPath();
			TimekeeperPlugin.getDefault().exportTo(path);
			// probably don't have to verify that the content is correct as this is actually
			// done by H2
			Assert.assertEquals("\"TASK_ID\",\"REPOSITORY_URL\",\"TICK\",\"CURRENTACTIVITY_ID\"",
					Files.readAllLines(path.resolve("trackedtask.csv")).get(0));
			Assert.assertEquals(
					"\"ID\",\"END_TIME\",\"ADJUSTED\",\"START_TIME\",\"SUMMARY\",\"TASK_ID\",\"REPOSITORY_URL\"",
					Files.readAllLines(path.resolve("activity.csv")).get(0));
			Assert.assertEquals("\"TASK_ID\",\"REPOSITORY_URL\",\"ACTIVITIES_ID\"",
					Files.readAllLines(path.resolve("trackedtask_activity.csv")).get(0));
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
		bot.getDisplay().syncExec(() -> bot.getDisplay().getActiveShell().setSize(1024, 400));
		SWTBotView view = openViewById("net.resheim.eclipse.timekeeper.ui.views.workWeek");
		UIThreadRunnable.syncExec(bot.getDisplay(), () -> {
			ActionFactory.IWorkbenchAction maximizeAction = ActionFactory.MAXIMIZE
					.create(bot.viewByTitle(MAIN_VIEW_NAME).getViewReference().getPage().getWorkbenchWindow());
			maximizeAction.run();			
		});
		view.setFocus();
		// Take a screenshot for documentation
		Composite parent = bot.cTabItem(MAIN_VIEW_NAME).widget.getControl().getParent();
		assertNotNull(parent);
		bot.getDisplay().syncExec(() -> {
			TestUtility.takeScreenshot(screenshotsDir,
					parent, "workweek-view.png");
		});
		return view;
	}
	
}
