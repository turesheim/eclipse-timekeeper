package net.resheim.eclipse.timekeeper.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Logical export/import of the current model from H2 1.4.194 to H2 2.5.250. */
final class CurrentDatabaseConverter {
	private CurrentDatabaseConverter() { }

	static LegacyDatabaseConverter.Result convert(Connection source, Connection target) throws SQLException {
		check(source, target, false);
		for (String table : DatabaseSchema.CURRENT.keySet()) {
			LegacyDatabaseConverter.requireNoRows(target, "SELECT 1 FROM " + table, "Target must be empty: " + table);
		}
		Map<String, List<List<String>>> expected = read(source);
		LegacyDatabaseConverter.copyRows(target, expected);
		return report(source, expected);
	}

	static LegacyDatabaseConverter.Result verify(Connection source, Connection target) throws SQLException {
		check(source, target, true);
		Map<String, List<List<String>>> expected = read(source);
		LegacyDatabaseConverter.verifyRows(target, expected);
		return report(source, expected);
	}

	private static void check(Connection source, Connection target, boolean verification) throws SQLException {
		LegacyDatabaseConverter.requireH2(source, "1.4.194");
		LegacyDatabaseConverter.requireH2(target, "2.5.250");
		if (!source.isReadOnly() || target.isReadOnly() != verification
				|| !source.getAutoCommit() || !target.getAutoCommit()
				|| DatabaseSchema.inspect(source) != DatabaseSchema.Kind.CURRENT) {
			throw new SQLException("Engine upgrade requires an idle read-only current-model source and a separate target");
		}
		DatabaseSchema.Kind kind = DatabaseSchema.inspect(target);
		if (kind != DatabaseSchema.Kind.RECOVERING && !(verification && kind == DatabaseSchema.Kind.CURRENT)) {
			throw new SQLException("Engine upgrade target has not been prepared by the recovery workflow");
		}
		if (!DatabaseVersion.read(target).origin().equals("H2_1_4")) {
			throw new SQLException("Engine upgrade target has a different origin");
		}
		LegacyDatabaseConverter.requireNoRows(source,
				"SELECT 1 FROM ACTIVITY WHERE START_TIME IS NULL OR ADJUSTED IS NULL OR END_TIME < START_TIME",
				"Activity timestamps and adjustment flags must be valid");
	}

	private static Map<String, List<List<String>>> read(Connection source) throws SQLException {
		Map<String, List<List<String>>> result = new LinkedHashMap<>();
		for (var table : DatabaseSchema.CURRENT.entrySet()) {
			result.put(table.getKey(), LegacyDatabaseConverter.rows(source,
					"SELECT " + String.join(",", table.getValue()) + " FROM " + table.getKey()));
		}
		return result;
	}

	private static LegacyDatabaseConverter.Result report(Connection source, Map<String, List<List<String>>> data)
			throws SQLException {
		// Zero identifies an unstamped current model; stamped current-model version is 1.
		return LegacyDatabaseConverter.report(data, DatabaseSchema.hasVersion(source) ? DatabaseVersion.read(source).version() : 0);
	}
}
