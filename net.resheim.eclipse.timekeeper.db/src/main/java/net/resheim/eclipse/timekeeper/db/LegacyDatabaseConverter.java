package net.resheim.eclipse.timekeeper.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Explicit, offline data conversion for the repository's historical V1/V2 schemas.
 * Not invoked during plugin startup. Callers must use a read-only source connection
 * and a separate, freshly created current-model database with no other clients.
 * Schema creation is deliberately outside the conversion transaction: H2 DDL
 * cannot be rolled back with the copied rows. Neither connection is closed here.
 */
public final class LegacyDatabaseConverter {
	private LegacyDatabaseConverter() { }

	public record Result(int sourceVersion, int projects, int tasks, int activities,
			int openActivities, Duration closedDuration) { }

	// Order is also the foreign-key-safe insertion order. No constraints are disabled.
	private static final Map<String, List<String>> CURRENT = columns(
			"PROJECT_TYPE:ID",
			"PROJECT:NAME,REPOSITORY_URL,EXTERNAL_ID,PROJECT_URL,TASKS_URL,TYPE",
			"TASK:TASK_ID,REPOSITORY_URL,TICK,TASK_URL,TASK_SUMMARY,TASK_PROJECT,CURRENTACTIVITY_ID",
			"ACTIVITY:ID,START_TIME,END_TIME,ADJUSTED,SUMMARY,TASK_ID,REPOSITORY_URL,ACTIVITY_PROJECT",
			"TASK_ACTIVITY:TASK_ID,REPOSITORY_URL,ACTIVITIES_ID",
			"PROJECT_TASK:PROJECT_NAME,TASK_ID,REPOSITORY_URL",
			"PROJECT_ACTIVITY:PROJECT_NAME,CHILDREN_ID",
			"ACTIVITYLABEL:ID,NAME,COLOR",
			"ACTIVITY_ACTIVITYLABEL:ACTIVITY_ID,LABELS_ID");

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
		Map<String, Set<String>> sourceSchema = schema(source);
		boolean v2 = sourceSchema.containsKey("PROJECT");
		Map<String, List<String>> legacy = columns(
				"ACTIVITY:ID,END_TIME,ADJUSTED,START_TIME,SUMMARY,TASK_ID,REPOSITORY_URL" + (v2 ? ",PROJECT" : ""),
				"TRACKEDTASK:TASK_ID,REPOSITORY_URL,TICK,CURRENTACTIVITY_ID" + (v2 ? ",TASK_URL,TASK_SUMMARY,PROJECT" : ""),
				"TRACKEDTASK_ACTIVITY:TASK_ID,REPOSITORY_URL,ACTIVITIES_ID");
		if (v2) {
			legacy.put("PROJECT", List.of("NAME", "REPOSITORY_URL", "EXTERNAL_ID"));
		}
		requireSchema(sourceSchema, legacy, "source");
		requireSchema(schema(target), CURRENT, "target");
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
			for (String table : CURRENT.keySet()) {
				List<List<String>> actual = rows(target, "SELECT " + String.join(",", CURRENT.get(table)) + " FROM " + table);
				if (!sorted(expected.get(table)).equals(sorted(actual))) {
					throw new SQLException("Conversion verification failed for " + table);
				}
			}
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

	private static Map<String, List<String>> columns(String... tables) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (String table : tables) {
			String[] parts = table.split(":", 2);
			result.put(parts[0], Arrays.asList(parts[1].split(",")));
		}
		return result;
	}

	private static Map<String, Set<String>> schema(Connection connection) throws SQLException {
		Map<String, Set<String>> result = new TreeMap<>();
		try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[] { "TABLE", "VIEW" })) {
			while (tables.next()) {
				String namespace = tables.getString("TABLE_SCHEM");
				if ("INFORMATION_SCHEMA".equals(namespace)) {
					continue;
				}
				if (!"PUBLIC".equals(namespace) || !"TABLE".equals(tables.getString("TABLE_TYPE"))) {
					throw new SQLException("Unsupported schema or view in conversion database");
				}
				String table = tables.getString("TABLE_NAME");
				Set<String> names = new TreeSet<>();
				try (ResultSet fields = connection.getMetaData().getColumns(null, "PUBLIC", table, "%")) {
					while (fields.next()) {
						names.add(fields.getString("COLUMN_NAME"));
					}
				}
				result.put(table, names);
			}
		}
		return result;
	}

	private static void requireSchema(Map<String, Set<String>> actual, Map<String, List<String>> expected, String role)
			throws SQLException {
		Map<String, Set<String>> names = new TreeMap<>();
		expected.forEach((table, fields) -> names.put(table, new TreeSet<>(fields)));
		if (!actual.equals(names)) {
			throw new SQLException("Unsupported " + role + " schema; expected " + names + " but found " + actual);
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
