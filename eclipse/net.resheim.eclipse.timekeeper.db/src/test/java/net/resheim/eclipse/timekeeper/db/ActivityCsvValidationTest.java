package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityCsvValidationTest {
    @TempDir Path directory;
    @Test void csvStartsUseTheSameExactClockBoundaryBeforeAnyMerge() throws Exception {
        Instant now=Instant.parse("2026-10-13T10:00:00.123456789Z");
        Path csv=directory.resolve("activity.csv");
        Files.writeString(csv,"\"START_TIME\",\"SUMMARY\"\n\""+now+"\",\"quoted, summary\"\n");
        ActivityCsvValidation.validate(csv,now);
        Files.writeString(csv,"\"START_TIME\",\"SUMMARY\"\n\""+now.plusNanos(1)+"\",\"future\"\n");
        assertTrue(assertThrows(IOException.class,()->ActivityCsvValidation.validate(csv,now)).getMessage().contains("future"));
    }
    @Test void malformedStartsAndMissingStartColumnAreRefused() throws Exception {
        Path csv=directory.resolve("activity.csv");
        for(String value:new String[]{"START_TIME\ninvalid\n","SUMMARY\nmissing start\n"}) {
            Files.writeString(csv,value);
            assertThrows(IOException.class,()->ActivityCsvValidation.validate(csv,Instant.now()));
        }
    }
}
