package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.BeforeClass;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.ui.preferences.LabelPreferencePage;
import net.resheim.eclipse.timekeeper.ui.preferences.DatabasePreferencePage;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceInitializer;
import net.resheim.eclipse.timekeeper.ui.views.WeekViewContentProvider;

/** Regression coverage for notifications racing with view disposal. */
public class WeekViewContentProviderTest {

	@BeforeClass
	public static void waitForDatabase() {
		new SWTWorkbenchBot().waitUntil(new DefaultCondition() {
			@Override
			public boolean test() {
				return TimekeeperPlugin.getDefault().isReady();
			}

			@Override
			public String getFailureMessage() {
				return "Timekeeper database did not become ready";
			}
		}, 30000);
	}

	@Test
	public void preferencesRemainResponsiveWhenDatabaseStartupFails() throws Exception {
		// Simulate a terminal failure by temporarily hiding the live connection.
		// Restore from the test thread even on timeout, so the old blocking loop
		// would be released rather than hanging the remaining Eclipse tests.
		Field field = TimekeeperPlugin.class.getDeclaredField("databaseStatus");
		field.setAccessible(true);
		Object previous = field.get(null);
		Field managerField = TimekeeperPlugin.class.getDeclaredField("entityManager");
		managerField.setAccessible(true);
		Object previousManager = managerField.get(null);
		try {
			field.set(null, new Status(IStatus.ERROR, TimekeeperPlugin.BUNDLE_ID, "Synthetic startup failure"));
			managerField.set(null, null);
			FutureTask<Void> initialization = new FutureTask<>(() -> {
				new PreferenceInitializer().initializeDefaultPreferences();
				return null;
			});
			PlatformUI.getWorkbench().getDisplay().asyncExec(initialization);
			initialization.get(3, TimeUnit.SECONDS);
			onUi(() -> {
				Shell shell = new Shell(Display.getCurrent());
				LabelPreferencePage page = new LabelPreferencePage();
				DatabasePreferencePage database = new DatabasePreferencePage();
				try {
					page.createControl(shell);
					assertFalse(page.isValid());
					assertFalse(page.performOk());
					database.init(PlatformUI.getWorkbench());
					String location = database.getPreferenceStore().getString(TimekeeperPlugin.PREF_DATABASE_LOCATION);
					String url = database.getPreferenceStore().getString(TimekeeperPlugin.PREF_DATABASE_URL);
					database.createControl(shell);
					assertNull(button((Composite) database.getControl(), "Upgrade database backup..."));
					assertNull(button((Composite) database.getControl(), "Verify recovered database..."));
					assertTrue(button((Composite) database.getControl(), "Specified by JDBC URL").isEnabled());
					assertEquals(location, database.getPreferenceStore().getString(TimekeeperPlugin.PREF_DATABASE_LOCATION));
					assertEquals(url, database.getPreferenceStore().getString(TimekeeperPlugin.PREF_DATABASE_URL));
				} finally {
					database.dispose();
					page.dispose();
					shell.dispose();
				}
			});
		} finally {
			managerField.set(null, previousManager);
			field.set(null, previous);
		}
	}

	private static Button button(Composite parent, String text) {
		for (Control child : parent.getChildren()) {
			if (child instanceof Button button && text.equals(button.getText())) return button;
			if (child instanceof Composite composite) {
				Button found = button(composite, text);
				if (found != null) return found;
			}
		}
		return null;
	}

	@Test
	public void backgroundNotificationRefreshesOnUiThread() throws Exception {
		Fixture fixture = createFixture();
		try {
			notifyFromWorker(fixture.provider);
			// syncExec drains the preceding async notification before checking its result.
			onUi(() -> {
				assertEquals(1, fixture.filters);
				assertEquals(1, fixture.refreshes);
			});
		} finally {
			onUi(fixture::dispose);
		}
	}

	@Test
	public void queuedNotificationIsIgnoredAfterControlDisposal() throws Exception {
		checkDisposal(false);
	}

	@Test
	public void queuedNotificationIsIgnoredAfterProviderDisposal() throws Exception {
		checkDisposal(true);
	}

	private void checkDisposal(boolean disposeProvider) throws Exception {
		Fixture fixture = createFixture();
		try {
			onUi(() -> {
				// Keep the UI thread occupied until the worker has queued the notification.
				notifyFromWorker(fixture.provider);
				if (disposeProvider) {
					fixture.provider.dispose();
				} else {
					fixture.shell.dispose();
				}
			});
			onUi(() -> {
				assertEquals(0, fixture.filters);
				assertEquals(0, fixture.refreshes);
			});
			// Notifications arriving after disposal must also be harmless.
			notifyFromWorker(fixture.provider);
			onUi(() -> {
				assertEquals(0, fixture.filters);
				assertEquals(0, fixture.refreshes);
			});
		} finally {
			onUi(fixture::dispose);
		}
	}

	private static Fixture createFixture() throws Exception {
		Fixture[] result = new Fixture[1];
		onUi(() -> result[0] = new Fixture());
		return result[0];
	}

	private static void notifyFromWorker(WeekViewContentProvider provider) {
		FutureTask<Void> notification = new FutureTask<>(provider::databaseStateChanged, null);
		Thread worker = new Thread(notification, "timekeeper-notification-test");
		worker.setDaemon(true);
		worker.start();
		try {
			notification.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new AssertionError("Background notification failed or blocked on the UI thread", e);
		}
	}

	private static void onUi(Runnable action) throws Exception {
		FutureTask<Void> invocation = new FutureTask<>(action, null);
		PlatformUI.getWorkbench().getDisplay().syncExec(invocation);
		invocation.get(5, TimeUnit.SECONDS);
	}

	private static final class Fixture {
		final Display display = Display.getCurrent();
		final Shell shell = new Shell(display);
		int filters;
		int refreshes;

		final WeekViewContentProvider provider = new WeekViewContentProvider() {
			@Override
			protected void filter() {
				assertSame("Filtering must run on the UI thread", display, Display.getCurrent());
				filters++;
				filtered = new HashSet<>();
			}
		};

		Fixture() {
			provider.inputChanged(new Viewer() {
				@Override
				public Control getControl() {
					assertSame("Notification threads must not dereference the viewer", display, Display.getCurrent());
					return shell;
				}

				@Override
				public void refresh() {
					assertSame("Refresh must run on the UI thread", display, Display.getCurrent());
					refreshes++;
				}

				@Override
				public Object getInput() { return null; }
				@Override
				public ISelection getSelection() { return StructuredSelection.EMPTY; }
				@Override
				public void setInput(Object input) { }
				@Override
				public void setSelection(ISelection selection, boolean reveal) { }
			}, null, null);
		}

		void dispose() {
			provider.dispose();
			shell.dispose();
		}
	}
}
