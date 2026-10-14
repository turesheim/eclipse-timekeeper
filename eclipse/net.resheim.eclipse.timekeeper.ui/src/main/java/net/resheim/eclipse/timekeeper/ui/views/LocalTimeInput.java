package net.resheim.eclipse.timekeeper.ui.views;

import java.time.*;
import java.time.format.DateTimeFormatter;

/** Exact local editor values; repeated clock times retain their explicit offset. */
public final class LocalTimeInput {
    private LocalTimeInput() { }

    public static String format(Instant instant, ZoneId zone) {
        var zoned = instant.atZone(zone);
        var local = zoned.toLocalDateTime();
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(local)
                + (zone.getRules().getValidOffsets(local).size() > 1 ? zoned.getOffset().getId() : "");
    }

    public static Instant parse(String text, ZoneId zone) {
        String value = text.trim().replace(' ', 'T');
        if (value.matches(".*(?:Z|[+-]\\d{2}:\\d{2}(?::\\d{2})?)$"))
            return OffsetDateTime.parse(value).toInstant();
        var local = LocalDateTime.parse(value);
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) throw new IllegalArgumentException("This clock time does not exist in " + zone + " because the clocks move forward.");
        if (offsets.size() > 1) throw new IllegalArgumentException("This clock time occurs twice in " + zone + ". Include its UTC offset (" + offsets.getFirst() + " or " + offsets.getLast() + ").");
        return local.toInstant(offsets.getFirst());
    }
}
