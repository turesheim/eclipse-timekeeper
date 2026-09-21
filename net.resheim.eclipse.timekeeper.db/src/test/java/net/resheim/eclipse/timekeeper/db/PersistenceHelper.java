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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.eclipse.persistence.config.PersistenceUnitProperties;

/**
 * Creates an isolated in-memory database for each test. No user database is opened.
 * 
 * @author Torkild U. Resheim
 */
public class PersistenceHelper {
	
	public static EntityManager getEntityManager() {
		return getEntityManager("jdbc:h2:mem:test_" + UUID.randomUUID(), "create-tables");
	}

	/** Opens only the synthetic database URL supplied by a test. */
	public static EntityManager getEntityManager(String jdbcUrl, String ddlGeneration) {
		Map<String, Object> props = new HashMap<String, Object>();
		props.put(PersistenceUnitProperties.JDBC_URL, jdbcUrl);
		props.put(PersistenceUnitProperties.DDL_GENERATION, ddlGeneration);
		props.put(PersistenceUnitProperties.JDBC_DRIVER, "org.h2.Driver");
		props.put(PersistenceUnitProperties.JDBC_USER, "sa");
		props.put(PersistenceUnitProperties.JDBC_PASSWORD, "");
		props.put(PersistenceUnitProperties.LOGGING_LEVEL, "warning");
		return Persistence
				.createEntityManagerFactory("net.resheim.eclipse.timekeeper.db", props)
				.createEntityManager();
	}

	/**
	 * Rolls back unfinished work and closes the isolated test database and its pool.
	 */
	public static void close(EntityManager entityManager) {
		if (entityManager != null && entityManager.isOpen()) {
			EntityManagerFactory factory = entityManager.getEntityManagerFactory();
			try {
				if (entityManager.getTransaction().isActive()) {
					entityManager.getTransaction().rollback();
				}
			} finally {
				entityManager.close();
				factory.close();
			}
		}
	}

}
