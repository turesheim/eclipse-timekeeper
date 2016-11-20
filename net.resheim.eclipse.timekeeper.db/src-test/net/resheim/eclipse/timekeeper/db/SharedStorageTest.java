/*******************************************************************************
 * Copyright (c) 2016 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.Query;

import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("restriction")
public class SharedStorageTest {
	
	private static EntityManager entityManager;
	
	static {
		entityManager = PersistenceHelper.getEntityManager(); 
	}

	private LocalTask task_1;

	@Before
	public void before() {
		task_1 = new LocalTask("1", "TestTask_1");
	}

	@After
	public void after() {
		// empty all tables
		EntityTransaction transaction = entityManager.getTransaction();
		if (transaction.isActive()) {
			transaction.rollback();
		}
		// clean up after running tests
		transaction.begin();
		Query createQuery = entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE;TRUNCATE TABLE ACTIVITY;TRUNCATE TABLE TRACKEDTASK;SET REFERENTIAL_INTEGRITY TRUE");
		createQuery.executeUpdate();
		transaction.commit();
	}
	

	@Test
	public void testSimpleTaskPersistence() {
		TrackedTask task = new TrackedTask(task_1);
		LocalDateTime now = LocalDateTime.now();
		Activity a = new Activity(task,now);
		task.addActivity(a);
		a.setStart(now.minus(Duration.ofHours(1)));
		a.setEnd(now);
		persist(task);

		// now attempt to load the task from the persistent storage
		Query query = entityManager.createQuery("SELECT task FROM TrackedTask task WHERE task.id = :id", String.class);
		query.setParameter("id", task.getId());
		Object singleResult = query.getSingleResult();
		// Test the single task
		if (singleResult instanceof TrackedTask) {
			List<Activity> activities = ((TrackedTask) singleResult).getActivities();
			Assert.assertEquals(1, activities.size());
			Activity activity = activities.get(0);
			// duration should be one day
			Assert.assertEquals(Duration.ofHours(1), activity.getDuration());
		}
	}

	private void persist(TrackedTask ttask) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		entityManager.persist(ttask);
		transaction.commit();
	}

	/**
	 * Verifies that the duration of several activities is calculated correctly
	 * after de-serializing from the persisted storage.
	 */
	@Test
	public void testTrackedTask_getDuration() {
		TrackedTask task = new TrackedTask(task_1);

		LocalDateTime start = LocalDateTime.of(2016, 3, 14, 22, 0);
		LocalDateTime start2 = LocalDateTime.of(2016, 3, 16, 0, 0);
		Activity a1 = new Activity();
		task.addActivity(a1);

		a1.setStart(start);
		a1.setEnd(start.plusHours(4));

		Activity a2 = new Activity();
		task.addActivity(a2);
		a2.setStart(start.minus(Duration.ofHours(3)));
		a2.setEnd(start.minus(Duration.ofHours(1)));

		// activity that lasts more than a day
		Activity a3 = new Activity();
		task.addActivity(a3);
		a3.setStart(start2);
		a3.setEnd(start2.plusHours(25));

		// store
		persist(task);

		// now attempt to load the task from the persistent storage
		Query query = entityManager.createQuery("SELECT task FROM TrackedTask task WHERE task.id = :id", String.class);
		query.setParameter("id", task.getId());
		Object singleResult = query.getSingleResult();

		// verify that the accumulated duration is correct
		if (singleResult instanceof TrackedTask) {
			TrackedTask trackedTask = (TrackedTask) singleResult;
			// total work on the 14th of March should be 4 hours
			Assert.assertEquals(Duration.ofHours(4), trackedTask.getDuration(start.toLocalDate()));
			// total work on the 16th of March should be 24 hours
			Assert.assertEquals(Duration.ofHours(24), trackedTask.getDuration(start2.toLocalDate()));
		} else fail("Could not find task");
	}
	
}
