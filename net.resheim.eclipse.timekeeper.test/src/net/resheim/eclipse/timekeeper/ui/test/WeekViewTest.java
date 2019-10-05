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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import javax.persistence.EntityTransaction;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskCategory;
import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.eclipse.finder.waits.Conditions;
import org.eclipse.swtbot.eclipse.finder.widgets.SWTBotView;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.junit.SWTBotJunit4ClassRunner;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.DatabaseChangeListener;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

@SuppressWarnings("restriction")
@RunWith(SWTBotJunit4ClassRunner.class)
public class WeekViewTest {
	
	private static final Logger log = Logger.getLogger(WeekViewTest.class);
	static {
		BasicConfigurator.configure();
	}
	
	private static final String MAIN_VIEW_CATEGORY = "Timekeeper";
	private static final String MAIN_VIEW_NAME = "Workweek";
	private static final int RADIUS = 32;
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
		bot = new SWTWorkbenchBot();
		String screenshots = System.getProperty("screenshots");
		if (screenshots == null) {
			screenshots = "../resources/screenshots";
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
	}
		
	@Test
	public void testOpenWorkweekView() throws Exception {
		log.info("Resizing shell");
		bot.getDisplay().asyncExec(() -> bot.getDisplay().getActiveShell().setSize(1024, 400));
		// open view
		log.info("Opening Workweek view");
		openViewById("net.resheim.eclipse.timekeeper.ui.views.workWeek");
		assertTrue(bot.viewByTitle(MAIN_VIEW_NAME).isActive());
		ActionFactory.IWorkbenchAction maximizeAction = ActionFactory.MAXIMIZE
				.create(bot.viewByTitle(MAIN_VIEW_NAME).getViewReference().getPage().getWorkbenchWindow());
		maximizeAction.run();
		bot.getDisplay().syncExec(() ->  {
			TrackedTask ttask_1 = createTask("Eclipse Science", "1", "Eclipse Science web site");
			createActivity(ttask_1, "Send out e-mail about GitHub repo");
			// TODO: why is this required?
			//TasksUi.getTaskActivityManager().activateTask(ttask_1.getTask());
			TrackedTask ttask = createTask("Timekeeper for Eclipse", "152", "Set up test rig for user interface tests");
			createActivity(ttask, "Rig test plug-in and make it take screenshots");
			createActivity(ttask, "Mess around for a cross-platform method for opening the preferences dialog");
			TasksUi.getTaskActivityManager().activateTask(ttask.getTask());
			TimekeeperUiPlugin.getActiveTrackedTask().getCurrentActivity().get().setSummary("Add some sensible data for the screenshot");
		});
		// force a save of the workspace so that our save participant is triggered
		ResourcesPlugin.getWorkspace().save(true, new NullProgressMonitor());
		// try the various toolbar buttons
		bot.toolbarButtonWithTooltip("Show previous week").click();
		bot.toolbarButtonWithTooltip("Show previous week").click();
		bot.toolbarButtonWithTooltip("Show next week").click();
		bot.toolbarButtonWithTooltip("Show current week").click();
		bot.toolbarDropDownButtonWithTooltip("Export selected week to clipboard").click();		
		// Take a screenshot for documentation
		//bot.viewByTitle(MAIN_VIEW_NAME).setFocus();
		bot.sleep(5000);
		bot.getDisplay().syncExec(() -> takeScreenshot(bot.cTabItem(MAIN_VIEW_NAME).widget.getParent().getParent(),"workweek-view.png"));
	}
	
	@Test
	public void testOpenPreferences() {
		bot.sleep(1000);
		log.info("Opening preferences dialog");		
		// WTF: Creates InvalidThreadException
		// bot.getDisplay().syncExec(PreferencesUtil.createPreferenceDialogOn(bot.getDisplay().getActiveShell(), null, null, null)::open);
		// return immediately to avoid blocking
		bot.getDisplay().asyncExec(() -> PreferencesUtil.createPreferenceDialogOn(new Shell(bot.getDisplay()), null, null, null).open());
		bot.waitUntil(Conditions.shellIsActive("Preferences")); 
		bot.tree().getTreeItem("Timekeeper").select();
		bot.getDisplay().syncExec(() -> {
			Composite main = (Composite)((Composite)bot.activeShell().widget.getChildren()[0]).getChildren()[0];			
			takeScreenshot(main.getChildren()[3], "preferences-timekeeper.png");
		});
		bot.tree().getTreeItem("Timekeeper").expand().getNode("Database").select();
		bot.getDisplay().syncExec(() -> {
			Composite main = (Composite)((Composite)bot.activeShell().widget.getChildren()[0]).getChildren()[0];
			takeScreenshot(main.getChildren()[3], "preferences-database.png");
		});
		bot.tree().getTreeItem("Timekeeper").expand().getNode("Report Templates").select();
		bot.getDisplay().syncExec(() -> {
			bot.list().select("Default HTML");
			Composite main = (Composite)((Composite)bot.activeShell().widget.getChildren()[0]).getChildren()[0];
			takeScreenshot(main.getChildren()[3], "preferences-templates.png");
		});
		SWTBotShell activeShell = bot.activeShell();
		activeShell.close();
		waitUntilShellIsClosed(bot, activeShell);
	}

	private static void createActivity(TrackedTask ttask,String text) {
		LocalDateTime now = LocalDateTime.now();
		Activity a = new Activity(ttask,now);
		a.setSummary(text);
		ttask.addActivity(a);
		a.setStart(now.minus(Duration.ofHours(1)));
		a.setEnd(now);
		// save the new task (do we really need to)?
		EntityTransaction transaction = TimekeeperPlugin.getDefault().getEntityManager().getTransaction();
		transaction.begin();
		TimekeeperPlugin.getDefault().getEntityManager().persist(ttask);
		transaction.commit();
	}

	private static TrackedTask createTask(String category, String id, String text) {
		Optional<AbstractTaskCategory> o = tl.getCategories().stream().filter(c -> c.getHandleIdentifier().equals(category)).findFirst();
		AbstractTaskCategory c = o.orElseGet(() -> {
			TaskCategory tc = new TaskCategory(category, category);
			tl.addCategory(tc);
			return tc;
		});
		ITask task = new LocalTask(id, text);
		tl.addTask(task, c);
		return new TrackedTask(task);
	}

	  /**
	 * Utility method for capturing a screenshot of a dialog or wizard window into a
	 * file.
	 * 
	 * @param shell the dialog shell
	 * @param file  the file to save the image to
	 */
	private void takeScreenshot(final Control widget, String filename) {
		// Grab a screenshot of the dialog shell
		final Rectangle b = widget.getBounds();
		int width = b.width;
		int height = b.height;
		
		Rectangle m = widget.getDisplay().map(widget.getParent(), null, b);
		final Image screenshot = new Image(widget.getDisplay(), width, height);
		
		GC gc = new GC(widget.getDisplay());
		gc.copyArea(screenshot, m.x, m.y);
		gc.dispose();

		// Create drop shadow image
		final Image image = new Image(widget.getDisplay(), width * 2, height * 2);
		GC gc2 = new GC(image);
		gc2.setInterpolation(SWT.HIGH);
		gc2.setAntialias(SWT.ON);
		int border = RADIUS / 2;
		fillRoundRectangleDropShadow(gc2, image.getBounds(), RADIUS);
		gc2.drawImage(screenshot, 0, 0, width, height, border, border, width * 2 - RADIUS, height * 2 - RADIUS);
		screenshot.dispose();
		gc2.dispose();
		Path path = Paths.get(screenshotsDir.getAbsolutePath(), filename);
		ImageLoader loader = new ImageLoader();
		try {
			// overwrite the existing file if different
			if (path.toFile().exists()) {
				compareImages(widget, image, path, loader);
				screenshot.dispose();
				return;
			}
			loader.data = new ImageData[] { image.getImageData() };
			loader.save(Files.newOutputStream(path, StandardOpenOption.CREATE), SWT.IMAGE_PNG);
			image.dispose();
		} catch (IOException e) {
			log.error("Could not save image file", e);
		}
	}

	private void compareImages(final Control widget, final Image image, Path path, ImageLoader loader)
			throws IOException {
		try {
			loader.load(Files.newInputStream(path, StandardOpenOption.READ));
			Image original = new Image(widget.getDisplay(), loader.data[0]);

			if (!original.getImageData().equals(image.getImageData())) {
				loader.data = new ImageData[] { image.getImageData() };
				loader.save(Files.newOutputStream(path, StandardOpenOption.WRITE), SWT.IMAGE_PNG);
			}
			original.dispose();
		} catch (SWTException e) {
			// probably broken image file, just continue and
			// overwrite it
		}
	}

	private static void fillRoundRectangleDropShadow(GC gc, Rectangle bounds, int radius) {
		gc.setAdvanced(true);
		gc.setAntialias(SWT.ON);
		gc.setBackground(Display.getCurrent().getSystemColor(SWT.COLOR_BLACK));
		gc.setAlpha(0x8f / radius);
		for (int i = 0; i < radius; i++) {
			Rectangle shadowBounds = new Rectangle(bounds.x + i, bounds.y + i, bounds.width - (i * 2),
					bounds.height - (i * 2));
			gc.fillRoundRectangle(shadowBounds.x, shadowBounds.y, shadowBounds.width, shadowBounds.height, radius * 2,
					radius * 2);
		}
		gc.setAlpha(0xff);
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
	
}
