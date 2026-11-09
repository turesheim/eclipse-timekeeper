package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarFeedTest {
    private static String event(String fields) {return "BEGIN:VEVENT\r\nDTSTAMP:20260101T000000Z\r\n"+fields.replace("\n","\r\n")+"\r\nEND:VEVENT\r\n";}
    private static CalendarFeed feed(String... events) {return new CalendarFeed("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Timekeeper//Calendar test//EN\r\n"+String.join("",events)+"END:VCALENDAR\r\n",true,true);}
    private static List<CalendarFeed.Occurrence> week(CalendarFeed feed,String from,String zone) {var start=LocalDate.parse(from);return feed.occurrences(start,start.plusDays(7),ZoneId.of(zone));}
    @Test void searchableEventDefinitionsKeepDetailsAndDetachedRevisionsWithoutExpandingRecurrence(){
        String master=event("UID:search-series\nDTSTART:20260504T090000\nDURATION:PT1H\nRRULE:FREQ=SECONDLY\nEXDATE:20260504T090000\nSUMMARY:Series\nDESCRIPTION:Spectrograph calibration\nLOCATION:North observatory");
        String moved=event("UID:search-series\nRECURRENCE-ID:20260504T100000\nDTSTART:20260505T120000\nSEQUENCE:2\nSUMMARY:Moved observation");
        String older=moved.replace("SEQUENCE:2","SEQUENCE:1").replace("Moved observation","Superseded");
        var source=feed(master,moved,older,event("UID:search-cancelled\nDTSTART:20260504T090000Z\nSTATUS:CANCELLED"),event("UID:search-single\nDTSTART;VALUE=DATE:20260504\nDTEND;VALUE=DATE:20260506\nSUMMARY:Conference"),todo("UID:search-task\nSUMMARY:Separate task"));
        var definitions=source.eventDefinitions(ZoneId.of("Europe/Oslo"));assertEquals(3,definitions.size());
        var series=definitions.stream().filter(value->value.path("title").asText().equals("Series")).findFirst().orElseThrow();
        assertTrue(series.path("seriesDefinition").asBoolean());assertTrue(series.path("eventKey").isNull());assertEquals("2026-05-04T07:00:00Z",series.path("start").asText());
        var override=definitions.stream().filter(value->value.path("title").asText().equals("Moved observation")).findFirst().orElseThrow();
        assertEquals("Spectrograph calibration",override.path("description").asText());assertEquals("North observatory",override.path("location").asText());assertEquals("2026-05-05T11:00:00Z",override.path("end").asText());
        assertFalse(override.path("seriesDefinition").asBoolean());assertTrue(override.path("eventKey").asText().matches("[a-f0-9]{64}"));
        var single=definitions.stream().filter(value->value.path("title").asText().equals("Conference")).findFirst().orElseThrow();
        assertTrue(single.path("allDay").asBoolean());assertEquals("2026-05-06",single.path("endDate").asText());assertEquals(week(feed(event("UID:search-single\nDTSTART;VALUE=DATE:20260504\nDTEND;VALUE=DATE:20260506")),"2026-05-04","Europe/Oslo").getFirst().key(),single.path("eventKey").asText());
        var otherZone=source.eventDefinitions(ZoneId.of("America/Los_Angeles"));assertEquals(definitions.stream().map(value->value.path("id").asText()).toList(),otherZone.stream().map(value->value.path("id").asText()).toList());
    }
    @Test void standaloneDetachedEventDefinitionsRetainTheirOccurrenceKey(){
        var source=feed(event("UID:search-detached\nRECURRENCE-ID;VALUE=DATE:20260508\nDTSTART;VALUE=DATE:20260509\nSUMMARY:Moved detached event\nDESCRIPTION:Detached details\nLOCATION:South observatory"));
        var definitions=source.eventDefinitions(ZoneOffset.UTC);assertEquals(1,definitions.size());var value=definitions.getFirst();
        assertTrue(value.path("recurring").asBoolean());assertFalse(value.path("seriesDefinition").asBoolean());
        assertEquals("Detached details",value.path("description").asText());assertEquals("South observatory",value.path("location").asText());
        assertEquals(week(source,"2026-05-09","UTC").getFirst().key(),value.path("eventKey").asText());
    }
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
    @Test void taskCatalogIncludesAllPublishedDatesStatusesAndUndatedTasksWithoutExpandingInfiniteSeries(){
        var source=feed(todo("UID:old\nDUE;VALUE=DATE:19990101\nSUMMARY:Old deadline\nCATEGORIES:Arbeid,Review\\, release"),
                todo("UID:future\nDUE:20990101T003000Z\nSUMMARY:Future deadline"),
                todo("UID:undated\nSUMMARY:No deadline"),todo("UID:done\nCOMPLETED:20260101T120000Z\nSUMMARY:Completed without STATUS"),
                todo("UID:cancelled\nSTATUS:CANCELLED\nSUMMARY:Cancelled"),
                todo("UID:repeat\nDTSTART;VALUE=DATE:20200101\nDUE;VALUE=DATE:20200102\nRRULE:FREQ=DAILY\nSUMMARY:Repeating task"),
                event("UID:old\nDTSTART;VALUE=DATE:20260101\nSUMMARY:Not a task"));
        var tasks=source.tasks(ZoneId.of("America/Los_Angeles"));assertEquals(6,tasks.size());
        var byName=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();tasks.forEach(value->byName.put(value.path("summary").asText(),value));
        assertEquals("1999-01-01",byName.get("Old deadline").path("due").path("date").asText());assertTrue(byName.get("Old deadline").path("start").isNull());
        assertEquals(Set.of("Arbeid","Review, release"),java.util.stream.StreamSupport.stream(byName.get("Old deadline").path("categories").spliterator(),false).map(value->value.asText()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("2099-01-01",byName.get("Future deadline").path("due").path("date").asText());assertEquals("00:30",byName.get("Future deadline").path("due").path("time").asText());assertEquals("UTC",byName.get("Future deadline").path("due").path("timeZone").asText());
        assertTrue(byName.get("No deadline").path("due").isNull());assertEquals("COMPLETED",byName.get("Completed without STATUS").path("status").asText());assertEquals("CANCELLED",byName.get("Cancelled").path("status").asText());assertTrue(byName.get("Repeating task").path("recurring").asBoolean());
    }
    @Test void taskCatalogKeepsStableDefinitionAndDetachedInstanceIdentitiesAndLatestRevisions(){
        String original=todo("UID:series\nDTSTART;VALUE=DATE:20260101\nDUE;VALUE=DATE:20260102\nRRULE:FREQ=WEEKLY\nSUMMARY:Series\nCATEGORIES:Team");
        String changed=todo("UID:series\nRECURRENCE-ID;VALUE=DATE:20260108\nDTSTART;VALUE=DATE:20260109\nSEQUENCE:2\nSUMMARY:Moved instance");
        String older=changed.replace("SEQUENCE:2","SEQUENCE:1").replace("Moved instance","Old instance");
        var tasks=feed(original,changed,older).tasks(ZoneOffset.UTC);assertEquals(2,tasks.size());
        var moved=tasks.stream().filter(value->value.path("summary").asText().equals("Moved instance")).findFirst().orElseThrow();
        assertEquals("2026-01-10",moved.path("due").path("date").asText());assertEquals("Team",moved.path("categories").get(0).asText());
        var renamed=feed(original,changed.replace("Moved instance","Renamed instance")).tasks(ZoneOffset.UTC);
        assertEquals(tasks.stream().map(value->value.path("id").asText()).toList(),renamed.stream().map(value->value.path("id").asText()).toList());
    }
    @Test void taskDeadlinesRetainAbsolutePrecisionAcrossRepeatedHoursAndKeepFloatingClocksUnresolved(){
        var tasks=feed(todo("UID:early\nDUE:20261025T003000Z\nSUMMARY:Early hour"),todo("UID:late\nDUE:20261025T013000Z\nSUMMARY:Late hour"),
                todo("UID:floating\nDUE:20261025T023000\nSUMMARY:Floating clock")).tasks(ZoneId.of("Europe/Oslo"));
        var moments=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();tasks.forEach(value->moments.put(value.path("summary").asText(),value.path("due")));
        assertEquals("00:30",moments.get("Early hour").path("time").asText());assertEquals("01:30",moments.get("Late hour").path("time").asText());
        assertEquals("UTC",moments.get("Early hour").path("timeZone").asText());assertEquals("UTC",moments.get("Late hour").path("timeZone").asText());
        assertEquals("02:30",moments.get("Floating clock").path("time").asText());assertEquals("Europe/Oslo",moments.get("Floating clock").path("timeZone").asText());
    }
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
    @Test void thisAndFutureTodoDueChangesExpandTheUpdatedInterval(){
        var master=todo("UID:future-due\nDTSTART:20260504T090000Z\nDUE:20260504T100000Z\nRRULE:FREQ=DAILY;COUNT=5\nSUMMARY:Task");
        var changed=todo("UID:future-due\nRECURRENCE-ID;RANGE=THISANDFUTURE:20260505T090000Z\nDTSTART:20260505T090000Z\nDUE:20260507T120000Z\nSUMMARY:Extended deadline");
        var values=feed(master,changed).occurrences(LocalDate.parse("2026-05-07"),LocalDate.parse("2026-05-08"),ZoneOffset.UTC);
        assertEquals(1,values.size());assertEquals("2026-05-07T12:00:00Z",values.getFirst().start().toString());assertEquals("Extended deadline",values.getFirst().title());
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
