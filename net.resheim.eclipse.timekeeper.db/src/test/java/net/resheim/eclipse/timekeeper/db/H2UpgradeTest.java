package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Properties;

import org.h2.tools.Backup;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.resheim.eclipse.timekeeper.db.model.GlobalTaskId;
import net.resheim.eclipse.timekeeper.db.model.Task;

class H2UpgradeTest {
	@TempDir Path directory;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void upgradesCurrentModelWithLabelsAndRelationshipsWithoutChangingOriginal(boolean versioned) throws Exception {
		createOldCurrent(versioned, null);
		byte[] original = Files.readAllBytes(directory.resolve("original.mv.db"));
		Path backup = backup();
		var result = DatabaseRecovery.recover(backup, directory.resolve("upgrade"), () -> false);
		assertEquals(new LegacyDatabaseConverter.Result(versioned ? 1 : 0, 2, 3, 5, 0, Duration.ofSeconds(19800)), result.data());
		assertEquals(result, DatabaseRecovery.verify(result.directory()));
		assertArrayEquals(original, Files.readAllBytes(directory.resolve("original.mv.db")));
		assertArrayEquals(original, Files.readAllBytes(result.directory().resolve("source.mv.db")));
		Properties receipt = new Properties();
		try (var stream = Files.newInputStream(result.directory().resolve("validated.properties"))) { receipt.load(stream); }
		assertEquals("H2 2.5.250", receipt.getProperty("engine"));
		assertEquals("CURRENT", receipt.getProperty("source.schema"));
		verifyCurrent(result.jdbcUrl());
		Path sql = directory.resolve("upgraded.sql");
		try (Connection target = DriverManager.getConnection(result.jdbcUrl(), "sa", ""); var statement = target.createStatement()) {
			assertTrue(target.getMetaData().getDatabaseProductVersion().startsWith("2.5.250 "));
			assertEquals(new DatabaseVersion.Stamp(1, "READY", "H2_1_4"), DatabaseVersion.read(target));
			statement.execute("SCRIPT TO '" + sql.toString().replace("'", "''") + "'");
		}
		try (Connection restored = DriverManager.getConnection(url("restored"), "sa", "");
				var reader = Files.newBufferedReader(sql, StandardCharsets.UTF_8)) {
			RunScript.execute(restored, reader);
		}
		verifyCurrent(url("restored") + ";IFEXISTS=TRUE");
	}

	@ParameterizedTest
	@ValueSource(strings = { "UPDATE TIMEKEEPER_SCHEMA SET VERSION=999", "UPDATE TIMEKEEPER_SCHEMA SET STATE='CREATING'",
			"CREATE TABLE EXTRA_DATA(ID INT)", "UPDATE ACTIVITY SET END_TIME='2000-01-01 00:00:00'" })
	void refusesUnsupportedOrInvalidCurrentSourcesWithoutChangingThem(String mutation) throws Exception {
		createOldCurrent(true, mutation);
		byte[] original = Files.readAllBytes(directory.resolve("original.mv.db"));
		Path backup = backup();
		Path output = directory.resolve("upgrade");
		assertThrows(SQLException.class, () -> DatabaseRecovery.recover(backup, output, () -> false));
		assertFalse(Files.exists(output.resolve("validated.properties")));
		assertArrayEquals(original, Files.readAllBytes(directory.resolve("original.mv.db")));
	}

	@Test
	void normalStartupNeverUpgradesAnOldDatabaseInPlace() throws Exception {
		createOldCurrent(false, null);
		byte[] original = Files.readAllBytes(directory.resolve("original.mv.db"));
		SQLException failure = assertThrows(SQLException.class, () -> DatabaseStartup.open(url("original") + ";IFEXISTS=TRUE"));
		assertTrue(failure.getMessage().contains("backup upgrade"));
		assertArrayEquals(original, Files.readAllBytes(directory.resolve("original.mv.db")));
	}

	@Test
	void preservesNanosecondActivityTimesAndTicks() throws Exception {
		createOldCurrent(false, null);
		try (Connection source = LegacyH2.open(url("original") + ";IFEXISTS=TRUE"); var statement = source.createStatement()) {
			statement.execute("UPDATE ACTIVITY SET START_TIME='2022-09-19 09:00:00.123456789',"
					+ " END_TIME='2022-09-19 10:30:00.987654321' WHERE SUMMARY='Investigated the build'");
			statement.execute("UPDATE TASK SET TICK='2022-09-19 09:42:00.222333444' WHERE TASK_ID='1'"
					+ " AND REPOSITORY_URL='https://example.invalid/a'");
			try (var rows = statement.executeQuery("SELECT START_TIME FROM ACTIVITY WHERE SUMMARY='Investigated the build'")) {
				assertTrue(rows.next());
				assertEquals(123456789, rows.getTimestamp(1).getNanos());
			}
		}
		var result = DatabaseRecovery.recover(backup(), directory.resolve("nano"), () -> false);
		assertEquals(Duration.ofSeconds(19800, 864197532), result.data().closedDuration());
		var manager = DatabaseStartup.open(result.jdbcUrl());
		try {
			Task task = manager.find(Task.class, new GlobalTaskId("https://example.invalid/a", "1"));
			assertEquals(LocalDateTime.parse("2022-09-19T09:42:00.222333444"), task.getTick());
			var activity = task.getActivities().stream().filter(a -> a.getSummary().equals("Investigated the build")).findFirst().orElseThrow();
			assertEquals(123456789, activity.getStart().getNano());
			assertEquals(987654321, activity.getEnd().getNano());
		} finally { DatabaseStartup.close(manager); }
	}

	@Test
	void legacyReaderDoesNotReplaceTheNormalDriver() throws Exception {
		try (Connection modern = DriverManager.getConnection("jdbc:h2:mem:modern_driver", "sa", "")) {
			assertTrue(modern.getMetaData().getDatabaseProductVersion().startsWith("2.5.250 "));
			try (Connection old = LegacyH2.open(url("isolated"))) {
				assertTrue(old.getMetaData().getDatabaseProductVersion().startsWith("1.4.194 "));
				try (Connection current = DriverManager.getConnection("jdbc:h2:mem:still_modern", "sa", "")) {
					assertTrue(current.getMetaData().getDatabaseProductVersion().startsWith("2.5.250 "));
				}
			}
			assertTrue(modern.isValid(1));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "jdbc:h2:tcp://localhost/test", "jdbc:h2:ssl://localhost/test",
			"jdbc:h2:mem:test;AUTO_SERVER=TRUE", "jdbc:h2:mem:test;INIT=CREATE TABLE X(ID INT)" })
	void legacyReaderRefusesServerAndInitUrls(String url) {
		assertThrows(SQLException.class, () -> LegacyH2.open(url));
	}

	@Test
	void modernMixedModeOpensWithSupportedSharedSettings() throws Exception {
		// Ephemeral port avoids colliding with a user's server; no personal path is read.
		String shared = url("shared") + ";AUTO_SERVER=TRUE;AUTO_SERVER_PORT=0";
		var manager = DatabaseStartup.open(shared);
		try (Connection second = DriverManager.getConnection(shared + ";IFEXISTS=TRUE", "sa", "")) {
			assertEquals(DatabaseSchema.Kind.CURRENT, DatabaseSchema.inspect(second));
		} finally {
			DatabaseStartup.close(manager);
		}
	}

	private void verifyCurrent(String url) throws Exception {
		var manager = DatabaseStartup.open(url);
		try { CurrentModelFixture.verify(manager); }
		finally { DatabaseStartup.close(manager); }
	}

	private void createOldCurrent(boolean versioned, String mutation) throws Exception {
		try (Connection source = LegacyH2.open(url("original"));
				var stream = getClass().getResourceAsStream("/net/resheim/eclipse/timekeeper/db/fixtures/current-model-186355a.sql");
				var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			RunScript.execute(source, reader);
			try (var statement = source.createStatement()) {
				if (versioned) {
					statement.execute("CREATE TABLE TIMEKEEPER_SCHEMA(ID INTEGER PRIMARY KEY CHECK(ID=1), VERSION INTEGER NOT NULL,"
							+ " STATE VARCHAR(16) NOT NULL, ORIGIN VARCHAR(16) NOT NULL)");
					statement.execute("INSERT INTO TIMEKEEPER_SCHEMA VALUES(1,1,'READY','NEW')");
				}
				if (mutation != null) statement.execute(mutation);
			}
		}
	}

	private Path backup() throws SQLException {
		Path backup = directory.resolve("backup.zip");
		Backup.execute(backup.toString(), directory.toString(), "original", true);
		return backup;
	}

	private String url(String name) { return "jdbc:h2:" + directory.resolve(name).toAbsolutePath(); }
}
