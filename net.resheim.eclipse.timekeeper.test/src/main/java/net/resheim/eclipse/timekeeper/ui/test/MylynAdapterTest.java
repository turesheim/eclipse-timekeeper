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
