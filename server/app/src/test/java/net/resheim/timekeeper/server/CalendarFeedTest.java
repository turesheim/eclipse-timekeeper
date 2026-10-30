package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarFeedTest {
    private static String event(String fields) {return "BEGIN:VEVENT\r\nDTSTAMP:20260101T000000Z\r\n"+fields.replace("\n","\r\n")+"\r\nEND:VEVENT\r\n";}
    private static CalendarFeed feed(String... events) {return new CalendarFeed("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Timekeeper//Calendar test//EN\r\n"+String.join("",events)+"END:VCALENDAR\r\n");}
    private static List<CalendarFeed.Occurrence> week(CalendarFeed feed,String from,String zone) {var start=LocalDate.parse(from);return feed.occurrences(start,start.plusDays(7),ZoneId.of(zone));}
    @Test void recurrenceRetainsWallClockAcrossDstAndRespectsExdatesAndRdates() {
        var source=feed(event("UID:weekly\nDTSTART;TZID=Europe/Oslo:20260322T090000\nDTEND;TZID=Europe/Oslo:20260322T100000\nRRULE:FREQ=WEEKLY;COUNT=4\nEXDATE;TZID=Europe/Oslo:20260405T090000\nRDATE;TZID=Europe/Oslo:20260406T090000\nSUMMARY:Planning"));
        assertEquals("2026-03-22T08:00:00Z",week(source,"2026-03-22","Europe/Oslo").getFirst().start().toString());
        assertEquals("2026-03-29T07:00:00Z",week(source,"2026-03-29","Europe/Oslo").getFirst().start().toString());
        var april=week(source,"2026-04-05","Europe/Oslo");assertEquals(1,april.size());assertEquals("2026-04-06T07:00:00Z",april.getFirst().start().toString());
    }
    @Test void movedCancelledAndRevisedOccurrencesKeepTheirOriginalIdentity() {
        var master=event("UID:meeting\nDTSTART:20260504T090000Z\nDTEND:20260504T100000Z\nRRULE:FREQ=DAILY;COUNT=5\nSUMMARY:Original");
        var moved=event("UID:meeting\nRECURRENCE-ID:20260505T090000Z\nDTSTART:20260505T120000Z\nDTEND:20260505T130000Z\nSEQUENCE:2\nSUMMARY:Moved");
        var cancelled=event("UID:meeting\nRECURRENCE-ID:20260506T090000Z\nSTATUS:CANCELLED");
        var old=event("UID:meeting\nRECURRENCE-ID:20260505T090000Z\nDTSTART:20260505T110000Z\nDTEND:20260505T120000Z\nSEQUENCE:1\nSUMMARY:Old revision");
        var original=week(feed(master),"2026-05-04","UTC");var changed=week(feed(master,moved,cancelled,old),"2026-05-04","UTC");
        assertEquals(4,changed.size());assertEquals("Moved",changed.get(1).title());assertEquals("2026-05-05T12:00:00Z",changed.get(1).start().toString());assertEquals(original.get(1).key(),changed.get(1).key());
        assertEquals(0,week(feed(master,event("UID:meeting\nSTATUS:CANCELLED\nSEQUENCE:3")),"2026-05-04","UTC").size());
    }
    @Test void allDayExclusiveEndsAndFloatingTimesFollowTheAccountZone() {
        var source=feed(event("UID:days\nDTSTART;VALUE=DATE:20260504\nDTEND;VALUE=DATE:20260506\nSUMMARY:Conference"),event("UID:floating\nDTSTART:20260504T090000\nDURATION:PT1H\nSUMMARY:Local appointment"));
        var west=week(source,"2026-05-04","America/Los_Angeles");assertEquals(2,west.size());assertTrue(west.getFirst().allDay());assertEquals("2026-05-06",west.getFirst().endDate());assertEquals("2026-05-04T16:00:00Z",west.get(1).start().toString());
        var east=week(source,"2026-05-04","Pacific/Kiritimati");assertEquals("2026-05-03T19:00:00Z",east.get(1).start().toString());assertEquals(west.get(1).key(),east.get(1).key());
    }
    @Test void spanningMidnightAndMovedOccurrencesAreIncludedByTheirActualInterval() {
        var source=feed(event("UID:night\nDTSTART:20260503T233000Z\nDTEND:20260504T013000Z\nSUMMARY:Night"),event("UID:other\nDTSTART:20260101T090000Z\nRRULE:FREQ=DAILY;COUNT=2\nDURATION:PT1H"),event("UID:other\nRECURRENCE-ID:20260102T090000Z\nDTSTART:20260504T090000Z\nDTEND:20260504T100000Z\nSUMMARY:Moved into this week"));
        var values=week(source,"2026-05-04","UTC");assertEquals(2,values.size());assertEquals("2026-05-03T23:30:00Z",values.getFirst().start().toString());assertEquals("Moved into this week",values.get(1).title());
    }
    @Test void thisAndFutureMovesUseOriginalOccurrenceKeysAndUpdatedDetails() {
        String master=event("UID:range\nDTSTART;TZID=Europe/Oslo:20260322T090000\nDURATION:PT1H\nRRULE:FREQ=WEEKLY;COUNT=4\nSUMMARY:Original");
        String override=event("UID:range\nRECURRENCE-ID;TZID=Europe/Oslo;RANGE=THISANDFUTURE:20260329T090000\nDTSTART;TZID=Europe/Oslo:20260329T100000\nDURATION:PT2H\nSUMMARY:Later");
        var original=week(feed(master),"2026-04-05","Europe/Oslo").getFirst();var changed=week(feed(master,override),"2026-04-05","Europe/Oslo").getFirst();
        assertEquals(original.key(),changed.key());assertEquals("2026-04-05T08:00:00Z",changed.start().toString());assertEquals(java.time.Duration.ofHours(2),java.time.Duration.between(changed.start(),changed.end()));assertEquals("Later",changed.title());
    }
    @Test void foldedEscapedTextRemainsPlainTextAndStandaloneIdentitySurvivesRescheduling() {
        var one=feed(event("UID:standalone\nDTSTART:20260504T090000Z\nDURATION:PT1H\nSUMMARY:Meeting\\, a long\n  title\nDESCRIPTION:<script>alert(1)</script>\\nReview\\; notes\nATTACH:https://unreachable.example.invalid/secret"));
        var first=week(one,"2026-05-04","UTC").getFirst();assertEquals("Meeting, a long title",first.title());assertTrue(first.description().contains("<script>"));assertTrue(first.description().contains("\nReview; notes"));
        var moved=week(feed(event("UID:standalone\nDTSTART:20260505T090000Z\nDURATION:PT1H")),"2026-05-04","UTC").getFirst();assertEquals(first.key(),moved.key());assertNotEquals(first.json().get("eventVersion"),moved.json().get("eventVersion"));
    }
    @Test void denseRulesMalformedFeedsAndUnboundedQueriesFailExplicitly() {
        assertEquals("CALENDAR_TOO_COMPLEX",assertThrows(CalendarFailure.class,()->week(feed(event("UID:dense\nDTSTART:20260504T000000Z\nRRULE:FREQ=SECONDLY\nDURATION:PT1S")),"2026-05-04","UTC")).code);
        assertThrows(CalendarFailure.class,()->feed(event("SUMMARY:Missing identity\nDTSTART:20260504T000000Z")));
        assertThrows(CalendarFailure.class,()->new CalendarFeed("<html>Not a calendar</html>"));
        assertThrows(CalendarFailure.class,()->oneDayFeed().occurrences(LocalDate.parse("2026-01-01"),LocalDate.parse("2027-01-01"),ZoneOffset.UTC));
    }
    @Test void embeddedTimezoneDefinitionsWorkOfflineAndUtcExdatesMatchZonedRecurrences()throws Exception {
        try(var fixture=new CalendarDownloadTest.Fixture()){
            var requests=new java.util.concurrent.atomic.AtomicInteger();fixture.on("/timezone",exchange->{requests.incrementAndGet();CalendarDownloadTest.reply(exchange,500,"Must never download TZURL");});
            String timezone="BEGIN:VTIMEZONE\r\nTZID:Custom/TimekeeperTest\r\nTZURL:"+fixture.origin+"/timezone\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0230\r\nTZOFFSETTO:+0230\r\nTZNAME:Custom office\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";
            String local=event("UID:custom-zone\nDTSTART;TZID=Custom/TimekeeperTest:20260504T090000\nDURATION:PT1H\nSUMMARY:Office");
            for(int attempt=0;attempt<2;attempt++)assertEquals("2026-05-04T06:30:00Z",week(feed(timezone,local),"2026-05-04","UTC").getFirst().start().toString());
            assertEquals(0,requests.get());
            var excluded=feed(event("UID:utc-exclusion\nDTSTART;TZID=Europe/Oslo:20260504T090000\nDURATION:PT1H\nRRULE:FREQ=DAILY;COUNT=2\nEXDATE:20260505T070000Z"));
            assertEquals(1,week(excluded,"2026-05-04","Europe/Oslo").size());
        }
    }
    private static String todo(String fields){return event(fields).replace("VEVENT","VTODO");}
    @Test void calendarTodosIncludeDueOnlyScheduledAndCompletedItemsWithoutEventIdentityCollisions(){
        var source=feed(event("UID:shared\nDTSTART;VALUE=DATE:20260504\nSUMMARY:Event"),
                todo("UID:shared\nDUE;VALUE=DATE:20260504\nSUMMARY:Due today"),
                todo("UID:scheduled\nDTSTART;TZID=Europe/Oslo:20260504T090000\nSUMMARY:Scheduled task"),
                todo("UID:finished\nDUE:20260504T120000Z\nSTATUS:COMPLETED\nCOMPLETED:20260503T100000Z\nSUMMARY:Finished"));
        var values=source.occurrences(LocalDate.parse("2026-05-04"),LocalDate.parse("2026-05-05"),ZoneId.of("Europe/Oslo"));
        assertEquals(4,values.size());assertEquals(4,values.stream().map(CalendarFeed.Occurrence::key).distinct().count());
        var due=values.stream().filter(value->value.title().equals("Due today")).findFirst().orElseThrow();
        assertTrue(due.todo());assertTrue(due.allDay());assertEquals("2026-05-04",due.firstDate());assertEquals("2026-05-05",due.endDate());
        var scheduled=values.stream().filter(value->value.title().equals("Scheduled task")).findFirst().orElseThrow();
        assertEquals("2026-05-04T07:00:00Z",scheduled.start().toString());assertEquals(scheduled.start(),scheduled.end());
        assertEquals("COMPLETED",values.stream().filter(value->value.title().equals("Finished")).findFirst().orElseThrow().todoStatus());
    }
    @Test void recurringTodosUseDueMarkersWithExclusionsOverridesAndDst(){
        var master=todo("UID:recurring-task\nDTSTART;TZID=Europe/Oslo:20260322T090000\nDUE;TZID=Europe/Oslo:20260322T100000\nRRULE:FREQ=WEEKLY;COUNT=4\nEXDATE;TZID=Europe/Oslo:20260405T090000\nSUMMARY:Weekly task");
        var override=todo("UID:recurring-task\nRECURRENCE-ID;TZID=Europe/Oslo:20260329T090000\nDTSTART;TZID=Europe/Oslo:20260329T110000\nDUE;TZID=Europe/Oslo:20260329T120000\nSUMMARY:Moved task");
        assertEquals("2026-03-22T09:00:00Z",week(feed(master),"2026-03-22","Europe/Oslo").getFirst().start().toString());
        assertEquals("2026-03-29T08:00:00Z",week(feed(master),"2026-03-29","Europe/Oslo").getFirst().start().toString());
        var moved=week(feed(master,override),"2026-03-29","Europe/Oslo").getFirst();assertEquals("Moved task",moved.title());assertEquals("2026-03-29T10:00:00Z",moved.start().toString());
        assertEquals(week(feed(master),"2026-03-29","Europe/Oslo").getFirst().key(),moved.key());
        assertTrue(week(feed(master),"2026-04-05","Europe/Oslo").isEmpty());
    }
    @Test void dueMarkersRespectMidnightAndDoNotClaimTheWholeStartToDueInterval(){
        var source=feed(todo("UID:deadline\nDTSTART:20260501T090000Z\nDUE:20260504T000000Z\nSUMMARY:Deadline"));
        assertEquals(1,source.occurrences(LocalDate.parse("2026-05-04"),LocalDate.parse("2026-05-05"),ZoneOffset.UTC).size());
        assertTrue(source.occurrences(LocalDate.parse("2026-05-03"),LocalDate.parse("2026-05-04"),ZoneOffset.UTC).isEmpty());
        assertTrue(source.occurrences(LocalDate.parse("2026-05-01"),LocalDate.parse("2026-05-02"),ZoneOffset.UTC).isEmpty());
    }
    @Test void undatedOpenTodosAppearInEachWindowWithoutInventingDueDatesAndHaveStableVersions(){
        var source=feed(todo("UID:undated\nSUMMARY:Someday"),todo("UID:done\nSTATUS:COMPLETED"),todo("UID:cancelled\nSTATUS:CANCELLED"));
        var day=source.occurrences(LocalDate.parse("2026-05-04"),LocalDate.parse("2026-05-05"),ZoneOffset.UTC);
        var week=week(source,"2026-05-04","UTC");assertEquals(1,day.size());assertEquals(1,week.size());assertTrue(day.getFirst().undated());
        assertEquals(day.getFirst().key(),week.getFirst().key());assertEquals(day.getFirst().json().get("eventVersion"),week.getFirst().json().get("eventVersion"));
    }
    @Test void malformedTodoIntervalsFailExplicitly(){
        assertThrows(CalendarFailure.class,()->feed(todo("UID:bad\nDTSTART:20260504T090000Z\nDUE:20260504T080000Z")));
        assertThrows(CalendarFailure.class,()->feed(todo("UID:bad\nDUE:20260504T090000Z\nDURATION:PT1H")));
        assertThrows(CalendarFailure.class,()->feed(todo("UID:bad\nDTSTART;VALUE=DATE:20260504\nDUE:20260504T090000Z")));
    }
    private static CalendarFeed oneDayFeed(){return feed(event("UID:one\nDTSTART;VALUE=DATE:20260504"));}
    @Test void timedEventsWithoutAnEndRemainZeroDurationOccurrences(){
        var value=week(feed(event("UID:milestone\nDTSTART:20260504T120000Z\nSUMMARY:Milestone")),"2026-05-04","UTC");
        assertEquals(1,value.size());assertEquals(value.getFirst().start(),value.getFirst().end());assertFalse(value.getFirst().allDay());
    }
}
