package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.persistence.EntityManager;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;

class DatabaseStartupTest {
	@TempDir Path directory;

	@Test
	void createsNewFileDatabaseAndInitializesDefaultsOnce() throws Exception {
		EntityManager manager = DatabaseStartup.open(url());
		try {
			TimekeeperPlugin.initializeDefaultLabels(manager);
			TimekeeperPlugin.initializeDefaultLabels(manager);
			assertEquals(7L, manager.createQuery("SELECT COUNT(l) FROM ActivityLabel l", Long.class).getSingleResult());
			manager.getTransaction().begin();
			manager.persist(new ActivityLabel("Custom label", "1,2,3"));
			manager.getTransaction().commit();
		} finally {
			DatabaseStartup.close(manager);
		}
		manager = DatabaseStartup.open(url() + ";IFEXISTS=TRUE");
		try {
			TimekeeperPlugin.initializeDefaultLabels(manager);
			assertEquals(8L, manager.createQuery("SELECT COUNT(l) FROM ActivityLabel l", Long.class).getSingleResult());
		} finally {
			DatabaseStartup.close(manager);
		}
		try (Connection connection = connection()) {
			assertEquals(DatabaseSchema.Kind.CURRENT, DatabaseSchema.inspect(connection));
		}
	}

	@Test
	void opensNamedMemoryDatabaseWithoutACloseDelay() throws Exception {
		EntityManager manager = DatabaseStartup.open("jdbc:h2:mem:startup_" + UUID.randomUUID());
		try {
			TimekeeperPlugin.initializeDefaultLabels(manager);
			assertEquals(7, manager.createNamedQuery("ActivityLabel.findAll", ActivityLabel.class).getResultList().size());
		} finally {
			DatabaseStartup.close(manager);
		}
	}

	@Test
	void opensExistingCurrentModelWithoutChangingSchemaOrData() throws Exception {
		try (Connection connection = connection()) {
			run(connection, "/net/resheim/eclipse/timekeeper/db/fixtures/current-model-186355a.sql");
		}
		List<String> before = snapshot();
		EntityManager manager = DatabaseStartup.open(url() + ";IFEXISTS=TRUE");
		try {
			TimekeeperPlugin.initializeDefaultLabels(manager);
			CurrentModelFixture.verify(manager);
		} finally {
			DatabaseStartup.close(manager);
		}
		assertEquals(before, snapshot());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void refusesLegacySchemasBeforeJpaCanCreateTables(boolean v2) throws Exception {
		try (Connection connection = connection()) {
			run(connection, "/legacy-fixture/V1__baseline.sql");
			if (v2) {
				run(connection, "/legacy-fixture/V2__add_project_taskurl_and_tasksummary.sql");
				run(connection, "/legacy-fixture/legacy-data.sql");
			}
			assertEquals(v2 ? DatabaseSchema.Kind.LEGACY_V2 : DatabaseSchema.Kind.LEGACY_V1,
					DatabaseSchema.inspect(connection));
		}
		List<String> before = snapshot();
		SQLException failure = assertThrows(SQLException.class, () -> DatabaseStartup.open(url()));
		assertTrue(failure.getMessage().contains("Historical Timekeeper database"));
		assertEquals(before, snapshot());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"CREATE TABLE UNRELATED(ID INT)",
			"CREATE TABLE TASK(ID INT)",
			"CREATE VIEW EXTRA_VIEW AS SELECT 1 AS ID"
	})
	void refusesUnknownMixedAndViewSchemas(String extra) throws Exception {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			run(connection, "/legacy-fixture/V1__baseline.sql");
			statement.execute(extra);
		}
		List<String> before = snapshot();
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url()));
		assertEquals(before, snapshot());
	}

	@Test
	void refusesIncompleteCurrentSchemaWithoutCreatingMissingTables() throws Exception {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			run(connection, "/net/resheim/eclipse/timekeeper/db/fixtures/current-model-186355a.sql");
			statement.execute("DROP TABLE ACTIVITY_ACTIVITYLABEL");
		}
		List<String> before = snapshot();
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url()));
		assertEquals(before, snapshot());
	}

	@Test
	void refusesReadOnlyDatabaseForTimeTracking() throws Exception {
		DatabaseStartup.close(DatabaseStartup.open(url()));
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url() + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "jdbc:h2:mem:", "jdbc:h2:mem:;DB_CLOSE_DELAY=-1", "jdbc:other:database" })
	void rejectsUnsupportedUrls(String url) {
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url));
	}

	@Test
	void rejectsInitCommandBeforeOpeningAnyDatabase() {
		assertThrows(SQLException.class, () -> DatabaseStartup.open(url() + ";INIT=CREATE TABLE UNEXPECTED(ID INT)"));
		assertFalse(Files.exists(directory.resolve("database.mv.db")));
	}

	private void run(Connection connection, String resource) throws Exception {
		try (var stream = getClass().getResourceAsStream(resource)) {
			assertNotNull(stream, resource);
			try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				RunScript.execute(connection, reader);
			}
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
		return DriverManager.getConnection(url(), "sa", "");
	}

	private String url() {
		return "jdbc:h2:" + directory.resolve("database").toAbsolutePath();
	}
}
