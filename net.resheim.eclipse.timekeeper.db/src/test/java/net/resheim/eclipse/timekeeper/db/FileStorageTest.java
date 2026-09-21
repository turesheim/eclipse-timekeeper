package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.persistence.EntityManager;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;

/** File-backed persistence and recovery checks; all databases live in a fresh temporary directory. */
class FileStorageTest {
	@TempDir
	Path directory;
	private EntityManager manager;

	@AfterEach
	void close() {
		PersistenceHelper.close(manager);
		manager = null;
	}

	@Test
	void previousLabelMappingRemainsReadableAndWritableWithoutSchemaChanges() throws Exception {
		try (Connection connection = DriverManager.getConnection(url("original"), "sa", "");
				var stream = FileStorageTest.class.getResourceAsStream("fixtures/current-model-186355a.sql")) {
			assertNotNull(stream, "The frozen pre-change schema fixture must be packaged");
			try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				RunScript.execute(connection, reader);
			}
		}
		Map<String, List<List<String>>> expected = snapshot("original");
		verify("original");
		manager = PersistenceHelper.getEntityManager(existingUrl("original"), "none");
		manager.getTransaction().begin();
		Activity activity = activity("Investigated the build");
		ActivityLabel label = activity.getLabels().get(0);
		String labelId = label.getId();
		activity.toggleLabel(new ActivityLabel(label));
		manager.getTransaction().commit();
		close();
		manager = PersistenceHelper.getEntityManager(existingUrl("original"), "none");
		assertTrue(activity("Investigated the build").getLabels().isEmpty());
		assertEquals(labelId, activity(CurrentModelFixture.ADJUSTED).getLabels().get(0).getId());
		manager.getTransaction().begin();
		activity("Investigated the build").toggleLabel(manager.find(ActivityLabel.class, labelId));
		manager.getTransaction().commit();
		close();
		verify("original");
		assertEquals(expected, snapshot("original"));
	}

	@Test
	void restartAndClosedFileCopyPreserveTheCurrentModel() throws Exception {
		createFixture();
		Map<String, List<List<String>>> expected = snapshot("original");
		Path original = directory.resolve("original.mv.db");
		byte[] originalHash = hash(original);
		Files.copy(original, directory.resolve("backup.mv.db"));
		assertArrayEquals(originalHash, hash(directory.resolve("backup.mv.db")));
		verify("backup");
		assertEquals(expected, snapshot("backup"));
		assertArrayEquals(originalHash, hash(original), "Reading the backup must not touch the original");
		// Exercise the production create-tables setting on an existing database too.
		manager = PersistenceHelper.getEntityManager(existingUrl("original"), "create-tables");
		CurrentModelFixture.verify(manager);
		close();
		assertEquals(expected, snapshot("original"));
	}

	@Test
	void sqlExportAndRestorePreserveEveryRowAndRelationship() throws Exception {
		createFixture();
		Map<String, List<List<String>>> expected = snapshot("original");
		Path script = directory.resolve("export.sql");
		try (Connection connection = DriverManager.getConnection(existingUrl("original"), "sa", "");
				var statement = connection.createStatement()) {
			statement.execute("SCRIPT TO '" + script.toString().replace("'", "''") + "'");
		}
		try (Connection connection = DriverManager.getConnection(url("restored"), "sa", "");
				var reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
			RunScript.execute(connection, reader);
		}
		assertEquals(expected, snapshot("restored"));
		verify("restored");
		assertEquals(expected, snapshot("original"));
	}

	@Test
	void rollbackPreservesLabelsAndActivitiesAfterRestart() throws Exception {
		createFixture();
		Map<String, List<List<String>>> expected = snapshot("original");
		manager = PersistenceHelper.getEntityManager(existingUrl("original"), "none");
		manager.getTransaction().begin();
		Activity activity = activity("Investigated the build");
		activity.setSummary("Uncommitted edit");
		activity.getLabels().get(0).setColor("255,0,0");
		activity.getLabels().clear();
		manager.persist(new ActivityLabel("Uncommitted label", "0,0,0"));
		manager.flush();
		manager.getTransaction().rollback();
		close();
		verify("original");
		assertEquals(expected, snapshot("original"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "Investigated the build", "Exported the report" })
	void deletingAnActivityDoesNotDeleteLabels(String summary) throws Exception {
		createFixture();
		manager = PersistenceHelper.getEntityManager(existingUrl("original"), "none");
		manager.getTransaction().begin();
		Activity activity = activity(summary);
		activity.getTrackedTask().getActivities().remove(activity);
		manager.remove(activity);
		manager.getTransaction().commit();
		close();
		manager = PersistenceHelper.getEntityManager(existingUrl("original"), "none");
		assertEquals(4L, manager.createQuery("SELECT COUNT(a) FROM Activity a", Long.class).getSingleResult());
		assertEquals(2, manager.createNamedQuery("ActivityLabel.findAll", ActivityLabel.class).getResultList().size());
		Activity remaining = activity(CurrentModelFixture.ADJUSTED);
		assertEquals(1, remaining.getLabels().size());
		assertEquals("Billable", remaining.getLabels().get(0).getName());
		assertEquals(summary.equals("Investigated the build") ? 1 : 2,
				remaining.getTrackedTask().getActivities().size());
	}

	private Activity activity(String summary) {
		return manager.createQuery("SELECT a FROM Activity a WHERE a.summary = :summary", Activity.class)
				.setParameter("summary", summary).getSingleResult();
	}

	private void createFixture() {
		manager = PersistenceHelper.getEntityManager(url("original"), "create-tables");
		CurrentModelFixture.seed(manager);
		close(); // Close the factory/pool before copying or reopening any file.
		verify("original");
	}

	private void verify(String name) {
		// No schema generation on validation: missing tables must not be silently created.
		manager = PersistenceHelper.getEntityManager(existingUrl(name), "none");
		CurrentModelFixture.verify(manager);
		close();
	}

	private String url(String name) {
		return "jdbc:h2:" + directory.resolve(name).toAbsolutePath();
	}

	private String existingUrl(String name) {
		return url(name) + ";IFEXISTS=TRUE";
	}

	private byte[] hash(Path path) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
	}

	/** Compare all application rows, including generated UUIDs and association tables. */
	private Map<String, List<List<String>>> snapshot(String name) throws Exception {
		Map<String, List<List<String>>> snapshot = new TreeMap<>();
		try (Connection connection = DriverManager.getConnection(existingUrl(name), "sa", "");
				ResultSet tables = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[] { "TABLE" })) {
			while (tables.next()) {
				String table = tables.getString("TABLE_NAME");
				List<List<String>> rows = new ArrayList<>();
				try (var statement = connection.createStatement();
						ResultSet records = statement.executeQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"")) {
					while (records.next()) {
						List<String> row = new ArrayList<>();
						for (int column = 1; column <= records.getMetaData().getColumnCount(); column++) {
							row.add(records.getString(column));
						}
						rows.add(row);
					}
				}
				rows.sort(Comparator.comparing(Object::toString));
				snapshot.put(table, rows);
			}
		}
		assertTrue(snapshot.containsKey("ACTIVITY_ACTIVITYLABEL"));
		assertEquals(5, snapshot.get("TASK_ACTIVITY").size());
		assertEquals(3, snapshot.get("PROJECT_TASK").size());
		assertEquals(3, snapshot.get("ACTIVITY_ACTIVITYLABEL").size());
		return snapshot;
	}
}
