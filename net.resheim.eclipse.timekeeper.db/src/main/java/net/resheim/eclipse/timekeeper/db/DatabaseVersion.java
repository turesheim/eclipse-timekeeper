package net.resheim.eclipse.timekeeper.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.Set;

/** Durable schema identity and initialization state, not a record of every data write. */
final class DatabaseVersion {
	static final String TABLE = "TIMEKEEPER_SCHEMA";
	static final int CURRENT = 1;
	static final Set<String> COLUMNS = Set.of("ID", "VERSION", "STATE", "ORIGIN");
	private static final Set<String> ORIGINS = Set.of("NEW", "MIGRATION");
	private static final Set<String> STATES = Set.of("CREATING", "MIGRATING", "READY");

	record Stamp(int version, String state, String origin) { }

	private DatabaseVersion() { }

	static Stamp read(Connection connection) throws SQLException {
		Map<String, Integer> types = Map.of("ID", Types.INTEGER, "VERSION", Types.INTEGER,
				"STATE", Types.VARCHAR, "ORIGIN", Types.VARCHAR);
		try (var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT ID,VERSION,STATE,ORIGIN FROM " + TABLE)) {
			var metadata = rows.getMetaData();
			for (int column = 1; column <= metadata.getColumnCount(); column++) {
				if (metadata.getColumnType(column) != types.get(metadata.getColumnName(column))) {
					throw new SQLException("Invalid Timekeeper schema-version column type. Preserve the database for diagnosis.");
				}
			}
			if (!rows.next() || !Integer.valueOf(1).equals(rows.getObject("ID"))) {
				throw new SQLException("Missing or invalid Timekeeper schema-version record. Do not retry initialization in place.");
			}
			if (!Integer.valueOf(CURRENT).equals(rows.getObject("VERSION"))) {
				throw new SQLException("Unsupported Timekeeper database version: " + rows.getObject("VERSION")
						+ ". This plugin supports version " + CURRENT + "; no schema changes were made.");
			}
			String state = rows.getString("STATE");
			String origin = rows.getString("ORIGIN");
			if (state == null || origin == null || !STATES.contains(state) || !ORIGINS.contains(origin)
					|| (state.equals("MIGRATING") && origin.equals("NEW")) || rows.next()) {
				throw new SQLException("Invalid Timekeeper schema-version state. Preserve the database for diagnosis.");
			}
			return new Stamp(CURRENT, state, origin);
		}
	}

	/** DDL can commit independently: an empty marker table is deliberately not repaired. */
	static void begin(Connection connection, String origin) throws SQLException {
		requireWritableIdle(connection);
		if (!ORIGINS.contains(origin) || DatabaseSchema.inspect(connection) != DatabaseSchema.Kind.EMPTY) {
			throw new SQLException("Schema versioning can only start in a new, empty Timekeeper database.");
		}
		try (var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE " + TABLE + " (ID INTEGER PRIMARY KEY CHECK (ID=1),"
					+ " VERSION INTEGER NOT NULL, STATE VARCHAR(16) NOT NULL, ORIGIN VARCHAR(16) NOT NULL)");
		}
		try (var statement = connection.prepareStatement("INSERT INTO " + TABLE + " VALUES (1,?,'CREATING',?)")) {
			statement.setInt(1, CURRENT);
			statement.setString(2, origin);
			statement.executeUpdate();
		}
	}

	static void schemaCreated(Connection connection) throws SQLException {
		Stamp stamp = read(connection);
		advance(connection, "CREATING", stamp.origin().equals("NEW") ? "READY" : "MIGRATING");
	}

	/** Future migration runners must validate copied data after reopen before calling this. */
	static void migrationValidated(Connection connection) throws SQLException {
		advance(connection, "MIGRATING", "READY");
	}

	private static void advance(Connection connection, String expected, String next) throws SQLException {
		requireWritableIdle(connection);
		Stamp stamp = read(connection);
		if (!expected.equals(stamp.state()) || !DatabaseSchema.hasCurrentTables(connection)) {
			throw new SQLException("Timekeeper schema initialization is incomplete; its version state was not advanced.");
		}
		try (var statement = connection.prepareStatement("UPDATE " + TABLE
				+ " SET STATE=? WHERE ID=1 AND VERSION=? AND STATE=? AND ORIGIN=?")) {
			statement.setString(1, next);
			statement.setInt(2, CURRENT);
			statement.setString(3, expected);
			statement.setString(4, stamp.origin());
			if (statement.executeUpdate() != 1) throw new SQLException("Timekeeper schema-version state changed concurrently.");
		}
	}

	private static void requireWritableIdle(Connection connection) throws SQLException {
		if (connection.isReadOnly() || !connection.getAutoCommit()) {
			throw new SQLException("Schema version writes require an idle writable connection.");
		}
	}
}
