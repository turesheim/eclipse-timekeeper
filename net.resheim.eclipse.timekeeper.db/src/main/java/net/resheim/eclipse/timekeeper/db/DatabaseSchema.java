package net.resheim.eclipse.timekeeper.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Shared table/column recognition; inspection never creates or changes schema. */
public final class DatabaseSchema {
	private DatabaseSchema() { }

	public enum Kind { EMPTY, CURRENT, LEGACY_V1, LEGACY_V2, UNKNOWN }

	// Order is also the foreign-key-safe insertion order. No constraints are disabled.
	static final Map<String, List<String>> CURRENT = columns(
			"PROJECT_TYPE:ID",
			"PROJECT:NAME,REPOSITORY_URL,EXTERNAL_ID,PROJECT_URL,TASKS_URL,TYPE",
			"TASK:TASK_ID,REPOSITORY_URL,TICK,TASK_URL,TASK_SUMMARY,TASK_PROJECT,CURRENTACTIVITY_ID",
			"ACTIVITY:ID,START_TIME,END_TIME,ADJUSTED,SUMMARY,TASK_ID,REPOSITORY_URL,ACTIVITY_PROJECT",
			"TASK_ACTIVITY:TASK_ID,REPOSITORY_URL,ACTIVITIES_ID",
			"PROJECT_TASK:PROJECT_NAME,TASK_ID,REPOSITORY_URL",
			"PROJECT_ACTIVITY:PROJECT_NAME,CHILDREN_ID",
			"ACTIVITYLABEL:ID,NAME,COLOR",
			"ACTIVITY_ACTIVITYLABEL:ACTIVITY_ID,LABELS_ID");


	public static Kind inspect(Connection connection) throws SQLException {
		Map<String, Set<String>> actual = schema(connection);
		if (actual.isEmpty()) return Kind.EMPTY;
		if (matches(actual, CURRENT)) return Kind.CURRENT;
		if (matches(actual, legacy(false))) return Kind.LEGACY_V1;
		if (matches(actual, legacy(true))) return Kind.LEGACY_V2;
		return Kind.UNKNOWN;
	}

	private static Map<String, List<String>> legacy(boolean v2) {
		Map<String, List<String>> result = columns(
				"ACTIVITY:ID,END_TIME,ADJUSTED,START_TIME,SUMMARY,TASK_ID,REPOSITORY_URL" + (v2 ? ",PROJECT" : ""),
				"TRACKEDTASK:TASK_ID,REPOSITORY_URL,TICK,CURRENTACTIVITY_ID" + (v2 ? ",TASK_URL,TASK_SUMMARY,PROJECT" : ""),
				"TRACKEDTASK_ACTIVITY:TASK_ID,REPOSITORY_URL,ACTIVITIES_ID");
		if (v2) result.put("PROJECT", List.of("NAME", "REPOSITORY_URL", "EXTERNAL_ID"));
		return result;
	}

	private static boolean matches(Map<String, Set<String>> actual, Map<String, List<String>> expected) {
		Map<String, Set<String>> names = new TreeMap<>();
		expected.forEach((table, fields) -> names.put(table, new TreeSet<>(fields)));
		return actual.equals(names);
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
					throw new SQLException("Unsupported schema or view in Timekeeper database; no schema changes were made");
				}
				String table = tables.getString("TABLE_NAME");
				Set<String> names = new TreeSet<>();
				try (ResultSet fields = connection.getMetaData().getColumns(null, "PUBLIC", table, "%")) {
					while (fields.next()) {
						// JDBC treats underscores in the table argument as wildcards.
						if (table.equals(fields.getString("TABLE_NAME"))) {
							names.add(fields.getString("COLUMN_NAME"));
						}
					}
				}
				result.put(table, names);
			}
		}
		return result;
	}
}
