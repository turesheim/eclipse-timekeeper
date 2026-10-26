package net.resheim.eclipse.timekeeper.nativeui.test;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/**
 * Idle-time corrections persist through the client, including optimistic
 * conflict handling.
 */
public class IdleClientTest {
	@Test
	public void disregardIdleTimePersistsEndAndRestartsNativeActivity() throws Exception {
		checkIdleReturn(false, false, true);
	}

	@Test
	public void concurrentEditWhileIdleDialogIsOpenRemainsActive() throws Exception {
		checkIdleReturn(true, false, true);
	}

	@Test
	public void awayWithAutoStopDisabledResumesNativeTracking() throws Exception {
		checkIdleReturn(false, true, false);
	}

	@Test
	public void awayWithAutoStopEnabledEndsNativeTracking() throws Exception {
		checkIdleReturn(false, true, true);
	}

	private void checkIdleReturn(boolean concurrentEdit, boolean away, boolean autoStop) throws Exception {
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
		var plugin = TimekeeperUiPlugin.getDefault();
		var service = plugin.getTimekeeperService();
		String preference = net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants.DEACTIVATE_WHEN_AWAY;
		boolean previousAutoStop = plugin.getPreferenceStore().getBoolean(preference);
		var task = service.createTask(new CreateTask(Optional.empty(), "Native idle return", Optional.empty()));
		var label = service.createLabel(new CreateLabel("Idle client label", Optional.empty()));
		Instant start = Instant.now().minusSeconds(1200);
		var activity = service.createActivity(new CreateActivity(task.id(), OwnerId.LOCAL, start, Optional.empty(),
				"Preserve idle summary", Set.of(label.id())));
		// Creation can return today's part when the requested interval crosses
		// midnight. Keep the idle sample inside that actual active interval.
		Instant lastActive = activity.start().plus(Duration.between(activity.start(), Instant.now()).dividedBy(2));
		var handler = TimekeeperUiPlugin.class.getDeclaredMethod("handleReactivation", long.class);
		handler.setAccessible(true);
		Field idle = field("lastIdleTimeMillis");
		Field sampled = field("sampledActivity");
		Field lastTime = field("lastActiveTime");
		FutureTask<Void> returnFromIdle = new FutureTask<>(() -> {
			plugin.getPreferenceStore().setValue(preference, autoStop);
			idle.setLong(null, (away ? 40 : 10) * 60_000L);
			sampled.set(plugin, activity.id());
			lastTime.set(plugin, lastActive);
			handler.invoke(plugin, 0L);
			return null;
		});
		try {
			PlatformUI.getWorkbench().getDisplay().asyncExec(returnFromIdle);
			if (away) {
				if (autoStop) {
					bot.shell("Activity automatically stopped").activate();
					bot.button("OK").click();
				}
			} else {
				bot.shell("Disregard idle time?").activate();
				if (concurrentEdit) {
					service.updateActivity(new UpdateActivity(activity.id(), activity.version(), activity.start(),
							activity.end(), "Concurrent idle edit", activity.labelIds()));
				}
				bot.button("Yes").click();
				if (concurrentEdit) {
					bot.shell("Could not update idle activity").activate();
					bot.button("OK").click();
				}
			}
			returnFromIdle.get(20, TimeUnit.SECONDS);
			var saved = service.activity(activity.id()).orElseThrow();
			assertEquals(activity.start(), saved.start());
			assertEquals(activity.labelIds(), saved.labelIds());
			if (concurrentEdit) {
				assertEquals("Concurrent idle edit", saved.summary());
				assertTrue(saved.end().isEmpty());
				assertEquals(activity.id(), service.activeActivity(OwnerId.LOCAL).orElseThrow().id());
			} else {
				assertEquals("Preserve idle summary", saved.summary());
				assertEquals(Optional.of(lastActive), saved.end());
				if (away && autoStop) {
					assertTrue(service.activeActivity(OwnerId.LOCAL).isEmpty());
					return;
				}
				var restarted = service.activeActivity(OwnerId.LOCAL).orElseThrow();
				assertNotEquals(activity.id(), restarted.id());
				assertEquals(task.id(), restarted.taskId());
				assertTrue(restarted.start().isAfter(lastActive));
			}
		} finally {
			var query = new net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery(Instant.EPOCH,
					Instant.now().plusSeconds(60), Optional.empty(), Optional.of(task.id()), Optional.empty(),
					TimekeeperUiPlugin.getCalendarZone());
			service.activities(query).activities()
					.forEach(value -> service.deleteActivity(new DeleteActivity(value.id(), value.version())));
			service.deleteTask(new DeleteTask(task.id(), service.task(task.id()).orElseThrow().version()));
			service.deleteLabel(new DeleteLabel(label.id(), service.label(label.id()).orElseThrow().version()));
			var cleanup = new FutureTask<Void>(() -> {
				plugin.getPreferenceStore().setValue(preference, previousAutoStop);
				idle.setLong(null, 0L);
				sampled.set(plugin, null);
				lastTime.set(plugin, null);
				return null;
			});
			PlatformUI.getWorkbench().getDisplay().asyncExec(cleanup);
			cleanup.get(20, TimeUnit.SECONDS);
		}
	}

	private static Field field(String name) throws Exception {
		var field = TimekeeperUiPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
