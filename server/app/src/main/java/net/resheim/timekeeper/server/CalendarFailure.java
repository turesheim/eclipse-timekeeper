package net.resheim.timekeeper.server;

/** A stable calendar error code, never a remote URL, response body or exception message. */
final class CalendarFailure extends RuntimeException {
    final String code;
    CalendarFailure(String code) {super(code);this.code=code;}
    static CalendarFailure invalid() {return new CalendarFailure("CALENDAR_INVALID_FEED");}
    static CalendarFailure complex() {return new CalendarFailure("CALENDAR_TOO_COMPLEX");}
}
