package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Duration;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.h2.tools.Backup;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseRecoveryTest {
	@TempDir Path directory;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void recoversClosedBackupAndReverifiesWithoutChangingOriginal(boolean v2) throws Exception {
		Path backup = backup(v2, null);
		byte[] original = Files.readAllBytes(directory.resolve("original.mv.db"));
		byte[] zipped = Files.readAllBytes(backup);
		Path output = directory.resolve("recovery with spaces and é");
		var result = DatabaseRecovery.recover(backup, output, () -> false);
		assertEquals(new LegacyDatabaseConverter.Result(v2 ? 2 : 1, v2 ? 2 : 0,
				v2 ? 3 : 0, v2 ? 5 : 0, 0, Duration.ofSeconds(v2 ? 19800 : 0)), result.data());
		assertTrue(result.jdbcUrl().endsWith("/converted;IFEXISTS=TRUE"));
		assertTrue(Files.exists(output.resolve("started.properties")));
		assertTrue(Files.exists(output.resolve("validated.properties")));
		assertFalse(Files.exists(output.resolve("validated.properties.pending")));
		byte[] converted = Files.readAllBytes(output.resolve("converted.mv.db"));
		assertEquals(result, DatabaseRecovery.verify(output));
		assertArrayEquals(converted, Files.readAllBytes(output.resolve("converted.mv.db")));
		assertArrayEquals(original, Files.readAllBytes(directory.resolve("original.mv.db")));
		assertArrayEquals(zipped, Files.readAllBytes(backup));
		assertArrayEquals(zipped, Files.readAllBytes(output.resolve("backup.zip")));
		assertArrayEquals(original, Files.readAllBytes(output.resolve("source.mv.db")));
		Properties receipt = receipt(output);
		assertEquals("1", receipt.getProperty("receipt.version"));
		assertEquals(result.jdbcUrl(), receipt.getProperty("target.jdbcUrl"));
		assertEquals(v2 ? "PT5H30M" : "PT0S", receipt.getProperty("closed.duration"));
		try (Connection target = readOnly(output.resolve("converted"))) {
			assertEquals(new DatabaseVersion.Stamp(1, "READY", v2 ? "LEGACY_V2" : "LEGACY_V1"), DatabaseVersion.read(target));
		}
	}

	@Test
	void retainsOpenActivitiesWithoutRunningStartupCleanup() throws Exception {
		Path backup = backup(true, "UPDATE ACTIVITY SET END_TIME=NULL WHERE ID='00000000-0000-0000-0000-000000000001'");
		var result = DatabaseRecovery.recover(backup, directory.resolve("recovery"), () -> false);
		assertEquals(1, result.data().openActivities());
		assertEquals(Duration.ofSeconds(14400), result.data().closedDuration());
		assertEquals(result, DatabaseRecovery.verify(result.directory()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "CREATE TABLE UNRECOGNIZED(ID INT)",
			"DELETE FROM TRACKEDTASK_ACTIVITY WHERE ACTIVITIES_ID='00000000-0000-0000-0000-000000000001'" })
	void failedConversionsRetainBackupButCannotBeVerifiedOrRetriedInPlace(String mutation) throws Exception {
		Path backup = backup(true, mutation);
		byte[] original = Files.readAllBytes(directory.resolve("original.mv.db"));
		Path output = directory.resolve("recovery");
		assertThrows(SQLException.class, () -> DatabaseRecovery.recover(backup, output, () -> false));
		assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(output.resolve("backup.zip")));
		assertArrayEquals(original, Files.readAllBytes(directory.resolve("original.mv.db")));
		assertFalse(Files.exists(output.resolve("validated.properties")));
		assertThrows(IOException.class, () -> DatabaseRecovery.verify(output));
		assertThrows(IOException.class, () -> DatabaseRecovery.recover(backup, output, () -> false));
	}

	@Test
	void cancellationAfterCommitDoesNotPublishSuccessAndNewAttemptWorks() throws Exception {
		Path backup = backup(true, null);
		Path output = directory.resolve("cancelled");
		assertThrows(InterruptedIOException.class, () -> DatabaseRecovery.recover(backup, output,
				() -> Files.exists(output.resolve("converted.mv.db"))));
		assertTrue(Files.exists(output.resolve("converted.mv.db")));
		try (Connection target = readOnly(output.resolve("converted")); var statement = target.createStatement();
				var rows = statement.executeQuery("SELECT COUNT(*) FROM ACTIVITY")) {
			assertTrue(rows.next());
			assertEquals(5, rows.getInt(1)); // The data commit completed, but validation did not.
		}
		assertTrue(Files.exists(output.resolve("backup.zip")));
		assertFalse(Files.exists(output.resolve("validated.properties")));
		assertThrows(IOException.class, () -> DatabaseRecovery.verify(output));
		var retry = DatabaseRecovery.recover(backup, directory.resolve("retry"), () -> false);
		assertThrows(SQLException.class, () -> DatabaseStartup.open("jdbc:h2:" + output.resolve("converted") + ";IFEXISTS=TRUE"));
		try (Connection target = readOnly(output.resolve("converted"))) {
			assertEquals("RECOVERING", DatabaseVersion.read(target).state());
		}
		assertEquals(5, retry.data().activities());
	}

	@Test
	void cancellationBeforeReservationDoesNotCreateOutput() throws Exception {
		Path backup = backup(true, null);
		Path output = directory.resolve("cancelled");
		assertThrows(InterruptedIOException.class, () -> DatabaseRecovery.recover(backup, output, () -> true));
		assertFalse(Files.exists(output));
	}

	@Test
	void lateCancellationAfterDatabaseValidationStillDoesNotPublishAReceipt() throws Exception {
		Path backup = backup(true, null);
		Path output = directory.resolve("late-cancellation");
		assertThrows(InterruptedIOException.class, () -> DatabaseRecovery.recover(backup, output, () -> {
			if (!Files.exists(output.resolve("converted.mv.db"))) return false;
			try (Connection target = readOnly(output.resolve("converted"))) {
				return DatabaseVersion.read(target).state().equals("READY");
			} catch (SQLException failure) {
				throw new AssertionError(failure);
			}
		}));
		try (Connection target = readOnly(output.resolve("converted"))) {
			assertEquals("READY", DatabaseVersion.read(target).state());
		}
		assertFalse(Files.exists(output.resolve("validated.properties")));
		assertThrows(IOException.class, () -> DatabaseRecovery.verify(output));
	}

	@Test
	void neverOverwritesExistingDestination() throws Exception {
		Path backup = backup(true, null);
		Path output = Files.createDirectory(directory.resolve("existing"));
		Files.writeString(output.resolve("keep.txt"), "User data");
		assertThrows(IOException.class, () -> DatabaseRecovery.recover(backup, output, () -> false));
		assertEquals("User data", Files.readString(output.resolve("keep.txt")));
		assertFalse(Files.exists(output.resolve("started.properties")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "../escape.mv.db", "/escape.mv.db", "nested/x.mv.db", "x.h2.db",
			"x.mv.db:bad", "x\\bad.mv.db", "not-db.txt" })
	void rejectsUnsupportedArchiveEntries(String entry) throws Exception {
		Path backup = zip(entry);
		Path output = directory.resolve("recovery");
		assertThrows(IOException.class, () -> DatabaseRecovery.recover(backup, output, () -> false));
		assertFalse(Files.exists(output.resolve("source.mv.db")));
		assertFalse(Files.exists(directory.resolve("escape.mv.db")));
		assertFalse(Files.exists(output.resolve("validated.properties")));
	}

	@Test
	void rejectsMultipleDatabaseFiles() throws Exception {
		Path backup = zip("one.mv.db", "two.mv.db");
		Path output = directory.resolve("recovery");
		assertThrows(IOException.class, () -> DatabaseRecovery.recover(backup, output, () -> false));
		assertFalse(Files.exists(output.resolve("source.mv.db")));
	}

	@Test
	void rejectsInvalidZipAndJdbcSettingsInDestination() throws Exception {
		Path backup = Files.writeString(directory.resolve("invalid.zip"), "not a ZIP");
		assertThrows(IOException.class, () -> DatabaseRecovery.recover(backup, directory.resolve("invalid"), () -> false));
		Path injection = directory.resolve("output;INIT=DROP ALL OBJECTS");
		assertThrows(IOException.class, () -> DatabaseRecovery.recover(backup, injection, () -> false));
		assertFalse(Files.exists(injection));
	}

	@Test
	void boundedCopyNeverOverwritesOrExceedsLimit() throws Exception {
		Path output = directory.resolve("bounded");
		assertThrows(IOException.class, () -> DatabaseRecovery.copy(new ByteArrayInputStream(new byte[20]),
				output, 10, () -> false));
		assertTrue(Files.size(output) <= 10);
		assertThrows(IOException.class, () -> DatabaseRecovery.copy(new ByteArrayInputStream(new byte[1]),
				output, 10, () -> false));
	}

	@Test
	void detectsChangedTargetEvenWhenCountsAndDurationMatch() throws Exception {
		Path backup = backup(true, null);
		Path output = directory.resolve("recovery");
		var result = DatabaseRecovery.recover(backup, output, () -> false);
		try (Connection target = DriverManager.getConnection(result.jdbcUrl(), "sa", ""); var statement = target.createStatement()) {
			statement.executeUpdate("UPDATE ACTIVITY SET SUMMARY='Changed'");
		}
		assertThrows(IOException.class, () -> DatabaseRecovery.verify(output));
		try (Connection source = readOnly(output.resolve("source")); Connection target = readOnly(output.resolve("converted"))) {
			assertThrows(SQLException.class, () -> LegacyDatabaseConverter.verify(source, target));
		}
	}

	@Test
	void rejectsUnknownReceiptVersion() throws Exception {
		Path backup = backup(true, null);
		Path output = directory.resolve("recovery");
		DatabaseRecovery.recover(backup, output, () -> false);
		Properties receipt = receipt(output);
		receipt.setProperty("receipt.version", "999");
		try (var stream = Files.newOutputStream(output.resolve("validated.properties"))) {
			receipt.store(stream, "Test mutation");
		}
		assertThrows(IOException.class, () -> DatabaseRecovery.verify(output));
	}

	@ParameterizedTest
	@ValueSource(strings = { "engine", "target.jdbcUrl", "conversion.version", "source.version", "projects", "tasks",
			"activities", "open.activities", "closed.duration" })
	void rejectsChangedReceiptMetadata(String property) throws Exception {
		Path output = directory.resolve("recovery");
		DatabaseRecovery.recover(backup(true, null), output, () -> false);
		Properties receipt = receipt(output);
		receipt.setProperty(property, "invalid");
		try (var stream = Files.newOutputStream(output.resolve("validated.properties"))) {
			receipt.store(stream, "Test mutation");
		}
		assertThrows(IOException.class, () -> DatabaseRecovery.verify(output));
	}

	@Test
	void stillVerifiesThePreviousUnversionedReceiptFormatWithoutStampingIt() throws Exception {
		Path output = directory.resolve("recovery");
		var result = DatabaseRecovery.recover(backup(true, null), output, () -> false);
		// Reproduce PR #192's target/receipt format using the same synthetic data.
		try (Connection target = DriverManager.getConnection(result.jdbcUrl(), "sa", ""); var statement = target.createStatement()) {
			statement.execute("DROP TABLE TIMEKEEPER_SCHEMA");
		}
		Properties receipt = receipt(output);
		receipt.setProperty("conversion.version", "legacy-v1-v2-to-current-1");
		receipt.setProperty("target.sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(Files.readAllBytes(output.resolve("converted.mv.db")))));
		try (var stream = Files.newOutputStream(output.resolve("validated.properties"))) {
			receipt.store(stream, "Synthetic previous-format receipt");
		}
		assertEquals(result, DatabaseRecovery.verify(output));
		DatabaseStartup.close(DatabaseStartup.open(result.jdbcUrl()));
		try (Connection target = readOnly(output.resolve("converted"))) {
			assertFalse(DatabaseSchema.hasVersion(target));
		}
	}

	@Test
	void cannotDisguiseAVersionedTargetAsTheOldReceiptFormat() throws Exception {
		Path output = directory.resolve("recovery");
		DatabaseRecovery.recover(backup(true, null), output, () -> false);
		Properties receipt = receipt(output);
		receipt.setProperty("conversion.version", "legacy-v1-v2-to-current-1");
		try (var stream = Files.newOutputStream(output.resolve("validated.properties"))) {
			receipt.store(stream, "Test mutation");
		}
		assertThrows(IOException.class, () -> DatabaseRecovery.verify(output));
	}

	private Properties receipt(Path output) throws IOException {
		Properties receipt = new Properties();
		try (var stream = Files.newInputStream(output.resolve("validated.properties"))) {
			receipt.load(stream);
		}
		return receipt;
	}

	private Path backup(boolean v2, String mutation) throws Exception {
		try (Connection source = DriverManager.getConnection("jdbc:h2:" + directory.resolve("original"), "sa", "")) {
			run(source, "V1__baseline.sql");
			if (v2) {
				run(source, "V2__add_project_taskurl_and_tasksummary.sql");
				run(source, "legacy-data.sql");
			}
			if (mutation != null) {
				try (var statement = source.createStatement()) { statement.execute(mutation); }
			}
		}
		Path backup = directory.resolve("backup.zip");
		Backup.execute(backup.toString(), directory.toString(), "original", true);
		return backup;
	}

	private void run(Connection connection, String resource) throws Exception {
		try (var stream = getClass().getResourceAsStream("/legacy-fixture/" + resource)) {
			assertNotNull(stream);
			try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { RunScript.execute(connection, reader); }
		}
	}

	private Path zip(String... names) throws IOException {
		Path zip = directory.resolve("input.zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (String name : names) {
				output.putNextEntry(new ZipEntry(name));
				output.write(new byte[] { 1, 2, 3 });
				output.closeEntry();
			}
		}
		return zip;
	}

	private Connection readOnly(Path file) throws SQLException {
		return DriverManager.getConnection("jdbc:h2:" + file + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
	}
}
