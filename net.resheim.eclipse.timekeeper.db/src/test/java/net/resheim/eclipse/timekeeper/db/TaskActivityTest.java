package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Task;

class TaskActivityTest {
	@Test
	void endingActivityUsesTheSuppliedTimestamp() {
		Task task = new Task();
		Activity activity = task.startActivity();
		LocalDateTime lastActive = LocalDateTime.of(2026, 9, 21, 12, 34, 56);

		task.endActivity(lastActive);

		assertEquals(lastActive, activity.getEnd());
		assertTrue(task.getCurrentActivity().isEmpty());
	}

	@Test
	void interruptedActivityRecoveryUsesLastTickAndNeverEndsInTheFuture() {
		LocalDateTime start = LocalDateTime.of(2026, 9, 21, 14, 28, 38);
		LocalDateTime now = start.plusMinutes(10);

		assertEquals(start.plusMinutes(7),
				TimekeeperPlugin.recoveredActivityEnd(start, start.plusMinutes(7), now, 0));
		assertEquals(now,
				TimekeeperPlugin.recoveredActivityEnd(start, now.plusMinutes(20), now, 0));
		assertEquals(start.plusMinutes(4),
				TimekeeperPlugin.recoveredActivityEnd(start, start.minusMinutes(1), now, 240_000));
		assertEquals(now,
				TimekeeperPlugin.recoveredActivityEnd(start, null, now, 900_000));
		assertEquals(start,
				TimekeeperPlugin.recoveredActivityEnd(start, start.plusMinutes(7), start.minusMinutes(1), 0));
	}
}
