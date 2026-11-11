package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class InternetCalendarCodecTest {
    @Test void googleAllDayEventsAppearOnTheirCivilDay(){
        var value=parse("{\"id\":\"meeting\",\"summary\":\"Planning\",\"start\":{\"date\":\"2026-11-10\"},\"end\":{\"date\":\"2026-11-11\"}}");
        String body=InternetCalendarCodec.feed(TaskCloudProvider.Provider.GOOGLE,List.of(value));var rows=new CalendarFeed(body).occurrences(LocalDate.parse("2026-11-09"),LocalDate.parse("2026-11-16"),ZoneId.of("UTC"));assertEquals(1,rows.size(),body);
    }
    @Test void googleRecurrenceKeepsLocalTimeAcrossDstAndHonorsCancelledAndMovedInstances(){
        var master=parse("""
            {"id":"series","summary":"Weekly meeting","start":{"dateTime":"2026-03-22T09:00:00+01:00","timeZone":"Europe/Oslo"},"end":{"dateTime":"2026-03-22T10:00:00+01:00","timeZone":"Europe/Oslo"},"recurrence":["RRULE:FREQ=WEEKLY;COUNT=3"]}
            """);
        var cancelled=parse("""
            {"id":"cancelled","recurringEventId":"series","status":"cancelled","originalStartTime":{"dateTime":"2026-03-29T09:00:00+02:00","timeZone":"Europe/Oslo"}}
            """);
        var moved=parse("""
            {"id":"moved","recurringEventId":"series","summary":"Moved meeting","originalStartTime":{"dateTime":"2026-04-05T09:00:00+02:00","timeZone":"Europe/Oslo"},"start":{"dateTime":"2026-04-05T10:30:00+02:00","timeZone":"Europe/Oslo"},"end":{"dateTime":"2026-04-05T11:30:00+02:00","timeZone":"Europe/Oslo"}}
            """);
        String body=InternetCalendarCodec.feed(TaskCloudProvider.Provider.GOOGLE,List.of(master,cancelled,moved));var rows=new CalendarFeed(body).occurrences(LocalDate.parse("2026-03-20"),LocalDate.parse("2026-04-10"),ZoneId.of("UTC"));
        assertEquals(2,rows.size());assertEquals(Instant.parse("2026-03-22T08:00:00Z"),rows.get(0).start());assertEquals(Instant.parse("2026-04-05T08:30:00Z"),rows.get(1).start());assertEquals("Moved meeting",rows.get(1).title());
    }
    @Test void microsoftMastersUseWindowsZoneRulesAndRetainExceptionsWithoutAHistoryHorizon(){
        var master=parse("""
            {"id":"series","subject":"Weekly meeting","type":"seriesMaster","originalStartTimeZone":"W. Europe Standard Time","start":{"dateTime":"2026-03-22T08:00:00.0000000","timeZone":"UTC"},"end":{"dateTime":"2026-03-22T09:00:00.0000000","timeZone":"UTC"},
             "recurrence":{"pattern":{"type":"weekly","interval":1,"daysOfWeek":["sunday"],"firstDayOfWeek":"monday"},"range":{"type":"endDate","endDate":"2026-04-05","recurrenceTimeZone":"W. Europe Standard Time"}},
             "cancelledOccurrences":["OID.series.2026-03-29"],"exceptionOccurrences":[{"id":"moved","subject":"Moved meeting","occurrenceId":"OID.series.2026-04-05","start":{"dateTime":"2026-04-05T08:30:00","timeZone":"UTC"},"end":{"dateTime":"2026-04-05T09:30:00","timeZone":"UTC"}}]}
            """);
        String body=InternetCalendarCodec.feed(TaskCloudProvider.Provider.MICROSOFT,List.of(master));var rows=new CalendarFeed(body).occurrences(LocalDate.parse("2026-03-20"),LocalDate.parse("2026-04-10"),ZoneId.of("UTC"));
        assertEquals(2,rows.size());assertEquals(Instant.parse("2026-03-22T08:00:00Z"),rows.get(0).start());assertEquals(Instant.parse("2026-04-05T08:30:00Z"),rows.get(1).start());assertEquals("Moved meeting",rows.get(1).title());
    }
    @Test void microsoftAllDayEventsRetainTheirOriginalCivilDateAndTextCannotAddComponents(){
        var value=object().put("id","all-day").put("subject","Safe\nBEGIN:VEVENT\nUID:injected").put("type","singleInstance").put("isAllDay",true).put("originalStartTimeZone","W. Europe Standard Time");
        value.set("start",object().put("dateTime","2026-07-21T22:00:00").put("timeZone","UTC"));value.set("end",object().put("dateTime","2026-07-22T22:00:00").put("timeZone","UTC"));
        String body=InternetCalendarCodec.feed(TaskCloudProvider.Provider.MICROSOFT,List.of(value));var rows=new CalendarFeed(body).occurrences(LocalDate.parse("2026-07-01"),LocalDate.parse("2026-07-31"),ZoneId.of("America/New_York"));
        assertEquals(1,rows.size());assertTrue(rows.get(0).allDay());assertEquals("2026-07-22",rows.get(0).firstDate());assertEquals(value.path("subject").asText(),rows.get(0).title());
    }
    @Test void missingTimeAndUnknownZonesFailInsteadOfCreatingFabricatedEvents(){
        var task=object().put("id","not-a-calendar-event").put("summary","Undated task");assertThrows(ApiFailure.class,()->InternetCalendarCodec.feed(TaskCloudProvider.Provider.GOOGLE,List.of(task)));
        assertThrows(ApiFailure.class,()->InternetCalendarCodec.zone("Unknown provider time zone"));
    }
}
