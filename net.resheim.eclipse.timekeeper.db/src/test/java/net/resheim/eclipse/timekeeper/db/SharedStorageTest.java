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
import java.time.LocalDateTime;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.Query;

import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.tasks.core.ITask;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.db.model.GlobalTaskId;

@SuppressWarnings("restriction")
public class SharedStorageTest {
	
	private static EntityManager entityManager;
	
	public static final String KEY_VALUELIST_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$

	public static final String KV_SEPARATOR = "="; //$NON-NLS-1$

	public static final String PAIR_SEPARATOR = ";"; //$NON-NLS-1$

	//------------------------------------------------------------------------
	// Migration test
	//------------------------------------------------------------------------
	static long remainder = 0;
	
	static {
		entityManager = PersistenceHelper.getEntityManager(); 
	}

	synchronized static void accumulateTime(ITask task, String dateString, long millis) {
		millis = millis + remainder;
		long seconds = millis / 1000;
		remainder = millis - (seconds * 1000);
		if (seconds == 0) {
			return;
		}
		String accumulatedString = getValue(task, dateString);
		if (accumulatedString != null) {
			long accumulated = Long.parseLong(accumulatedString);
			accumulated = accumulated + seconds;
			setValue(task, dateString, Long.toString(accumulated));
		} else {
			setValue(task, dateString, Long.toString(seconds));
		}
	}

	/**
	 * 
	 * @param task
	 * @param key date "start" or "tick"
	 * @return
	 */
	public static String getValue(ITask task, String key) {
		String attribute = task.getAttribute(KEY_VALUELIST_ID);
		if (attribute == null) {
			return null;
		} else {
			String[] split = attribute.split(PAIR_SEPARATOR);
			for (String string : split) {
				if (string.length() > 0) {
					String[] kv = string.split(KV_SEPARATOR);
					if (kv[0].equals(key)) {
						return kv[1];
					}
				}
			}
		}
		return null;
	}

	/**
	 * Sets a value in the Mylyn database for the specified task.
	 *
	 * @param task
	 *            the task to set a value for
	 * @param key
	 *            the key for the value
	 * @param value
	 *            the value associated with the key
	 */
	public static void setValue(ITask task, String key, String value) {
		StringBuilder sb = new StringBuilder();
		String attribute = task.getAttribute(KEY_VALUELIST_ID);
		if (attribute == null || attribute.length() == 0) {
			sb.append(key);
			sb.append('=');
			sb.append(value);
		} else {
			String[] split = attribute.split(PAIR_SEPARATOR);
			boolean found = false;
			for (int i = 0; i < split.length; i++) {
				String string = split[i];
				String[] kv = string.split(KV_SEPARATOR);
				if (kv[0].equals(key)) {
					kv[1] = value;
					found = true;
				}
				if (kv.length == 2) {
					sb.append(kv[0]);
					sb.append('=');
					sb.append(kv[1]);
					if (i < split.length - 1) {
						sb.append(';');
					}
				}
			}
			if (!found) {
				sb.append(';');
				sb.append(key);
				sb.append('=');
				sb.append(value);
	
			}
		}
		task.setAttribute(KEY_VALUELIST_ID, sb.toString());
	}
	
	private LocalTask mylynTask;

	@Before
	public void before() {
		mylynTask = new LocalTask("1", "TestmylynTask");
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
		Query createQuery = entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE;TRUNCATE TABLE ACTIVITY;TRUNCATE TABLE TASK;SET REFERENTIAL_INTEGRITY TRUE");
		createQuery.executeUpdate();
		transaction.commit();
	}

	private void persist(Task ttask) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		entityManager.persist(ttask);
		transaction.commit();
	}

	@Test
	public void testSimpleTaskPersistence() {
		Task ttask = new Task(mylynTask);
		LocalDateTime now = LocalDateTime.now();
		Activity a = new Activity(ttask,now);
		ttask.addActivity(a);
		a.setStart(now.minus(Duration.ofHours(1)));
		a.setEnd(now);
		persist(ttask);

		// now attempt to load the task from the persistent storage
		GlobalTaskId id = new GlobalTaskId(ttask.getRepositoryUrl(), ttask.getTaskId());
		Task dbTask = entityManager.find(Task.class, id);
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
		Task task = new Task(mylynTask);

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
		GlobalTaskId id = new GlobalTaskId(task.getRepositoryUrl(), task.getTaskId());
		Task dbTask = entityManager.find(Task.class, id);

		// verify that the accumulated duration is correct
		if (dbTask instanceof Task) {
			Task trackedTask = (Task) dbTask;
			// total work on the 14th of March should be 4 hours
			Assert.assertEquals(Duration.ofHours(4), trackedTask.getDuration(start.toLocalDate()));
			// total work on the 16th of March should be 24 hours
			Assert.assertEquals(Duration.ofHours(24), trackedTask.getDuration(start2.toLocalDate()));
		} else fail("Could not find task");
	}

}
