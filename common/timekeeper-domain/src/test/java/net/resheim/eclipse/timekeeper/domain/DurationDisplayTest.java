package net.resheim.eclipse.timekeeper.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationDisplayTest {
    @Test void clockAndDecimalHoursDoNotChangeRecordedPrecision() {
        var duration=Duration.ofMinutes(90).plusNanos(1);
        assertEquals("1:30",DurationDisplay.format(duration,false));
        assertEquals("1.50",DurationDisplay.format(duration,true));
        assertEquals(1,duration.getNano());
        assertEquals("25:00",DurationDisplay.format(Duration.ofHours(25),false));
        assertEquals("25.00",DurationDisplay.format(Duration.ofHours(25),true));
    }
    @Test void decimalRoundingUsesTheExactAggregateAndClockFormattingCannotOverflow() {
        assertEquals("0.00",DurationDisplay.format(Duration.ofSeconds(17,999999999),true));
        assertEquals("0.01",DurationDisplay.format(Duration.ofSeconds(18),true));
        assertEquals("0.01",DurationDisplay.format(Duration.ofSeconds(9).plusSeconds(9),true));
        assertEquals("2562047788015215:30",DurationDisplay.format(Duration.ofSeconds(Long.MAX_VALUE),false));
        assertThrows(IllegalArgumentException.class,()->DurationDisplay.format(Duration.ofSeconds(-1),true));
    }
}
