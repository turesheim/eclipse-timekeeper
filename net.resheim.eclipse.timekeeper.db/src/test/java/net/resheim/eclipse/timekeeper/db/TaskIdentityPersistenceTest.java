package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.persistence.EntityManager;
import javax.persistence.RollbackException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.resheim.eclipse.timekeeper.db.model.Task;

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

	private Task linkedTask(String summary) {
		Task task = new Task(summary);
		task.linkExternalTask("jira", "https://issues.example", "TIME-42",
				"https://issues.example/browse/TIME-42");
		return task;
	}
}
