package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.persistence.EntityManager;

import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.resheim.eclipse.timekeeper.db.model.Task;

/** Real second JVMs, temporary files and local-only servers; never personal storage. */
class StorageModesTest {
	private static final LocalDateTime CHILD_TICK = LocalDateTime.parse("2022-09-21T10:11:12.123456789");
	@TempDir Path directory;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void secondProcessReadsAndCommitsWhileFirstClientRemainsConnected(boolean tcp) throws Exception {
		Files.createDirectory(directory.resolve("workspace with spaces"));
		String embedded = "jdbc:h2:" + directory.resolve("workspace with spaces/h2db");
		Server server = null;
		String url = embedded + ";AUTO_SERVER=TRUE;AUTO_SERVER_PORT=0";
		try {
			if (tcp) {
				// Pre-create only the empty file: remote creation stays disabled on the server.
				try (var empty = DriverManager.getConnection(embedded, "sa", "")) { }
				server = server();
				url = remote(server, "workspace with spaces/h2db");
			}
			EntityManager first = DatabaseStartup.open(url);
			try {
				CurrentModelFixture.seed(first);
				CurrentModelFixture.verify(first);
				// The owner and its JPA connection stay open for the child's entire lifetime.
				runChild(classpath(), JpaClient.class, url + ";IFEXISTS=TRUE");
				first.clear();
				first.getEntityManagerFactory().getCache().evictAll();
				assertEquals(CHILD_TICK, task(first).getTick());
				CurrentModelFixture.verify(first);
			} finally { DatabaseStartup.close(first); }
		} finally {
			if (server != null) server.stop();
		}
		// Close every client/server, then read the same file without either sharing mode.
		EntityManager reopened = DatabaseStartup.open(embedded + ";IFEXISTS=TRUE");
		try {
			CurrentModelFixture.verify(reopened);
			assertEquals(CHILD_TICK, task(reopened).getTick());
		} finally { DatabaseStartup.close(reopened); }
	}

	@Test
	void workspaceEmbeddedStorageRejectsASecondProcessWithoutMixedMode() throws Exception {
		String url = "jdbc:h2:" + directory.resolve("workspace");
		EntityManager owner = DatabaseStartup.open(url);
		try {
			CurrentModelFixture.seed(owner);
			runChild(classpath(), StorageProcessClient.class, url + ";IFEXISTS=TRUE", "90020");
			CurrentModelFixture.verify(owner);
		} finally { DatabaseStartup.close(owner); }
		EntityManager reopened = DatabaseStartup.open(url + ";IFEXISTS=TRUE");
		try { CurrentModelFixture.verify(reopened); }
		finally { DatabaseStartup.close(reopened); }
	}

	@ParameterizedTest
	@ValueSource(strings = { "VERSION=999", "STATE='MIGRATING'" })
	void serverStartupEnforcesSchemaGuardsWithoutChangingMarker(String mutation) throws Exception {
		String embedded = "jdbc:h2:" + directory.resolve("guarded");
		EntityManager manager = DatabaseStartup.open(embedded);
		try { CurrentModelFixture.seed(manager); }
		finally { DatabaseStartup.close(manager); }
		try (var connection = DriverManager.getConnection(embedded, "sa", ""); var statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE TIMEKEEPER_SCHEMA SET " + mutation);
			if (mutation.startsWith("STATE=")) {
				statement.executeUpdate("UPDATE TIMEKEEPER_SCHEMA SET ORIGIN='MIGRATION'");
			}
		}
		Server server = server();
		try {
			String url = remote(server, "guarded") + ";IFEXISTS=TRUE";
			List<String> before = snapshot(url);
			assertThrows(SQLException.class, () -> DatabaseStartup.open(url));
			assertEquals(before, snapshot(url), "Rejected startup must not change schema, records or marker");
			try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement();
					var rows = statement.executeQuery("SELECT COUNT(*) FROM TIMEKEEPER_SCHEMA WHERE " + mutation)) {
				assertTrue(rows.next());
				assertEquals(1, rows.getInt(1));
			}
		} finally { server.stop(); }
	}

	@Test
	void missingServerDatabaseIsNotSilentlyCreated() throws Exception {
		Server server = server();
		try {
			assertThrows(SQLException.class, () -> DatabaseStartup.open(remote(server, "missing") + ";IFEXISTS=TRUE"));
			assertFalse(Files.exists(directory.resolve("missing.mv.db")));
		} finally { server.stop(); }
	}

	private static List<String> snapshot(String url) throws SQLException {
		var result = new java.util.ArrayList<String>();
		try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement();
				var rows = statement.executeQuery("SCRIPT NOPASSWORDS")) {
			while (rows.next()) result.add(rows.getString(1));
		}
		return result;
	}

	private Server server() throws SQLException {
		// No -tcpAllowOthers or -ifNotExists: only local clients and existing temp files.
		return Server.createTcpServer("-tcpPort", "0", "-baseDir", directory.toString()).start();
	}

	private static String remote(Server server, String name) {
		return "jdbc:h2:tcp://localhost:" + server.getPort() + "/" + name;
	}

	private static Task task(EntityManager manager) {
		return CurrentModelFixture.task(manager, CurrentModelFixture.REPOSITORY_A, "1");
	}

	private static String classpath() {
		return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
	}

	private void runChild(String classpath, Class<?> main, String... args) throws Exception {
		var command = new java.util.ArrayList<>(List.of(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", classpath, main.getName()));
		command.addAll(List.of(args));
		Path output = Files.createTempFile(directory, "client-", ".log");
		Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "Child timed out: " + output);
			assertEquals(0, process.exitValue(), () -> readLog(output));
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Could not stop child process");
			}
		}
	}

	private static String readLog(Path output) {
		try { return Files.readString(output); }
		catch (java.io.IOException failure) { return failure.toString(); }
	}

	/** Forked with the same test/runtime dependencies, but independent JPA caches and driver state. */
	public static class JpaClient {
		public static void main(String[] args) throws Exception {
			EntityManager manager = DatabaseStartup.open(args[0]);
			try {
				CurrentModelFixture.verify(manager);
				manager.getTransaction().begin();
				task(manager).setTick(CHILD_TICK);
				manager.getTransaction().commit();
			} finally { DatabaseStartup.close(manager); }
		}
	}
}
