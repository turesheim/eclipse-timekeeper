package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import javax.persistence.EntityManager;
import javax.persistence.RollbackException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.db.model.OwnerIdentity;

class TaskIdentityPersistenceTest {
	private EntityManager manager;

	@AfterEach
	void close() {
		PersistenceHelper.close(manager);
	}

	@Test
	void externalIdentityCanOnlyBelongToOneTask() {
		manager = PersistenceHelper.getEntityManager();
		Task first = linkedTask("First");
		Task second = linkedTask("Second");
		manager.getTransaction().begin();
		manager.persist(first);
		manager.getTransaction().commit();

		manager.getTransaction().begin();
		manager.persist(second);
		assertThrows(RollbackException.class, () -> manager.getTransaction().commit());
		assertEquals(first.getTaskId(), second.getTaskId());
		assertNotEquals(first.getId(), second.getId());
	}

	@Test
	void staleAggregateUpdateIsRejected() {
		manager = PersistenceHelper.getEntityManager();
		Task task = new Task("Initial summary");
		manager.getTransaction().begin();
		manager.persist(task);
		manager.getTransaction().commit();
		manager.clear();

		EntityManager concurrent = manager.getEntityManagerFactory().createEntityManager();
		try {
			Task first = manager.find(Task.class, task.getId());
			Task stale = concurrent.find(Task.class, task.getId());
			manager.getTransaction().begin();
			first.setTaskSummary("First update");
			manager.getTransaction().commit();

			concurrent.getTransaction().begin();
			stale.setTaskSummary("Stale update");
			assertThrows(RollbackException.class, () -> concurrent.getTransaction().commit());
		} finally {
			concurrent.close();
		}
	}

	@Test
	void activityOwnerSurvivesPersistenceWithoutAnExternalTaskProvider() {
		manager = PersistenceHelper.getEntityManager();
		Task task = new Task("Server-created task");
		task.startActivity(new OwnerIdentity("user:alice"), Instant.parse("2026-09-21T08:00:00Z"));
		task.endActivity(Instant.parse("2026-09-21T09:00:00Z"));
		manager.getTransaction().begin();
		manager.persist(task);
		manager.getTransaction().commit();
		manager.clear();

		Task reloaded = manager.find(Task.class, task.getId());
		assertEquals(new OwnerIdentity("user:alice"), reloaded.getActivities().get(0).getOwner());
		assertEquals(Instant.parse("2026-09-21T08:00:00Z"), reloaded.getActivities().get(0).getStart());
	}

	private Task linkedTask(String summary) {
		Task task = new Task(summary);
		task.linkExternalTask("jira", "https://issues.example", "TIME-42",
				"https://issues.example/browse/TIME-42");
		return task;
	}
}
