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

import javax.persistence.EntityManager;
import javax.persistence.Persistence;

import org.eclipse.persistence.config.PersistenceUnitProperties;

/**
 * Utility for setting up an {@link EntityManager} for testing. Logging level is
 * set to FINE and the database resides in memory only.
 * 
 * @author Torkild U. Resheim
 */
public class PersistenceHelper {
	
	private static final EntityManager entityManager;
	
	static {
		Map<String, Object> props = new HashMap<String, Object>();
		props.put(PersistenceUnitProperties.JDBC_URL, "jdbc:h2:mem:test_mem");
		props.put(PersistenceUnitProperties.JDBC_DRIVER, "org.h2.Driver");
		props.put(PersistenceUnitProperties.JDBC_USER, "sa");
		props.put(PersistenceUnitProperties.JDBC_PASSWORD, "");
		props.put(PersistenceUnitProperties.LOGGING_LEVEL, "fine");
		entityManager = Persistence	
				.createEntityManagerFactory("net.resheim.eclipse.timekeeper.db", props)
				.createEntityManager();
	};

	public static EntityManager getEntityManager() {
		return entityManager;
	};

}
