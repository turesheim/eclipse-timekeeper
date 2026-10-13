/*******************************************************************************
 * Copyright (c) 2016-2020 Torkild Ulvøy Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db;

import static org.junit.Assert.fail;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Task;

@SuppressWarnings("restriction")
public class SharedStorageTest {
	
	private EntityManager entityManager;
	

	@Before
	public void before() {
		entityManager = PersistenceHelper.getEntityManager();
	}

	@After
	public void after() {
		PersistenceHelper.close(entityManager);
	}

	private void persist(Task ttask) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		entityManager.persist(ttask);
		transaction.commit();
		// Force subsequent reads through the database, not either JPA cache.
		entityManager.clear();
		entityManager.getEntityManagerFactory().getCache().evictAll();
	}

	@Test
	public void testSimpleTaskPersistence() {
		Task ttask = new Task("Test task");
		Instant now = Instant.now();
		Activity a = new Activity(ttask,now);
		ttask.addActivity(a);
		a.setStart(now.minus(Duration.ofHours(1)));
		a.setEnd(now);
		persist(ttask);

		// now attempt to load the task from the persistent storage
		String id = ttask.getId();
		Task dbTask = entityManager.find(Task.class, id);
		Assert.assertNotNull("Persisted task must be reloadable", dbTask);
		// Test the single task
		if (dbTask instanceof Task) {
			List<Activity> activities = ((Task) dbTask).getActivities();
			Assert.assertEquals(1, activities.size());
			Activity activity = activities.get(0);
			// duration should be one day
			Assert.assertEquals(Duration.ofHours(1), activity.getDuration());
		}
	}

	/**
	 * Verifies that the duration of several activities is calculated correctly
	 * after de-serializing from the persisted storage.
	 */
	@Test
	public void testTrackedTask_getDuration() {
		Task task = new Task("Test task");

		Instant start = Instant.parse("2016-03-14T22:00:00Z");
		Instant start2 = Instant.parse("2016-03-16T00:00:00Z");
		Activity a1 = new Activity();
		task.addActivity(a1);

		a1.setStart(start);
		a1.setEnd(start.plus(Duration.ofHours(4)));

		Activity a2 = new Activity();
		task.addActivity(a2);
		a2.setStart(start.minus(Duration.ofHours(3)));
		a2.setEnd(start.minus(Duration.ofHours(1)));

		// activity that lasts more than a day
		Activity a3 = new Activity();
		task.addActivity(a3);
		a3.setStart(start2);
		a3.setEnd(start2.plus(Duration.ofHours(25)));

		// store
		persist(task);

		// now attempt to load the task from the persistent storage
		String id = task.getId();
		Task dbTask = entityManager.find(Task.class, id);

		// verify that the accumulated duration is correct
		if (dbTask instanceof Task) {
			Task trackedTask = (Task) dbTask;
			// total work on the 14th of March should be 4 hours
			Assert.assertEquals(Duration.ofHours(4),
					trackedTask.getDuration(LocalDate.of(2016, 3, 14), ZoneOffset.UTC));
			// total work on the 16th of March should be 24 hours
			Assert.assertEquals(Duration.ofHours(24),
					trackedTask.getDuration(LocalDate.of(2016, 3, 16), ZoneOffset.UTC));
		} else fail("Could not find task");
	}

}
