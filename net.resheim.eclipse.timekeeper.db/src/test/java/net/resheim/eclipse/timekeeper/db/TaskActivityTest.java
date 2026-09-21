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
}
