package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.mylyn.MylynMetadata;
import net.resheim.eclipse.timekeeper.mylyn.MylynTaskSynchronizer;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/**
 * Provider identity survives replacement of Mylyn objects and late provider
 * events.
 */
@SuppressWarnings("restriction")
public class MylynAdapterTest {
	@Test
	public void workweekSwitchesBetweenNativeAndLinkedTaskActivation() throws Exception {
		var bot = new SWTWorkbenchBot();
		bot.waitUntil(new DefaultCondition() {
			@Override public boolean test() { return TimekeeperPlugin.getDefault().isReady(); }
			@Override public String getFailureMessage() { return "Database not ready"; }
		});
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var priorActivity = service.activeActivity(OwnerId.LOCAL);
		org.eclipse.mylyn.tasks.core.ITask[] priorMylyn = new org.eclipse.mylyn.tasks.core.ITask[1];
		var first = service.createTask(new CreateTask(Optional.empty(), "Linked activation bridge", Optional.empty()));
		var second = service.createTask(new CreateTask(Optional.empty(), "Native activation bridge", Optional.empty()));
		var list = org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getTaskList();
		var projection = new LocalTask(String.valueOf(list.getNextLocalTaskId()), first.summary());
		projection.setAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID, first.id().value().toString());
		try {
			onUi(() -> {
				priorMylyn[0] = TasksUi.getTaskActivityManager().getActiveTask();
				if (priorMylyn[0] != null) TasksUi.getTaskActivityManager().deactivateTask(priorMylyn[0]);
				service.activeActivity(OwnerId.LOCAL).ifPresent(activity ->
						service.stopActivity(new StopActivity(activity.id(), activity.version())));
				list.addTask(projection, list.getDefaultCategory());
				try {
					var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
					window.getShell().setSize(1100, 750);
					window.getShell().forceActive();
					window.getActivePage().showView(net.resheim.eclipse.timekeeper.ui.views.WorkWeekView.VIEW_ID);
				} catch (Exception failure) { throw new AssertionError(failure); }
			});
			var view = bot.viewByTitle("Workweek");
			view.setFocus();
			view.toolbarButton("Show current week").click();
			assertTrue(MylynIntegration.getDefault().isLinked(first));
			assertFalse(MylynIntegration.getDefault().isLinked(second));
			view.bot().treeWithId("workweek-editor-tree").getTreeItem(first.summary()).contextMenu("Activate task").click();
			var linkedInterval = service.activeActivity(OwnerId.LOCAL).orElseThrow();
			assertEquals(first.id(), linkedInterval.taskId());
			assertTrue(projection.isActive());
			view.bot().treeWithId("workweek-editor-tree").getTreeItem(second.summary()).contextMenu("Activate task").click();
			var nativeInterval = service.activeActivity(OwnerId.LOCAL).orElseThrow();
			assertEquals(second.id(), nativeInterval.taskId());
			assertTrue(service.activity(linkedInterval.id()).orElseThrow().end().isPresent());
			assertFalse("The previous Mylyn task must be deactivated", projection.isActive());
			assertNull(TasksUi.getTaskActivityManager().getActiveTask());
			view.bot().treeWithId("workweek-editor-tree").getTreeItem(first.summary()).contextMenu("Activate task").click();
			assertEquals(first.id(), service.activeActivity(OwnerId.LOCAL).orElseThrow().taskId());
			assertTrue(service.activity(nativeInterval.id()).orElseThrow().end().isPresent());
			assertTrue(projection.isActive());
			view.bot().treeWithId("workweek-editor-tree").getTreeItem(first.summary()).contextMenu("Deactivate task").click();
			assertFalse(projection.isActive());
			assertTrue(service.activeActivity(OwnerId.LOCAL).isEmpty());
			view.bot().treeWithId("workweek-editor-tree").getTreeItem(first.summary()).contextMenu("Activate task").click();
			var manuallyStopped = service.activeActivity(OwnerId.LOCAL).orElseThrow();
			// Stopping an interval through the service does not change Mylyn's flag.
			service.stopActivity(new StopActivity(manuallyStopped.id(), manuallyStopped.version()));
			assertTrue(projection.isActive());
			view.bot().treeWithId("workweek-editor-tree").getTreeItem(second.summary()).contextMenu("Activate task").click();
			assertEquals(second.id(), service.activeActivity(OwnerId.LOCAL).orElseThrow().taskId());
			assertFalse("Native activation must clear Mylyn even when its interval was already stopped", projection.isActive());
			assertNull(TasksUi.getTaskActivityManager().getActiveTask());
			view.bot().treeWithId("workweek-editor-tree").getTreeItem(second.summary()).contextMenu("Deactivate task").click();
			assertTrue(service.activeActivity(OwnerId.LOCAL).isEmpty());
		} finally {
			onUi(() -> { if (projection.isActive()) TasksUi.getTaskActivityManager().deactivateTask(projection); });
			for (var task : java.util.List.of(first, second)) {
				service.activities(new ActivityQuery(Instant.EPOCH, Instant.now().plusSeconds(60), Optional.empty(),
						Optional.of(task.id()), Optional.empty(), TimekeeperUiPlugin.getCalendarZone())).activities()
						.forEach(activity -> service.deleteActivity(new DeleteActivity(activity.id(), activity.version())));
				service.task(task.id()).ifPresent(current -> service.deleteTask(new DeleteTask(current.id(), current.version())));
			}
			onUi(() -> {
				if (list.getAllTasks().contains(projection)) list.deleteTask(projection);
				priorActivity.ifPresent(original -> service.activity(original.id()).ifPresent(current ->
						service.updateActivity(new UpdateActivity(current.id(), current.version(), original.start(),
								original.end(), original.summary(), original.labelIds()))));
				if (priorMylyn[0] != null) TasksUi.getTaskActivityManager().activateTask(priorMylyn[0]);
			});
		}
	}

	@Test
	public void synchronizationWaitsForConnectingClientAndResumesWithoutWarning() throws Exception {
		var bot = new SWTWorkbenchBot();
		bot.waitUntil(new DefaultCondition() {
			@Override public boolean test() { return TimekeeperPlugin.getDefault().isReady(); }
			@Override public String getFailureMessage() { return "Database not ready"; }
		});
		var plugin = TimekeeperUiPlugin.getDefault();
		var service = plugin.getTimekeeperService();
		var original = MylynIntegration.getDefault().synchronizer();
		var available = new java.util.concurrent.atomic.AtomicBoolean();
		var attempts = new java.util.concurrent.atomic.AtomicInteger();
		var warnings = new java.util.concurrent.CopyOnWriteArrayList<org.eclipse.core.runtime.IStatus>();
		org.eclipse.core.runtime.ILogListener listener = (status, bundle) -> {
			if (status.getPlugin().equals(MylynIntegration.PLUGIN_ID)
					&& status.getMessage().contains("Could not synchronize Mylyn"))
				warnings.add(status);
		};
		var connecting = (net.resheim.eclipse.timekeeper.service.TimekeeperService) java.lang.reflect.Proxy
				.newProxyInstance(service.getClass().getClassLoader(),
						new Class<?>[] { net.resheim.eclipse.timekeeper.service.TimekeeperService.class },
						(proxy, method, args) -> {
							if (!available.get()) {
								attempts.incrementAndGet();
								throw net.resheim.eclipse.timekeeper.service.ServiceException.conflict("database",
										"Timekeeper database is not ready");
							}
							try {
								return method.invoke(service, args);
							} catch (java.lang.reflect.InvocationTargetException failure) {
								throw failure.getCause();
							}
						});
		MylynTaskSynchronizer[] probe = new MylynTaskSynchronizer[1];
		boolean[] previous = new boolean[1];
		onUi(original::stop);
		var task = service.createTask(new CreateTask(Optional.empty(), "Available after connecting", Optional.empty()));
		org.eclipse.core.runtime.Platform.addLogListener(listener);
		try {
			onUi(() -> {
				var preferences = plugin.getPreferenceStore();
				String key = net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants.PROJECT_NATIVE_TASKS;
				previous[0] = preferences.getBoolean(key);
				preferences.setValue(key, true);
				probe[0] = new MylynTaskSynchronizer(connecting, PlatformUI.getWorkbench().getDisplay());
				probe[0].start();
			});
			bot.waitUntil(new DefaultCondition() {
				@Override public boolean test() { return attempts.get() > 0; }
				@Override public String getFailureMessage() { return "Client was not polled"; }
			});
			assertTrue("Connecting is an expected state", warnings.isEmpty());
			assertTrue("No projection before the client connects", MylynIntegration.getDefault().linked(task).isEmpty());
			available.set(true);
			bot.waitUntil(new DefaultCondition() {
				@Override public boolean test() {
					return MylynIntegration.getDefault().linked(task).isPresent();
				}
				@Override public String getFailureMessage() { return "Synchronization did not resume"; }
			});
			assertTrue(warnings.isEmpty());
		} finally {
			onUi(() -> {
				if (probe[0] != null) probe[0].stop();
				plugin.getPreferenceStore().setValue(
						net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants.PROJECT_NATIVE_TASKS, previous[0]);
				original.start();
			});
			org.eclipse.core.runtime.Platform.removeLogListener(listener);
			service.task(task.id()).ifPresent(current -> service.deleteTask(new DeleteTask(current.id(), current.version())));
		}
	}

	@Test
	public void reconnectReusesNativeIdentityAndPreservesHistory() throws Exception {
		var bot = new SWTWorkbenchBot();
		bot.waitUntil(new DefaultCondition() {
			@Override
			public boolean test() {
				return TimekeeperPlugin.getDefault().isReady();
			}

			@Override
			public String getFailureMessage() {
				return "Database not ready";
			}
		});
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var task = service.createTask(new CreateTask(Optional.empty(), "Created by server", Optional.empty()));
		var history = service
				.createActivity(new CreateActivity(task.id(), OwnerId.LOCAL, Instant.now().minusSeconds(3600),
						Optional.of(Instant.now().minusSeconds(1800)), "Recorded on server", Set.of()));
		var external = new LocalTask("987654", "Reconnected provider summary");
		external.setUrl("https://example.test/reconnected");
		var key = new ExternalTaskReference("local", MylynMetadata.getRepositoryUrl(external), external.getTaskId(),
				Optional.empty());
		service.linkExternalReference(
				new LinkExternalReference(task.id(), service.task(task.id()).orElseThrow().version(), key));
		try {
			onUi(() -> {
				var adapter = MylynIntegration.getDefault();
				var connected = adapter.resolve(external);
				assertEquals(task.id(), connected.id());
				assertTrue(connected.projectId().isEmpty());
				assertEquals(external.getSummary(), connected.summary());
				assertEquals(Optional.of(external.getUrl()), connected.url());
				assertEquals(task.id().value().toString(),
						external.getAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID));
				assertEquals(history, service.activity(history.id()).orElseThrow());
				var replacement = new LocalTask(external.getTaskId(), "Replacement Mylyn object");
				// The stable external key works even without a Mylyn UUID attribute.
				assertEquals(task.id(), adapter.resolve(replacement).id());
				assertEquals(history, service.activity(history.id()).orElseThrow());
			});
		} finally {
			service.deleteActivity(
					new DeleteActivity(history.id(), service.activity(history.id()).orElseThrow().version()));
			service.deleteTask(new DeleteTask(task.id(), service.task(task.id()).orElseThrow().version()));
		}
	}

	@Test
	public void lateAndDuplicateDeactivationDoesNotStopSuccessor() throws Exception {
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		var first = service.createTask(new CreateTask(Optional.empty(), "First activation", Optional.empty()));
		var second = service.createTask(new CreateTask(Optional.empty(), "Successor activation", Optional.empty()));
		var firstProjection = new LocalTask("987655", first.summary());
		var secondProjection = new LocalTask("987656", second.summary());
		firstProjection.setAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID, first.id().value().toString());
		secondProjection.setAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID, second.id().value().toString());
		org.eclipse.mylyn.tasks.core.ITask[] previous = new org.eclipse.mylyn.tasks.core.ITask[1];
		try {
			onUi(() -> {
				previous[0] = TasksUi.getTaskActivityManager().getActiveTask();
				if (previous[0] != null)
					TasksUi.getTaskActivityManager().deactivateTask(previous[0]);
				var adapter = MylynIntegration.getDefault();
				adapter.activated(firstProjection);
				var original = service.activeActivity(OwnerId.LOCAL).orElseThrow();
				adapter.activated(firstProjection);
				assertEquals(original, service.activeActivity(OwnerId.LOCAL).orElseThrow());
				adapter.activated(secondProjection);
				var successor = service.activeActivity(OwnerId.LOCAL).orElseThrow();
				assertEquals(second.id(), successor.taskId());
				assertTrue(service.activity(original.id()).orElseThrow().end().isPresent());
				adapter.deactivated(firstProjection);
				adapter.deactivated(firstProjection);
				assertEquals(successor, service.activeActivity(OwnerId.LOCAL).orElseThrow());
				adapter.deactivated(secondProjection);
				adapter.deactivated(secondProjection);
				assertTrue(service.activeActivity(OwnerId.LOCAL).isEmpty());
			});
		} finally {
			for (var task : java.util.List.of(first, second)) {
				service.activities(new ActivityQuery(Instant.EPOCH, Instant.now().plusSeconds(60), Optional.empty(),
						Optional.of(task.id()), Optional.empty(), TimekeeperUiPlugin.getCalendarZone())).activities()
						.forEach(activity -> service
								.deleteActivity(new DeleteActivity(activity.id(), activity.version())));
				service.deleteTask(new DeleteTask(task.id(), service.task(task.id()).orElseThrow().version()));
			}
			onUi(() -> {
				if (previous[0] != null)
					TasksUi.getTaskActivityManager().activateTask(previous[0]);
			});
		}
	}

	private static void onUi(Runnable action) throws Exception {
		var task = new FutureTask<Void>(action, null);
		PlatformUI.getWorkbench().getDisplay().asyncExec(task);
		task.get(20, TimeUnit.SECONDS);
	}
}
