package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseVersionTest {
	@TempDir Path directory;

	@Test
	void versionAndDataSurviveRestartClosedCopyAndSqlRestore() throws Exception {
		var manager = DatabaseStartup.open(url("database"));
		try {
			CurrentModelFixture.seed(manager);
		} finally {
			DatabaseStartup.close(manager);
		}
		Files.copy(directory.resolve("database.mv.db"), directory.resolve("copy.mv.db"));
		Path script = directory.resolve("export.sql");
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("SCRIPT TO '" + script.toString().replace("'", "''") + "'");
		}
		try (Connection connection = DriverManager.getConnection(url("restored"), "sa", "");
				var reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
			RunScript.execute(connection, reader);
		}
		for (String name : List.of("database", "copy", "restored")) {
			manager = DatabaseStartup.open(url(name) + ";IFEXISTS=TRUE");
			try {
				CurrentModelFixture.verify(manager);
			} finally {
				DatabaseStartup.close(manager);
			}
			try (Connection connection = DriverManager.getConnection(url(name) + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "")) {
				assertEquals(new DatabaseVersion.Stamp(1, "READY", "NEW"), DatabaseVersion.read(connection));
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"DELETE FROM TIMEKEEPER_SCHEMA",
			"UPDATE TIMEKEEPER_SCHEMA SET VERSION=2",
			"UPDATE TIMEKEEPER_SCHEMA SET VERSION=0",
			"UPDATE TIMEKEEPER_SCHEMA SET STATE='CREATING'",
			"UPDATE TIMEKEEPER_SCHEMA SET STATE='RECOVERING',ORIGIN='LEGACY_V1'",
			"UPDATE TIMEKEEPER_SCHEMA SET STATE='UNKNOWN'",
			"UPDATE TIMEKEEPER_SCHEMA SET ORIGIN='UNKNOWN'",
			"ALTER TABLE TIMEKEEPER_SCHEMA ADD EXTRA VARCHAR",
			"ALTER TABLE TIMEKEEPER_SCHEMA ALTER COLUMN VERSION VARCHAR(16)",
			"DROP TABLE ACTIVITY_ACTIVITYLABEL"
	})
	void rejectsIncompleteUnknownOrMalformedVersionsWithoutChangingSchemaOrData(String mutation) throws Exception {
		DatabaseStartup.close(DatabaseStartup.open(url("database")));
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute(mutation);
		}
		List<String> before = snapshot();
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url("database")));
		assertEquals(before, snapshot());
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 2 })
	void refusesInterruptedCreationRatherThanCompletingIt(int phase) throws Exception {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			DatabaseVersion.begin(connection, "NEW");
			if (phase == 0) statement.execute("DELETE FROM TIMEKEEPER_SCHEMA"); // DDL committed, row insert did not.
			if (phase == 2) statement.execute("CREATE TABLE TASK(TASK_ID VARCHAR)"); // Partial JPA DDL.
		}
		List<String> before = snapshot();
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url("database")));
		assertEquals(before, snapshot());
	}

	@Test
	void pendingRecoveryCannotBeOpenedByNormalStartupOrADifferentRecovery() throws Exception {
		DatabaseStartup.close(DatabaseStartup.openRecoveryTarget(url("database"), 2));
		List<String> before = snapshot();
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url("database")));
		assertThrows(SQLException.class, () -> DatabaseStartup.openRecoveryTarget(url("database"), 1));
		try (Connection connection = connection()) {
			assertEquals(new DatabaseVersion.Stamp(1, "RECOVERING", "LEGACY_V2"), DatabaseVersion.read(connection));
			assertEquals(DatabaseSchema.Kind.RECOVERING, DatabaseSchema.inspect(connection));
		}
		assertEquals(before, snapshot());
	}

	@Test
	void incompleteSchemaCannotAdvanceAndCallerTransactionIsNotCommitted() throws Exception {
		try (Connection connection = connection()) {
			connection.setAutoCommit(false);
			assertThrows(SQLException.class, () -> DatabaseVersion.begin(connection, "NEW"));
			assertFalse(connection.getAutoCommit());
			connection.rollback();
			connection.setAutoCommit(true);
			assertEquals(DatabaseSchema.Kind.EMPTY, DatabaseSchema.inspect(connection));
			DatabaseVersion.begin(connection, "NEW");
			assertThrows(SQLException.class, () -> DatabaseVersion.schemaCreated(connection));
			assertThrows(SQLException.class, () -> DatabaseVersion.recoveryValidated(connection));
			assertEquals(new DatabaseVersion.Stamp(1, "CREATING", "NEW"), DatabaseVersion.read(connection));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "(1,1,'READY','NEW'),(1,1,'READY','NEW')", "(NULL,1,'READY','NEW')",
			"(1,NULL,'READY','NEW')", "(1,1,NULL,'NEW')", "(1,1,'READY',NULL)" })
	void malformedRowsAreNeverTreatedAsAnUnversionedDatabase(String rows) throws Exception {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE TIMEKEEPER_SCHEMA(ID INTEGER,VERSION INTEGER,STATE VARCHAR,ORIGIN VARCHAR)");
			statement.execute("INSERT INTO TIMEKEEPER_SCHEMA VALUES " + rows);
		}
		List<String> before = snapshot();
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url("database")));
		assertEquals(before, snapshot());
	}

	@Test
	void versionMarkerCannotBeAddedToAnExistingUnversionedDatabase() throws Exception {
		PersistenceHelper.close(PersistenceHelper.getEntityManager(url("database"), "create-tables"));
		try (Connection connection = connection()) {
			assertThrows(SQLException.class, () -> DatabaseVersion.begin(connection, "NEW"));
			assertFalse(DatabaseSchema.hasVersion(connection));
		}
		DatabaseStartup.close(DatabaseStartup.open(url("database")));
		try (Connection connection = connection()) {
			assertEquals(DatabaseSchema.Kind.CURRENT, DatabaseSchema.inspect(connection));
			assertFalse(DatabaseSchema.hasVersion(connection));
			assertTrue(DatabaseSchema.hasCurrentTables(connection));
		}
	}

	private List<String> snapshot() throws SQLException {
		List<String> result = new ArrayList<>();
		try (Connection connection = connection(); var statement = connection.createStatement();
				var rows = statement.executeQuery("SCRIPT NOPASSWORDS")) {
			while (rows.next()) result.add(rows.getString(1));
		}
		return result;
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(url("database"), "sa", "");
	}

	private String url(String name) { return "jdbc:h2:" + directory.resolve(name).toAbsolutePath(); }
}
