package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import javax.persistence.EntityManager;

import org.h2.api.Trigger;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.GlobalTaskId;
import net.resheim.eclipse.timekeeper.db.model.Task;

public class LegacyDatabaseConverterTest {
	@TempDir Path directory;

	@Test
	void convertsV2WithoutChangingTheSourceAndSurvivesRestartAndExport() throws Exception {
		createSource(true);
		createTarget();
		byte[] before = sourceHash();
		try (Connection source = source(); Connection target = target()) {
			LegacyDatabaseConverter.Result result = LegacyDatabaseConverter.convert(source, target);
			assertEquals(new LegacyDatabaseConverter.Result(2, 2, 3, 5, 0, Duration.ofSeconds(19800)), result);
			assertTrue(target.getAutoCommit());
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target), "Reruns must not duplicate data");
		}
		assertArrayEquals(before, sourceHash());
		verifyV2(url("target"));
		Path script = directory.resolve("converted.sql");
		try (Connection connection = target(); var statement = connection.createStatement()) {
			statement.execute("SCRIPT TO '" + script.toString().replace("'", "''") + "'");
		}
		try (Connection restored = DriverManager.getConnection(url("restored"), "sa", "");
				var reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
			RunScript.execute(restored, reader);
		}
		verifyV2(url("restored"));
		assertArrayEquals(before, sourceHash());
	}

	@Test
	void convertsV1WithoutInventingProjectOrTaskMetadata() throws Exception {
		createSource(false);
		try (Connection connection = writableSource(); var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO TRACKEDTASK(TASK_ID,REPOSITORY_URL) VALUES ('1','https://example.invalid/a')");
			statement.executeUpdate("INSERT INTO ACTIVITY(ID,START_TIME,END_TIME,ADJUSTED,TASK_ID,REPOSITORY_URL) VALUES"
					+ " ('00000000-0000-0000-0000-000000000001','2022-09-19 09:00:00','2022-09-19 10:30:00',FALSE,'1','https://example.invalid/a')");
			statement.executeUpdate("INSERT INTO TRACKEDTASK_ACTIVITY SELECT TASK_ID,REPOSITORY_URL,ID FROM ACTIVITY");
		}
		createTarget();
		try (Connection source = source(); Connection target = target()) {
			assertEquals(new LegacyDatabaseConverter.Result(1, 0, 1, 1, 0, Duration.ofMinutes(90)),
					LegacyDatabaseConverter.convert(source, target));
		}
		EntityManager manager = PersistenceHelper.getEntityManager(url("target") + ";IFEXISTS=TRUE", "none");
		try {
			Task task = manager.find(Task.class, new GlobalTaskId(CurrentModelFixture.REPOSITORY_A, "1"));
			assertNotNull(task);
			assertNull(task.getProject());
			assertNull(task.getTaskSummary());
			assertNull(task.getTaskUrl());
			assertEquals(Duration.ofMinutes(90), task.getActivities().get(0).getDuration());
		} finally {
			PersistenceHelper.close(manager);
		}
	}

	@Test
	void preservesOpenActivityTickAndDirectProjectAssociations() throws Exception {
		createSource(true);
		try (Connection connection = writableSource(); var statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE ACTIVITY SET END_TIME=NULL WHERE ID='00000000-0000-0000-0000-000000000001'");
			statement.executeUpdate("UPDATE TRACKEDTASK SET CURRENTACTIVITY_ID='00000000-0000-0000-0000-000000000001',"
					+ " TICK='2022-09-19 09:42:00.123456' WHERE TASK_ID='1' AND REPOSITORY_URL='https://example.invalid/a'");
			statement.executeUpdate("INSERT INTO ACTIVITY(ID,START_TIME,END_TIME,ADJUSTED,SUMMARY,PROJECT) VALUES"
					+ " ('00000000-0000-0000-0000-000000000006','2022-09-20 12:00:00','2022-09-20 12:15:00',TRUE,'Project-only work','Reports')");
		}
		createTarget();
		try (Connection source = source(); Connection target = target()) {
			var result = LegacyDatabaseConverter.convert(source, target);
			assertEquals(1, result.openActivities());
			assertEquals(Duration.ofSeconds(15300), result.closedDuration());
			assertEquals(1, count(target, "PROJECT_ACTIVITY"));
		}
		EntityManager manager = PersistenceHelper.getEntityManager(url("target") + ";IFEXISTS=TRUE", "none");
		try {
			Task task = manager.find(Task.class, new GlobalTaskId(CurrentModelFixture.REPOSITORY_A, "1"));
			Activity current = task.getCurrentActivity().orElseThrow();
			assertNull(current.getEnd());
			assertEquals(task, current.getTrackedTask());
			assertEquals(LocalDateTime.parse("2022-09-19T09:42:00.123456"), task.getTick());
			Activity direct = manager.find(Activity.class, "00000000-0000-0000-0000-000000000006");
			assertNull(direct.getTrackedTask());
			assertEquals(Duration.ofMinutes(15), direct.getDuration());
		} finally {
			PersistenceHelper.close(manager);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"CREATE TABLE TASK(ID INT)",
			"ALTER TABLE ACTIVITY ADD EXTRA_DATA VARCHAR",
			"UPDATE TRACKEDTASK SET PROJECT='Missing project' WHERE TASK_ID='1'",
			"UPDATE ACTIVITY SET PROJECT='Reports' WHERE ID='00000000-0000-0000-0000-000000000001'",
			"DELETE FROM TRACKEDTASK_ACTIVITY WHERE ACTIVITIES_ID='00000000-0000-0000-0000-000000000001'",
			"UPDATE ACTIVITY SET ADJUSTED=NULL WHERE ID='00000000-0000-0000-0000-000000000001'",
			"UPDATE ACTIVITY SET END_TIME='2000-01-01 00:00:00' WHERE ID='00000000-0000-0000-0000-000000000001'",
			"UPDATE TRACKEDTASK SET CURRENTACTIVITY_ID='00000000-0000-0000-0000-000000000003' WHERE REPOSITORY_URL='https://example.invalid/a'"
	})
	void rejectsAmbiguousOrInconsistentSourcesWithoutWriting(String mutation) throws Exception {
		createSource(true);
		try (Connection connection = writableSource(); var statement = connection.createStatement()) {
			statement.execute(mutation);
		}
		createTarget();
		byte[] before = sourceHash();
		try (Connection source = source(); Connection target = target()) {
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target));
			assertEmpty(target);
		}
		assertArrayEquals(before, sourceHash());
	}

	@Test
	void rollsBackPartialCopyAndAllowsRetryAfterConstraintFailure() throws Exception {
		createSource(true);
		createTarget();
		byte[] before = sourceHash();
		try (Connection source = source(); Connection target = target(); var statement = target.createStatement()) {
			statement.execute("ALTER TABLE ACTIVITY ADD CONSTRAINT TEST_FAILURE CHECK (SUMMARY <> 'Across midnight')");
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target));
			assertTrue(target.getAutoCommit());
			assertEmpty(target); // Projects/tasks were inserted before the failing activity.
		}
		try (Connection source = source(); Connection target = target(); var statement = target.createStatement()) {
			assertEmpty(target); // Verify the rollback after closing and reopening the file.
			statement.execute("ALTER TABLE ACTIVITY DROP CONSTRAINT TEST_FAILURE");
			assertEquals(5, LegacyDatabaseConverter.convert(source, target).activities());
		}
		assertArrayEquals(before, sourceHash());
		verifyV2(url("target"));
	}

	@Test
	void rollsBackWhenVerificationDetectsChangedValues() throws Exception {
		createSource(true);
		createTarget();
		byte[] before = sourceHash();
		try (Connection source = source(); Connection target = target(); var statement = target.createStatement()) {
			statement.execute("CREATE TRIGGER CHANGE_SUMMARY BEFORE INSERT ON ACTIVITY FOR EACH ROW CALL \""
					+ ChangedSummary.class.getName() + "\"");
			SQLException failure = assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target));
			assertTrue(failure.getMessage().contains("Conversion verification failed for ACTIVITY"));
			assertEmpty(target);
			assertTrue(target.getAutoCommit());
		}
		assertArrayEquals(before, sourceHash());
	}

	/** Test-only fault injection: the converter must detect this before commit. */
	public static class ChangedSummary implements Trigger {
		private int summaryColumn;

		@Override
		public void init(Connection connection, String schema, String trigger, String table, boolean before, int type)
				throws SQLException {
			try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM ACTIVITY WHERE 1=0")) {
				var metadata = rows.getMetaData();
				for (int column = 1; column <= metadata.getColumnCount(); column++) {
					if (metadata.getColumnName(column).equals("SUMMARY")) {
						summaryColumn = column - 1;
						return;
					}
				}
			}
			throw new SQLException("Missing test summary column");
		}

		@Override
		public void fire(Connection connection, Object[] oldRow, Object[] newRow) {
			newRow[summaryColumn] = "Unexpected change";
		}

		@Override public void close() { }
		@Override public void remove() { }
	}

	@Test
	void doesNotCommitOrRollBackACallersTransaction() throws Exception {
		createSource(true);
		createTarget();
		try (Connection source = source(); Connection target = target(); var statement = target.createStatement()) {
			target.setAutoCommit(false);
			statement.executeUpdate("INSERT INTO ACTIVITYLABEL(ID,NAME) VALUES ('uncommitted','Caller data')");
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target));
			assertFalse(target.getAutoCommit());
			assertEquals(1, count(target, "ACTIVITYLABEL"));
			target.rollback();
			assertEmpty(target);
		}
	}

	@Test
	void rejectsWritableSourceAndNonemptyTarget() throws Exception {
		createSource(true);
		createTarget();
		try (Connection source = writableSource(); Connection target = target()) {
			assertFalse(source.isReadOnly());
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target));
			assertEmpty(target);
		}
		try (Connection source = source(); Connection target = target(); var statement = target.createStatement()) {
			statement.executeUpdate("INSERT INTO ACTIVITYLABEL(ID,NAME) VALUES ('keep-me','Existing label')");
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target));
			assertEquals(1, count(target, "ACTIVITYLABEL"));
			assertEquals(0, count(target, "TASK"));
		}
	}

	private void verifyV2(String jdbcUrl) {
		EntityManager manager = PersistenceHelper.getEntityManager(jdbcUrl + ";IFEXISTS=TRUE", "none");
		try {
			List<Task> tasks = manager.createNamedQuery("Task.findAll", Task.class).getResultList();
			List<Activity> activities = manager.createQuery("SELECT a FROM Activity a", Activity.class).getResultList();
			assertEquals(3, tasks.size());
			assertEquals(5, activities.size());
			assertEquals(19800, activities.stream().mapToLong(a -> a.getDuration().getSeconds()).sum());
			assertEquals(1, activities.stream().filter(Activity::isEdited).count());
			assertEquals(CurrentModelFixture.ADJUSTED, activities.stream().filter(Activity::isEdited).findFirst().orElseThrow().getSummary());
			assertTrue(activities.stream().allMatch(a -> a.getLabels().isEmpty()), "The historical schema has no labels");
			for (Activity activity : activities) {
				assertTrue(activity.getTrackedTask().getActivities().contains(activity));
			}
			for (Task task : tasks) {
				assertNull(task.getMylynTask());
				assertTrue(task.getProject().getTasks().contains(task));
				assertEquals(task.getRepositoryUrl() + "/" + task.getTaskId(), task.getTaskUrl());
				assertEquals(task.getTaskId().equals("1") ? 9900 : 0,
						task.getActivities().stream().mapToLong(a -> a.getDuration().getSeconds()).sum());
			}
			Task first = manager.find(Task.class, new GlobalTaskId(CurrentModelFixture.REPOSITORY_A, "1"));
			Task second = manager.find(Task.class, new GlobalTaskId(CurrentModelFixture.REPOSITORY_B, "1"));
			assertEquals(CurrentModelFixture.PROJECT_A, first.getProject().getName());
			assertEquals("Reports", second.getProject().getName());
			assertEquals("BASELINE-A", first.getProject().getExternalId());
			assertEquals("Report with Unicode: æøå", second.getTaskSummary());
			long[] expected = { 1800, 13500, 4500 };
			for (int i = 0; i < expected.length; i++) {
				LocalDate day = LocalDate.of(2022, 9, 18).plusDays(i);
				assertEquals(expected[i], tasks.stream().mapToLong(t -> t.getDuration(day).getSeconds()).sum());
			}
		} finally {
			PersistenceHelper.close(manager);
		}
	}

	@Test
	void rejectsReadyVersionedTargetsAndPendingTargetsForAnotherSourceVersion() throws Exception {
		createSource(true);
		DatabaseStartup.close(DatabaseStartup.open(url("target")));
		try (Connection source = source(); Connection target = target()) {
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target));
			assertEmpty(target);
			assertEquals(new DatabaseVersion.Stamp(1, "READY", "NEW"), DatabaseVersion.read(target));
		}
		DatabaseStartup.close(DatabaseStartup.openRecoveryTarget(url("wrong-version"), 1));
		try (Connection source = source(); Connection target = DriverManager.getConnection(url("wrong-version"), "sa", "")) {
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.convert(source, target));
			assertEmpty(target);
			assertEquals(new DatabaseVersion.Stamp(1, "RECOVERING", "LEGACY_V1"), DatabaseVersion.read(target));
		}
	}

	private void createSource(boolean v2) throws Exception {
		try (Connection connection = LegacyH2.open(url("source"))) {
			run(connection, "V1__baseline.sql");
			if (v2) {
				run(connection, "V2__add_project_taskurl_and_tasksummary.sql");
				run(connection, "legacy-data.sql");
			}
		}
	}

	private void run(Connection connection, String resource) throws Exception {
		try (var stream = getClass().getResourceAsStream("/legacy-fixture/" + resource)) {
			assertNotNull(stream, resource);
			try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				RunScript.execute(connection, reader);
			}
		}
	}

	private void createTarget() {
		PersistenceHelper.close(PersistenceHelper.getEntityManager(url("target"), "create-tables"));
	}

	private Connection source() throws SQLException {
		return LegacyH2.open(url("source") + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r");
	}

	private Connection writableSource() throws SQLException {
		return LegacyH2.open(url("source") + ";IFEXISTS=TRUE");
	}

	private Connection target() throws SQLException {
		return DriverManager.getConnection(url("target") + ";IFEXISTS=TRUE", "sa", "");
	}

	private String url(String name) {
		return "jdbc:h2:" + directory.resolve(name).toAbsolutePath();
	}

	private byte[] sourceHash() throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve("source.mv.db")));
	}

	private long count(Connection connection, String table) throws SQLException {
		try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rows.next();
			return rows.getLong(1);
		}
	}

	private void assertEmpty(Connection connection) throws SQLException {
		for (String table : List.of("PROJECT_TYPE", "PROJECT", "TASK", "ACTIVITY", "TASK_ACTIVITY",
				"PROJECT_TASK", "PROJECT_ACTIVITY", "ACTIVITYLABEL", "ACTIVITY_ACTIVITYLABEL")) {
			assertEquals(0, count(connection, table), table);
		}
	}
}
