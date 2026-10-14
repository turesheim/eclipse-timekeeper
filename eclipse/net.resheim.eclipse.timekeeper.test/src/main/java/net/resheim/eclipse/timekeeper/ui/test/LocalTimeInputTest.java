package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;
import java.time.*;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.ui.views.LocalTimeInput;

public class LocalTimeInputTest {
    private final ZoneId oslo=ZoneId.of("Europe/Oslo");
    @Test public void localEditorKeepsTheUsersDateAndExactNanoseconds() {
        var instant=Instant.parse("2026-07-01T22:30:00.123456789Z");
        assertEquals("2026-07-02T00:30:00.123456789",LocalTimeInput.format(instant,oslo));
        assertEquals(instant,LocalTimeInput.parse(LocalTimeInput.format(instant,oslo),oslo));
        assertEquals(Instant.parse("2026-01-01T23:30:00Z"),LocalTimeInput.parse("2026-01-02 00:30",oslo));
        assertEquals(instant,LocalTimeInput.parse(instant.toString(),oslo));
    }
    @Test public void skippedAndRepeatedTimesAreNeverSilentlyMoved() {
        assertThrows(IllegalArgumentException.class,()->LocalTimeInput.parse("2026-03-29T02:30:00",oslo));
        assertThrows(IllegalArgumentException.class,()->LocalTimeInput.parse("2026-10-25T02:30:00",oslo));
        for(var instant:new Instant[]{Instant.parse("2026-10-25T00:30:00.123456789Z"),Instant.parse("2026-10-25T01:30:00.123456789Z")}) {
            var text=LocalTimeInput.format(instant,oslo);
            assertTrue(text.startsWith("2026-10-25T02:30:00.123456789+0"));
            assertEquals(instant,LocalTimeInput.parse(text,oslo));
        }
        var kathmandu=ZoneId.of("Asia/Kathmandu");
        assertEquals(Instant.parse("2026-01-01T23:30:00Z"),LocalTimeInput.parse("2026-01-02T05:15:00",kathmandu));
    }
}
