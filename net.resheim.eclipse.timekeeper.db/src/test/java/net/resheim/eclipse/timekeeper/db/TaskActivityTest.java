package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Task;

class TaskActivityTest {
	@Test
	void nativeTasksHaveProviderIndependentIdentities() {
		Task first = new Task("Write the server API");
		Task second = new Task("Write the server API");

		assertNotNull(UUID.fromString(first.getId()));
		assertNotEquals(first.getId(), second.getId());
		assertEquals("Write the server API", first.getTaskSummary());
		assertTrue(first.getExternalReferences().isEmpty());
		assertNull(first.getRepositoryUrl());
		assertNull(first.getTaskId());
		assertNull(first.getMylynTask());
	}

	@Test
	void externalReferencesAreOptionalAndDoNotReplaceNativeIdentity() {
		Task task = new Task("Linked task");
		String nativeId = task.getId();

		var jira = task.linkExternalTask("jira", "https://issues.example", "TIME-42", null);
		var refreshed = task.linkExternalTask(" jira ", " https://issues.example ", " TIME-42 ",
				"https://issues.example/browse/TIME-42");
		task.linkExternalTask("github", "example/timekeeper", "215",
				"https://github.com/example/timekeeper/issues/215");

		assertSame(jira, refreshed);
		assertEquals(nativeId, task.getId());
		assertEquals(2, task.getExternalReferences().size());
		assertEquals("https://issues.example/browse/TIME-42", jira.getExternalUrl());
		assertEquals("TIME-42", task.getTaskId());
	}

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
