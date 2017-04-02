/*******************************************************************************
 * Copyright (c) 2017 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db;

import java.time.LocalDate;
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

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;

/**
 * Various integration tests related to the shared storage.
 */
@SuppressWarnings("restriction")
public class SharedStorageIntegrationTest {

	public static final String KEY_VALUELIST_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$
	public static final String KV_SEPARATOR = "="; //$NON-NLS-1$
	public static final String PAIR_SEPARATOR = ";"; //$NON-NLS-1$

	private static EntityManager entityManager;
	
	static {
		entityManager = PersistenceHelper.getEntityManager(); 
	}

	private LocalTask task;

	@Before
	public void before() {
		task = new LocalTask("1", "TestTask");
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

	//------------------------------------------------------------------------
	// Migration test
	//------------------------------------------------------------------------
	static long remainder = 0;
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
	 * Creates an "old style" entry in the Mylyn store and migrates this to the new SQL database. 
	 */
	@Test
	public void testMigrateFromMylynKVStore() {
		LocalDate now = LocalDate.now();
		accumulateTime(task, now.toString(), 3600*10);
		accumulateTime(task, now.toString(), 3600*10); // 72 seconds today
		accumulateTime(task, now.minusDays(1).toString(), 3600*30); // 108 seconds yesterday 
		accumulateTime(task, now.minusMonths(1).toString(), 3600*60); // 216 seconds a month ago
		
		TrackedTask ttask = new TrackedTask(task);

		// do the migration
		ttask.migrate();

		// store the newly created entity
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		entityManager.persist(ttask);
		transaction.commit();

		// ensure that we have the correct values after migrating
		Query query = entityManager.createQuery("SELECT task FROM TrackedTask task WHERE task.id = :id", String.class);
		query.setParameter("id", ttask.getId());
		Object singleResult = query.getSingleResult();

		if (singleResult instanceof TrackedTask) {
			List<Activity> activities = ((TrackedTask) singleResult).getActivities();
			Assert.assertEquals(3, activities.size());
			TrackedTask loaded = (TrackedTask) singleResult;
			Assert.assertEquals(72, loaded.getDuration(now).getSeconds());
			Assert.assertEquals(108, loaded.getDuration(now.minusDays(1)).getSeconds());
			Assert.assertEquals(216, loaded.getDuration(now.minusMonths(1)).getSeconds());
		}
		Assert.assertEquals(null, task.getAttribute(TimekeeperPlugin.KEY_VALUELIST_ID));
		Assert.assertEquals(ttask.getId(), task.getAttribute(TrackedTask.KEY_IDENTIFIER_ATTR));		
	}
}
