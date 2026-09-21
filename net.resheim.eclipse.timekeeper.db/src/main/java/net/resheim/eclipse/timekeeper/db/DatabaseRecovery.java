package net.resheim.eclipse.timekeeper.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

import javax.persistence.EntityManager;

/**
 * Explicit recovery from a trusted H2 1.4.194 backup, never from a live database.
 * All output is reserved in a new directory. Failed/interrupted output is retained
 * for diagnosis, never reused, and must not be selected for normal time tracking.
 * This class neither reads nor writes the plugin's database preferences.
 */
public final class DatabaseRecovery {
	private static final long MAX_BACKUP_BYTES = 256L * 1024 * 1024;
	private static final long MAX_DATABASE_BYTES = 1024L * 1024 * 1024;
	private static final String RECEIPT_VERSION = "1";
	private static final String CONVERSION_VERSION = "legacy-v1-v2-to-current-1";

	private DatabaseRecovery() { }

	public record Result(Path directory, String jdbcUrl, LegacyDatabaseConverter.Result data) { }

	/**
	 * Accepts a ZIP with exactly one .mv.db entry (no paths or auxiliary files).
	 * The destination must not exist. Cancellation has the same recovery policy as
	 * failure: preserve the backup, abandon the candidate and retry in a new folder.
	 */
	public static Result recover(Path backup, Path destination, BooleanSupplier cancelled)
			throws IOException, SQLException {
		Path input = backup.toAbsolutePath().normalize();
		Path directory = destination.toAbsolutePath().normalize();
		if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Select a regular backup ZIP file, not a link or a database in use.");
		}
		// Paths become JDBC URLs, so do not allow a filename to inject H2 settings.
		jdbcUrl(directory, "converted");
		checkCancelled(cancelled);
		Files.createDirectory(directory); // Never overwrite, merge with, or reuse an earlier attempt.
		Properties receipt = new Properties();
		receipt.setProperty("receipt.version", RECEIPT_VERSION);
		receipt.setProperty("conversion.version", CONVERSION_VERSION);
		receipt.setProperty("engine", "H2 1.4.194");
		receipt.setProperty("started", Instant.now().toString());
		writeProperties(directory.resolve("started.properties"), receipt);

		Path retained = directory.resolve("backup.zip");
		String inputHash = hash(input, MAX_BACKUP_BYTES, cancelled);
		try (InputStream stream = Files.newInputStream(input)) {
			copy(stream, retained, MAX_BACKUP_BYTES, cancelled);
		}
		if (!inputHash.equals(hash(retained, MAX_BACKUP_BYTES, cancelled))
				|| !inputHash.equals(hash(input, MAX_BACKUP_BYTES, cancelled))) {
			throw new IOException("The backup changed while being copied. Keep this attempt and use a stable backup.");
		}
		receipt.setProperty("backup.sha256", inputHash);
		Path sourceFile = directory.resolve("source.mv.db");
		try (ZipFile zip = new ZipFile(retained.toFile())) {
			var entries = zip.entries();
			if (!entries.hasMoreElements()) throw new IOException("The backup ZIP is empty.");
			var entry = entries.nextElement();
			String name = entry.getName();
			if (entries.hasMoreElements() || entry.isDirectory() || !name.endsWith(".mv.db")
					|| name.contains("/") || name.contains("\\") || name.contains(":")) {
				throw new IOException("The backup must contain exactly one .mv.db file without folders or other files.");
			}
			// Never use an archive entry name as an output path.
			try (InputStream stream = zip.getInputStream(entry)) {
				copy(stream, sourceFile, MAX_DATABASE_BYTES, cancelled);
			}
		}
		String sourceHash = hash(sourceFile, MAX_DATABASE_BYTES, cancelled);
		receipt.setProperty("source.sha256", sourceHash);
		String targetUrl = jdbcUrl(directory, "converted");
		LegacyDatabaseConverter.Result data;
		try (Connection source = connect(jdbcUrl(directory, "source"), true)) {
			DatabaseSchema.Kind kind = DatabaseSchema.inspect(source);
			if (kind != DatabaseSchema.Kind.LEGACY_V1 && kind != DatabaseSchema.Kind.LEGACY_V2) {
				throw new SQLException("This recovery tool supports only historical V1/V2 databases, not " + kind);
			}
			checkCancelled(cancelled);
			EntityManager manager = DatabaseStartup.open(targetUrl);
			DatabaseStartup.close(manager);
			try (Connection target = connect(targetUrl, false)) {
				data = LegacyDatabaseConverter.convert(source, target);
			}
		}
		checkCancelled(cancelled);
		// Reopen without schema generation and exercise JPA before the final, read-only
		// comparison. No core startup cleanup, activity inference or label seeding runs.
		EntityManager manager = DatabaseStartup.open(targetUrl + ";IFEXISTS=TRUE");
		try {
			for (String entity : new String[] { "Project", "Task", "Activity" }) {
				manager.createQuery("SELECT e FROM " + entity + " e").getResultList();
			}
		} finally {
			DatabaseStartup.close(manager);
		}
		if (!data.equals(verifyData(directory))) throw new SQLException("Reopened conversion totals differ.");
		if (!sourceHash.equals(hash(sourceFile, MAX_DATABASE_BYTES, cancelled))) {
			throw new IOException("The extracted source changed during conversion.");
		}
		receipt.setProperty("target.sha256", hash(directory.resolve("converted.mv.db"), MAX_DATABASE_BYTES, cancelled));
		receipt.setProperty("target.jdbcUrl", targetUrl + ";IFEXISTS=TRUE");
		receipt.setProperty("source.version", Integer.toString(data.sourceVersion()));
		receipt.setProperty("projects", Integer.toString(data.projects()));
		receipt.setProperty("tasks", Integer.toString(data.tasks()));
		receipt.setProperty("activities", Integer.toString(data.activities()));
		receipt.setProperty("open.activities", Integer.toString(data.openActivities()));
		receipt.setProperty("closed.duration", data.closedDuration().toString());
		receipt.setProperty("validated", Instant.now().toString());
		checkCancelled(cancelled);
		// An incomplete write never looks like success. If atomic rename is unsupported,
		// fail closed and retain the candidate rather than publish a partial receipt.
		Path pending = directory.resolve("validated.properties.pending");
		writeProperties(pending, receipt);
		checkCancelled(cancelled);
		Files.move(pending, directory.resolve("validated.properties"), StandardCopyOption.ATOMIC_MOVE);
		return new Result(directory, targetUrl + ";IFEXISTS=TRUE", data);
	}

	/** Checks a completed, unused recovery directory without changing either database. */
	public static Result verify(Path destination) throws IOException, SQLException {
		Path directory = destination.toAbsolutePath().normalize();
		Properties receipt = new Properties();
		try (InputStream stream = Files.newInputStream(directory.resolve("validated.properties"))) {
			receipt.load(stream);
		}
String expectedTargetUrl = jdbcUrl(directory, "converted") + ";IFEXISTS=TRUE";
		if (!RECEIPT_VERSION.equals(receipt.getProperty("receipt.version"))
				|| !CONVERSION_VERSION.equals(receipt.getProperty("conversion.version"))
				|| !"H2 1.4.194".equals(receipt.getProperty("engine"))
				|| !expectedTargetUrl.equals(receipt.getProperty("target.jdbcUrl"))) {
			throw new IOException("Unsupported recovery receipt version or metadata.");
		}
		for (String name : new String[] { "backup", "source", "target" }) {
			String file = name.equals("backup") ? "backup.zip" : name.equals("source") ? "source.mv.db" : "converted.mv.db";
			long limit = name.equals("backup") ? MAX_BACKUP_BYTES : MAX_DATABASE_BYTES;
			if (!hash(directory.resolve(file), limit, () -> false).equals(receipt.getProperty(name + ".sha256"))) {
				throw new IOException("Recovery file changed: " + file + ". Do not reuse this receipt after time tracking starts.");
			}
		}
		LegacyDatabaseConverter.Result data = verifyData(directory);
		if (!Integer.toString(data.sourceVersion()).equals(receipt.getProperty("source.version"))
				|| !Integer.toString(data.projects()).equals(receipt.getProperty("projects"))
				|| !Integer.toString(data.tasks()).equals(receipt.getProperty("tasks"))
				|| !Integer.toString(data.activities()).equals(receipt.getProperty("activities"))
				|| !Integer.toString(data.openActivities()).equals(receipt.getProperty("open.activities"))
				|| !data.closedDuration().toString().equals(receipt.getProperty("closed.duration"))) {
			throw new IOException("Recovery receipt metadata does not match the verified data.");
		}
		return new Result(directory, expectedTargetUrl, data);
	}

	private static LegacyDatabaseConverter.Result verifyData(Path directory) throws SQLException, IOException {
		try (Connection source = connect(jdbcUrl(directory, "source"), true);
				Connection target = connect(jdbcUrl(directory, "converted"), true)) {
			return LegacyDatabaseConverter.verify(source, target);
		}
	}

	private static Connection connect(String url, boolean readOnly) throws SQLException {
		Properties credentials = new Properties();
		credentials.setProperty("user", "sa");
		credentials.setProperty("password", "");
		return new org.h2.Driver().connect(url + ";IFEXISTS=TRUE" + (readOnly ? ";ACCESS_MODE_DATA=r" : ""), credentials);
	}

	private static String jdbcUrl(Path directory, String name) throws IOException {
		if (java.io.File.separatorChar == '/' && directory.toString().contains("\\")) {
			throw new IOException("Choose a recovery folder without backslashes in its path.");
		}
		String path = directory.resolve(name).toString().replace('\\', '/');
		if (path.contains(";") || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
			throw new IOException("Choose a recovery folder without semicolons or line breaks in its path.");
		}
		return "jdbc:h2:" + path;
	}

	private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
		if (cancelled.getAsBoolean()) throw new InterruptedIOException("Recovery cancelled. Do not use this incomplete attempt.");
	}

	static void copy(InputStream input, Path target, long limit, BooleanSupplier cancelled) throws IOException {
		try (FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			byte[] bytes = new byte[8192];
			long total = 0;
			int count;
			while ((count = input.read(bytes)) != -1) {
				checkCancelled(cancelled);
				if ((total += count) > limit) throw new IOException("Backup or database exceeds the recovery size limit.");
				ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
				while (buffer.hasRemaining()) output.write(buffer);
			}
			output.force(true);
		}
	}

	private static String hash(Path file, long limit, BooleanSupplier cancelled) throws IOException {
		try (InputStream stream = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = new byte[8192];
			long total = 0;
			int count;
			while ((count = stream.read(bytes)) != -1) {
				checkCancelled(cancelled);
				if ((total += count) > limit) throw new IOException("Backup or database exceeds the recovery size limit.");
				digest.update(bytes, 0, count);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static void writeProperties(Path path, Properties properties) throws IOException {
		var bytes = new java.io.ByteArrayOutputStream();
		properties.store(bytes, "Timekeeper recovery receipt - not database schema history");
		try (var input = new java.io.ByteArrayInputStream(bytes.toByteArray())) {
			copy(input, path, Long.MAX_VALUE, () -> false);
		}
	}
}
