package net.resheim.eclipse.timekeeper.db;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import net.resheim.eclipse.timekeeper.domain.Activity;

/** Check recorded starts before the CSV merge can write any table. */
final class ActivityCsvValidation {
    static void validate(Path path, Instant now) throws IOException {
        try (var rows = new org.h2.tools.Csv().read(path.toString(), null, "UTF-8")) {
            while (rows.next()) Activity.validateStart(Instant.parse(rows.getString("START_TIME")), now);
        } catch (SQLException | RuntimeException failure) {
            throw new IOException("Invalid activity start in import: " + failure.getMessage(), failure);
        }
    }
}
