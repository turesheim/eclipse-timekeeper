import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;

import org.h2.tools.RunScript;

/** Standalone baseline fixture; does not load or start the Eclipse plugin. */
public class LegacyFixture {
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--restore"))) {
            throw new IllegalArgumentException("Usage: LegacyFixture.java [--restore]");
        }
        Path root = Path.of("").toAbsolutePath();
        Path output = Files.createTempDirectory("timekeeper-legacy-fixture-");
        System.out.println("Fixture directory: " + output);
        Path database = output.resolve("original");
        try (Connection connection = open(database)) {
            for (String script : new String[] {
                    "net.resheim.eclipse.timekeeper.db/db/V1__baseline.sql",
                    "net.resheim.eclipse.timekeeper.db/db/V2__add_project_taskurl_and_tasksummary.sql",
                    "baseline/legacy-data.sql" }) {
                try (var reader = Files.newBufferedReader(root.resolve(script), StandardCharsets.UTF_8)) {
                    RunScript.execute(connection, reader);
                }
            }
            verify(connection);
            verifyLabels(connection, root.resolve("baseline/labels.csv"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("SCRIPT TO '" + output.resolve("export.sql").toString().replace("'", "''") + "'");
            }
        }
        // Copy only after closing the database; never touch an existing user's database.
        Files.copy(output.resolve("original.mv.db"), output.resolve("copy.mv.db"));
        try (Connection connection = open(output.resolve("copy"))) {
            verify(connection);
        }
        System.out.println("PASS: original and closed-file copy have expected data; label references validated.");
        System.out.println("Labels in baseline/labels.csv are a separate specification, not loaded into this legacy schema.");
        if (args.length == 1) {
            // Diagnostic only: baseline H2 1.4.194 currently fails on a duplicate index here.
            try (Connection connection = open(output.resolve("restored"));
                    var reader = Files.newBufferedReader(output.resolve("export.sql"), StandardCharsets.UTF_8)) {
                RunScript.execute(connection, reader);
                verify(connection);
            }
            System.out.println("PASS: SQL restore has expected data.");
        }
    }

    private static Connection open(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:h2:" + database, "sa", "");
    }

    private static void expect(Connection connection, String sql, long expected) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || result.getLong(1) != expected) {
                throw new AssertionError("Expected " + expected + " for " + sql);
            }
        }
    }

    private static void verifyLabels(Connection connection, Path specification) throws Exception {
        var lines = Files.readAllLines(specification, StandardCharsets.UTF_8);
        if (lines.size() != 4 || !lines.get(0).equals("activity_id,label_name,label_color")) {
            throw new AssertionError("Expected a header and three label assignments");
        }
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> colors = Map.of("Billable", "\"0,128,0\"", "Internal", "\"128,128,128\"");
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM ACTIVITY WHERE ID = ?")) {
            for (String line : lines.subList(1, lines.size())) {
                String[] fields = line.split(",", 3);
                if (fields.length != 3 || !fields[2].equals(colors.get(fields[1]))) {
                    throw new AssertionError("Unexpected label specification: " + line);
                }
                query.setString(1, fields[0]);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new AssertionError("Label references missing activity: " + line);
                    }
                }
                counts.merge(fields[1], 1, Integer::sum);
            }
        }
        if (!counts.equals(Map.of("Billable", 2, "Internal", 1))) {
            throw new AssertionError("Unexpected label counts: " + counts);
        }
    }

    private static void verify(Connection connection) throws Exception {
        expect(connection, "SELECT COUNT(*) FROM PROJECT", 2);
        expect(connection, "SELECT COUNT(*) FROM TRACKEDTASK", 3);
        expect(connection, "SELECT COUNT(*) FROM ACTIVITY", 5);
        expect(connection, "SELECT COUNT(*) FROM TRACKEDTASK_ACTIVITY", 5);
        expect(connection, "SELECT COUNT(*) FROM ACTIVITY WHERE ADJUSTED = TRUE", 1);
        expect(connection, "SELECT COUNT(*) FROM TRACKEDTASK WHERE TASK_ID = '1'", 2);
        expect(connection, "SELECT COUNT(*) FROM TRACKEDTASK t WHERE NOT EXISTS "
                + "(SELECT 1 FROM ACTIVITY a WHERE a.TASK_ID=t.TASK_ID AND a.REPOSITORY_URL=t.REPOSITORY_URL)", 1);
        expect(connection, "SELECT COUNT(*) FROM ACTIVITY a JOIN TRACKEDTASK_ACTIVITY r ON "
                + "a.ID=r.ACTIVITIES_ID AND a.TASK_ID=r.TASK_ID AND a.REPOSITORY_URL=r.REPOSITORY_URL", 5);
        expect(connection, "SELECT COUNT(*) FROM TRACKEDTASK t JOIN PROJECT p ON t.PROJECT=p.NAME", 3);
        expect(connection, "SELECT COUNT(*) FROM ACTIVITY WHERE SUMMARY = 'Manually adjusted – Unicode: æøå'", 1);
        Map<LocalDate, Long> daily = new TreeMap<>();
        Map<String, Long> tasks = new TreeMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT * FROM ACTIVITY")) {
            while (result.next()) {
                LocalDateTime start = result.getTimestamp("START_TIME").toLocalDateTime();
                LocalDateTime end = result.getTimestamp("END_TIME").toLocalDateTime();
                tasks.merge(result.getString("REPOSITORY_URL") + "/" + result.getString("TASK_ID"),
                        Duration.between(start, end).getSeconds(), Long::sum);
                while (start.isBefore(end)) {
                    LocalDateTime boundary = start.toLocalDate().plusDays(1).atStartOfDay();
                    LocalDateTime stop = end.isBefore(boundary) ? end : boundary;
                    daily.merge(start.toLocalDate(), Duration.between(start, stop).getSeconds(), Long::sum);
                    start = stop;
                }
            }
        }
        if (!daily.equals(Map.of(LocalDate.of(2022, 9, 18), 1800L,
                LocalDate.of(2022, 9, 19), 13500L, LocalDate.of(2022, 9, 20), 4500L))) {
            throw new AssertionError("Unexpected daily totals: " + daily);
        }
        if (!tasks.equals(Map.of("https://example.invalid/a/1", 9900L,
                "https://example.invalid/b/1", 9900L))) {
            throw new AssertionError("Unexpected task totals: " + tasks);
        }
    }
}
