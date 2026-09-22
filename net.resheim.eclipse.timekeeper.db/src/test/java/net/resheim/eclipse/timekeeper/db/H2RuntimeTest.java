package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDateTime;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.resheim.eclipse.timekeeper.db.model.Task;

class H2RuntimeTest {
	@TempDir Path directory;

	@Test
	void currentEnginePreservesNanosecondTimestampsAcrossRestartAndSqlRestore() throws Exception {
		var start = LocalDateTime.parse("2022-09-19T09:00:00.123456789");
		var end = LocalDateTime.parse("2022-09-19T10:30:00.987654321");
		var tick = LocalDateTime.parse("2022-09-19T09:42:00.222333444");
		String id;
		var manager = DatabaseStartup.open(url("original"));
		try {
			CurrentModelFixture.seed(manager);
			manager.getTransaction().begin();
			Task task = CurrentModelFixture.task(manager, CurrentModelFixture.REPOSITORY_A, "1");
			id = task.getId();
			task.setTick(tick);
			var activity = task.getActivities().stream().filter(a -> a.getSummary().equals("Investigated the build"))
					.findFirst().orElseThrow();
			activity.setStart(start);
			activity.setEnd(end);
			manager.getTransaction().commit();
		} finally { DatabaseStartup.close(manager); }
		Path script = directory.resolve("export.sql");
		try (var connection = DriverManager.getConnection(url("original") + ";IFEXISTS=TRUE", "sa", "");
				var statement = connection.createStatement()) {
			assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("2.5.250 "));
			statement.execute("SCRIPT TO '" + script.toString().replace("'", "''") + "'");
		}
		try (var restored = DriverManager.getConnection(url("restored"), "sa", "");
				var reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
			RunScript.execute(restored, reader);
		}
		for (String name : new String[] { "original", "restored" }) {
			manager = DatabaseStartup.open(url(name) + ";IFEXISTS=TRUE");
			try {
				Task task = manager.find(Task.class, id);
				assertEquals(tick, task.getTick());
				var activity = task.getActivities().stream().filter(a -> a.getSummary().equals("Investigated the build"))
						.findFirst().orElseThrow();
				assertEquals(start, activity.getStart());
				assertEquals(end, activity.getEnd());
			} finally { DatabaseStartup.close(manager); }
		}
	}

	private String url(String name) { return "jdbc:h2:" + directory.resolve(name); }
}
