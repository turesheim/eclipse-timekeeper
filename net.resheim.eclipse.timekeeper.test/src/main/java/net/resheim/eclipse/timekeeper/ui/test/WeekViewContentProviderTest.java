package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.HashSet;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.BeforeClass;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
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
