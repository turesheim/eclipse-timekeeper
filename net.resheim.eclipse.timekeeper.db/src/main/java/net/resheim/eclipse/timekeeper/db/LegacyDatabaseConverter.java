package net.resheim.eclipse.timekeeper.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit, offline data conversion for the repository's historical V1/V2 schemas.
 * Not invoked during plugin startup. Callers must use a read-only source connection
 * and a separate, freshly created current-model database with no other clients.
 * A versioned target must be in RECOVERING state for the matching historical
 * source version. READY targets are never converted, even when empty.
 * Schema creation is deliberately outside the conversion transaction: H2 DDL
 * cannot be rolled back with the copied rows. Neither connection is closed here.
 */
public final class LegacyDatabaseConverter {
	private LegacyDatabaseConverter() { }

	public record Result(int sourceVersion, int projects, int tasks, int activities,
			int openActivities, Duration closedDuration) { }

	private static final Map<String, List<String>> CURRENT = DatabaseSchema.CURRENT;
	/**
 * Copies and verifies every mapped value before committing. Unknown/mixed schemas,
 * inconsistent associations, populated targets and unsupported engines are rejected.
 * A failed data copy is rolled back; callers should discard a failed target and
 * retain their untouched source/backup. This is not an in-place or H2 2.x migration.
 */
	public static Result convert(Connection source, Connection target) throws SQLException {
		requireH2(source);
		requireH2(target);
		if (!source.isReadOnly() || target.isReadOnly() || !source.getAutoCommit() || !target.getAutoCommit()) {
			throw new SQLException("Conversion requires an idle read-only source and an idle writable target");
		}
		DatabaseSchema.Kind sourceKind = DatabaseSchema.inspect(source);
		boolean v2 = sourceKind == DatabaseSchema.Kind.LEGACY_V2;
		if (!v2 && sourceKind != DatabaseSchema.Kind.LEGACY_V1) {
			throw new SQLException("Unsupported source schema: " + sourceKind);
		}
		DatabaseSchema.Kind targetKind = DatabaseSchema.inspect(target);
		if (targetKind != DatabaseSchema.Kind.CURRENT && targetKind != DatabaseSchema.Kind.RECOVERING) {
			throw new SQLException("Unsupported target schema; an empty current-model database is required");
		}
		if (targetKind == DatabaseSchema.Kind.CURRENT && DatabaseSchema.hasVersion(target)) {
			throw new SQLException("A versioned target must be prepared by the explicit recovery workflow, not ready for tracking");
		}
		requireMatchingOrigin(target, v2);
		for (String table : CURRENT.keySet()) {
			requireNoRows(target, "SELECT 1 FROM " + table, "Target must be empty: " + table);
		}
		validateSource(source, v2);
		Map<String, List<List<String>>> expected = readSource(source, v2);
		Result result = report(expected, v2 ? 2 : 1);
		target.setAutoCommit(false);
		try {
			for (String table : CURRENT.keySet()) {
				List<String> names = CURRENT.get(table);
				String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
				try (var insert = target.prepareStatement("INSERT INTO " + table + " ("
						+ String.join(",", names) + ") VALUES (" + placeholders + ")")) {
					for (List<String> row : expected.get(table)) {
						for (int i = 0; i < row.size(); i++) {
							// Break the task/current-activity cycle until activities exist.
							insert.setString(i + 1, table.equals("TASK") && i == 6 ? null : row.get(i));
						}
						insert.executeUpdate();
					}
				}
			}
			try (var update = target.prepareStatement(
					"UPDATE TASK SET CURRENTACTIVITY_ID=? WHERE TASK_ID=? AND REPOSITORY_URL=?")) {
				for (List<String> row : expected.get("TASK")) {
					if (row.get(6) != null) {
						update.setString(1, row.get(6));
						update.setString(2, row.get(0));
						update.setString(3, row.get(1));
						if (update.executeUpdate() != 1) {
							throw new SQLException("Could not restore the current activity reference");
						}
					}
				}
			}
			verifyRows(target, expected);
			target.commit();
		} catch (SQLException | RuntimeException failure) {
			try {
				target.rollback();
				// Never enable autocommit after a failed rollback: that could commit partial data.
				target.setAutoCommit(true);
			} catch (SQLException cleanup) {
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
		target.setAutoCommit(true);
		return result;
	}

	/** Revalidates every mapped value after reopening both databases read-only. */
	public static Result verify(Connection source, Connection target) throws SQLException {
		requireH2(source);
		requireH2(target);
		if (!source.isReadOnly() || !target.isReadOnly() || !source.getAutoCommit() || !target.getAutoCommit()) {
			throw new SQLException("Verification requires idle read-only connections");
		}
		DatabaseSchema.Kind kind = DatabaseSchema.inspect(source);
		boolean v2 = kind == DatabaseSchema.Kind.LEGACY_V2;
		DatabaseSchema.Kind targetKind = DatabaseSchema.inspect(target);
		if ((!v2 && kind != DatabaseSchema.Kind.LEGACY_V1)
				|| (targetKind != DatabaseSchema.Kind.CURRENT && targetKind != DatabaseSchema.Kind.RECOVERING)) {
			throw new SQLException("Unsupported source or target schema for conversion verification");
		}
		requireMatchingOrigin(target, v2);
		validateSource(source, v2);
		Map<String, List<List<String>>> expected = readSource(source, v2);
		verifyRows(target, expected);
		return report(expected, v2 ? 2 : 1);
	}

	private static void requireMatchingOrigin(Connection target, boolean v2) throws SQLException {
		if (DatabaseSchema.hasVersion(target)
				&& !DatabaseVersion.read(target).origin().equals(v2 ? "LEGACY_V2" : "LEGACY_V1")) {
			throw new SQLException("Recovery target belongs to a different historical source version");
		}
	}

	private static void verifyRows(Connection target, Map<String, List<List<String>>> expected) throws SQLException {
		for (String table : CURRENT.keySet()) {
			List<List<String>> actual = rows(target, "SELECT " + String.join(",", CURRENT.get(table)) + " FROM " + table);
			if (!sorted(expected.get(table)).equals(sorted(actual))) {
				throw new SQLException("Conversion verification failed for " + table);
			}
		}
	}

	private static void validateSource(Connection source, boolean v2) throws SQLException {
		requireNoRows(source, "SELECT 1 FROM ACTIVITY WHERE START_TIME IS NULL OR ADJUSTED IS NULL OR END_TIME < START_TIME",
				"Activity timestamps and adjustment flags must be valid");
		requireNoRows(source, "SELECT 1 FROM ACTIVITY a WHERE (TASK_ID IS NULL AND REPOSITORY_URL IS NOT NULL)"
				+ " OR (TASK_ID IS NOT NULL AND REPOSITORY_URL IS NULL) OR (TASK_ID IS NOT NULL AND NOT EXISTS"
				+ " (SELECT 1 FROM TRACKEDTASK t WHERE t.TASK_ID=a.TASK_ID AND t.REPOSITORY_URL=a.REPOSITORY_URL))",
				"Activity references a missing or incomplete task identity");
		requireNoRows(source, "SELECT 1 FROM TRACKEDTASK_ACTIVITY r WHERE NOT EXISTS (SELECT 1 FROM ACTIVITY a"
				+ " WHERE a.ID=r.ACTIVITIES_ID AND a.TASK_ID=r.TASK_ID AND a.REPOSITORY_URL=r.REPOSITORY_URL)",
				"Task/activity association disagrees with the activity's owner");
		requireNoRows(source, "SELECT 1 FROM ACTIVITY a WHERE TASK_ID IS NOT NULL AND NOT EXISTS"
				+ " (SELECT 1 FROM TRACKEDTASK_ACTIVITY r WHERE r.ACTIVITIES_ID=a.ID"
				+ " AND r.TASK_ID=a.TASK_ID AND r.REPOSITORY_URL=a.REPOSITORY_URL)",
				"Activity is missing its task/activity association");
		requireNoRows(source, "SELECT 1 FROM TRACKEDTASK t WHERE CURRENTACTIVITY_ID IS NOT NULL AND NOT EXISTS"
				+ " (SELECT 1 FROM ACTIVITY a WHERE a.ID=t.CURRENTACTIVITY_ID"
				+ " AND a.TASK_ID=t.TASK_ID AND a.REPOSITORY_URL=t.REPOSITORY_URL)",
				"Current activity does not belong to its task");
		if (v2) {
			requireNoRows(source, "SELECT 1 FROM ACTIVITY WHERE TASK_ID IS NOT NULL AND PROJECT IS NOT NULL",
					"Activity cannot reference both a task and a project");
			for (String table : List.of("TRACKEDTASK", "ACTIVITY")) {
				requireNoRows(source, "SELECT 1 FROM " + table + " t WHERE PROJECT IS NOT NULL AND NOT EXISTS"
						+ " (SELECT 1 FROM PROJECT p WHERE p.NAME=t.PROJECT)", "Missing project referenced by " + table);
			}
		}
	}

	private static Map<String, List<List<String>>> readSource(Connection source, boolean v2) throws SQLException {
		Map<String, List<List<String>>> result = new LinkedHashMap<>();
		CURRENT.keySet().forEach(table -> result.put(table, new ArrayList<>()));
		if (v2) {
			result.put("PROJECT", rows(source, "SELECT NAME,REPOSITORY_URL,EXTERNAL_ID,NULL,NULL,NULL FROM PROJECT"));
			result.put("PROJECT_TASK", rows(source, "SELECT PROJECT,TASK_ID,REPOSITORY_URL FROM TRACKEDTASK WHERE PROJECT IS NOT NULL"));
			result.put("PROJECT_ACTIVITY", rows(source, "SELECT PROJECT,CAST(ID AS VARCHAR) FROM ACTIVITY WHERE PROJECT IS NOT NULL"));
		}
		result.put("TASK", rows(source, "SELECT TASK_ID,REPOSITORY_URL,TICK,"
				+ (v2 ? "TASK_URL,TASK_SUMMARY,PROJECT" : "NULL,NULL,NULL")
				+ ",CAST(CURRENTACTIVITY_ID AS VARCHAR) FROM TRACKEDTASK"));
		result.put("ACTIVITY", rows(source, "SELECT CAST(ID AS VARCHAR),START_TIME,END_TIME,ADJUSTED,SUMMARY,"
				+ "TASK_ID,REPOSITORY_URL," + (v2 ? "PROJECT" : "NULL") + " FROM ACTIVITY"));
		result.put("TASK_ACTIVITY", rows(source,
				"SELECT TASK_ID,REPOSITORY_URL,CAST(ACTIVITIES_ID AS VARCHAR) FROM TRACKEDTASK_ACTIVITY"));
		return result;
	}

	private static Result report(Map<String, List<List<String>>> data, int version) {
		Duration total = Duration.ZERO;
		int open = 0;
		for (List<String> activity : data.get("ACTIVITY")) {
			if (activity.get(2) == null) {
				open++;
			} else {
				total = total.plus(Duration.between(LocalDateTime.parse(activity.get(1).replace(' ', 'T')),
						LocalDateTime.parse(activity.get(2).replace(' ', 'T'))));
			}
		}
		return new Result(version, data.get("PROJECT").size(), data.get("TASK").size(),
				data.get("ACTIVITY").size(), open, total);
	}

	private static void requireH2(Connection connection) throws SQLException {
		if (!connection.getMetaData().getDatabaseProductName().equals("H2")
				|| !connection.getMetaData().getDatabaseProductVersion().startsWith("1.4.194")) {
			throw new SQLException("Only the verified H2 1.4.194 conversion is supported");
		}
	}

	private static void requireNoRows(Connection connection, String sql, String message) throws SQLException {
		try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			if (rows.next()) {
				throw new SQLException(message);
			}
		}
	}

	private static List<List<String>> rows(Connection connection, String sql) throws SQLException {
		List<List<String>> result = new ArrayList<>();
		try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				List<String> row = new ArrayList<>();
				for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
					row.add(rows.getString(column));
				}
				result.add(row);
			}
		}
		return result;
	}

	private static List<List<String>> sorted(List<List<String>> rows) {
		return rows.stream().sorted(Comparator.comparing(Object::toString)).toList();
	}
}
