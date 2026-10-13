package net.resheim.eclipse.timekeeper.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Presentation only: recorded durations retain their original precision. */
public final class DurationDisplay {
    private DurationDisplay() { }
    public static String format(Duration duration,boolean decimalHours) {
        Objects.requireNonNull(duration,"duration");
        if(duration.isNegative()) throw new IllegalArgumentException("Duration must not be negative");
        if(decimalHours) return BigDecimal.valueOf(duration.getSeconds())
            .add(BigDecimal.valueOf(duration.getNano(),9)).divide(BigDecimal.valueOf(3600),2,RoundingMode.HALF_UP).toPlainString();
        long seconds=duration.getSeconds();
        return String.format(Locale.ROOT,"%d:%02d",seconds/3600,(seconds/60)%60);
    }
}
