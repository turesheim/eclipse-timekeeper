package net.resheim.eclipse.timekeeper.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.eclipse.persistence.config.PersistenceUnitProperties;
import org.eclipse.persistence.jpa.PersistenceProvider;

/** Opens only an empty or versioned current-model database. Never migrates in place. */
public final class DatabaseStartup {
	private DatabaseStartup() { }

	static void close(EntityManager manager) {
		if (manager == null) return;
		EntityManagerFactory factory = manager.getEntityManagerFactory();
		try {
			if (manager.isOpen()) {
				if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
				manager.close();
			}
		} finally {
			if (factory.isOpen()) factory.close();
		}
	}

	public static EntityManager open(String jdbcUrl) throws SQLException {
		return open(jdbcUrl, "NEW");
	}

	/** Infrastructure for future explicit migrations into separate, empty storage. */
	static EntityManager openMigrationTarget(String jdbcUrl) throws SQLException {
		return open(jdbcUrl, "MIGRATION");
	}

	private static EntityManager open(String jdbcUrl, String origin) throws SQLException {
		boolean migration = !origin.equals("NEW");
		// INIT runs before inspection and could mutate an existing schema. Unnamed
		// memory databases cannot be shared between the inspection and JPA connections.
		if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:h2:")
				|| jdbcUrl.matches("(?is).*;\\s*INIT\\s*=.*")
				|| jdbcUrl.matches("(?is)jdbc:h2:mem:(;.*)?")) {
			throw new SQLException("Unsupported database URL. Use a named H2 database without an INIT command.");
		}
		Properties credentials = new Properties();
		credentials.setProperty("user", "sa");
		credentials.setProperty("password", "");
		EntityManagerFactory factory = null;
		EntityManager manager = null;
		// Keep this connection open so an in-memory database survives until JPA has
		// connected. Do not rely on DriverManager discovery across OSGi class loaders.
		try (Connection inspection = new org.h2.Driver().connect(jdbcUrl, credentials)) {
			if (inspection.isReadOnly()) {
				throw new SQLException("The Timekeeper database is read-only; time tracking requires writable storage.");
			}
			DatabaseSchema.Kind schema = DatabaseSchema.inspect(inspection);
			if (schema == DatabaseSchema.Kind.INCOMPLETE || schema == DatabaseSchema.Kind.MIGRATING) {
				throw new SQLException("Incomplete Timekeeper database initialization or migration."
						+ " Preserve this attempt and retry from a backup into new storage; no schema changes were made.");
			}
			boolean existing = !migration && schema == DatabaseSchema.Kind.CURRENT;
			if (schema != DatabaseSchema.Kind.EMPTY && !existing) {
				throw new SQLException("Unsupported, unversioned or non-empty migration-target database."
						+ " No schema changes were made. Historical migration is not supported; keep the original"
						+ " and select separate empty storage for a new database.");
			}
			if (schema == DatabaseSchema.Kind.EMPTY) DatabaseVersion.begin(inspection, origin);
			Map<String, Object> properties = new HashMap<>();
			properties.put(PersistenceUnitProperties.CLASSLOADER, DatabaseStartup.class.getClassLoader());
			properties.put(PersistenceUnitProperties.JDBC_URL, jdbcUrl);
			properties.put(PersistenceUnitProperties.JDBC_DRIVER, "org.h2.Driver");
			properties.put(PersistenceUnitProperties.JDBC_USER, "sa");
			properties.put(PersistenceUnitProperties.JDBC_PASSWORD, "");
			properties.put(PersistenceUnitProperties.LOGGING_LEVEL, "warning");
			properties.put(PersistenceUnitProperties.DDL_GENERATION,
					schema == DatabaseSchema.Kind.EMPTY ? "create-tables" : "none");
			factory = new PersistenceProvider()
					.createEntityManagerFactory("net.resheim.eclipse.timekeeper.db", properties);
			manager = factory.createEntityManager();
			manager.createQuery("SELECT COUNT(t) FROM Task t", Long.class).getSingleResult();
			if (!DatabaseSchema.hasCurrentTables(inspection)) {
				throw new SQLException("Timekeeper schema creation did not complete. Preserve the database for diagnosis.");
			}
			if (schema == DatabaseSchema.Kind.EMPTY) DatabaseVersion.schemaCreated(inspection);
			return manager;
		} catch (SQLException | RuntimeException failure) {
			try {
				if (manager != null) close(manager);
				else if (factory != null && factory.isOpen()) factory.close();
			} catch (RuntimeException cleanup) {
				failure.addSuppressed(cleanup);
			}
			if (failure instanceof SQLException sql && sql.getErrorCode() == 90048) {
				throw new SQLException("H2 2.5.250 cannot open this database format."
						+ " Historical migration is not supported. Keep the original; do not replace its files."
						+ " Select separate empty storage for a new database.", sql.getSQLState(), sql.getErrorCode(), sql);
			}
			throw failure;
		}
	}
}
