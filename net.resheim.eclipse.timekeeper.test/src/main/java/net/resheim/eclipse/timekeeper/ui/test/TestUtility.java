package net.resheim.eclipse.timekeeper.ui.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;

import javax.persistence.EntityTransaction;

import org.apache.log4j.Logger;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskCategory;
import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Task;

@SuppressWarnings("restriction")
public class TestUtility {

	private static final Logger log = Logger.getLogger(TestUtility.class);
	
	private static final int RADIUS = 32;

	static void createActivity(int dayOfWeek, Task ttask, String text) {
		LocalDateTime now = LocalDateTime.now();
		TemporalField fieldISO = WeekFields.of(Locale.getDefault()).dayOfWeek();
		LocalDateTime start = now.with(fieldISO, dayOfWeek);
		Activity a = new Activity(ttask, start);
		a.setSummary(text);
		ttask.addActivity(a);
		a.setStart(start.minus(Duration.ofHours(1)));
		a.setEnd(start);
		// save the new task (do we really need to)?
		EntityTransaction transaction = TimekeeperPlugin.getDefault().getEntityManager().getTransaction();
		transaction.begin();
		TimekeeperPlugin.getDefault().getEntityManager().persist(ttask);
		transaction.commit();
	}

	static Task createTask(TaskList tl, String category, String id, String text) {
		Optional<AbstractTaskCategory> o = tl.getCategories().stream().filter(c -> c.getHandleIdentifier().equals(category)).findFirst();
		AbstractTaskCategory c = o.orElseGet(() -> {
			TaskCategory tc = new TaskCategory(category, category);
			tl.addCategory(tc);
			return tc;
		});
		ITask task = new LocalTask(id, text);
		tl.addTask(task, c);
		return new Task(task);
	}

	/**
	 * Utility method for capturing a screenshot of a dialog or wizard window into a
	 * file.
	 * 
	 * @param shell the dialog shell
	 * @param file  the file to save the image to
	 */
	static void takeScreenshot(File screenshotsDir, final Control widget, String filename) {
		log.info("Taking screenshot of " + widget.getToolTipText());
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

	private static void compareImages(final Control widget, final Image image, Path path, ImageLoader loader)
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

}
