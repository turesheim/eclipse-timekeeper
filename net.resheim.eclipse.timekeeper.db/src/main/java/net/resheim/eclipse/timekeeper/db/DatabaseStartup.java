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

/** Opens only an empty or recognized current-model database. Never migrates in place. */
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

	/** Only the explicit recovery workflow may create/reopen a pending conversion. */
	static EntityManager openRecoveryTarget(String jdbcUrl, int legacyVersion) throws SQLException {
		if (legacyVersion != 1 && legacyVersion != 2) throw new SQLException("Unsupported historical version");
		return open(jdbcUrl, "LEGACY_V" + legacyVersion);
	}

	private static EntityManager open(String jdbcUrl, String origin) throws SQLException {
		boolean recovery = !origin.equals("NEW");
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
			if (schema == DatabaseSchema.Kind.INCOMPLETE || (!recovery && schema == DatabaseSchema.Kind.RECOVERING)) {
				throw new SQLException("Incomplete Timekeeper database initialization or recovery."
						+ " Preserve this attempt and retry from a backup into new storage; no schema changes were made.");
			}
			if (schema == DatabaseSchema.Kind.LEGACY_V1 || schema == DatabaseSchema.Kind.LEGACY_V2) {
				throw new SQLException("Historical Timekeeper database detected (" + schema
						+ "). Back up the closed database and convert a separate copy before using it."
						+ " No schema changes were made.");
			}
			boolean existing = recovery ? schema == DatabaseSchema.Kind.RECOVERING : schema == DatabaseSchema.Kind.CURRENT;
			if (schema != DatabaseSchema.Kind.EMPTY && !existing) {
				throw new SQLException("Unrecognized or mixed Timekeeper database schema."
						+ " No schema changes were made. Keep the original and review a backup before proceeding.");
			}
			if (recovery && existing && !DatabaseVersion.read(inspection).origin().equals(origin)) {
				throw new SQLException("Recovery target belongs to a different source schema version.");
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
			throw failure;
		}
	}
}
