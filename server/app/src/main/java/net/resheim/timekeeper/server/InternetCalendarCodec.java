package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Provider event definitions enter the existing bounded calendar engine, independently of tasks. */
final class InternetCalendarCodec {
    private static final DateTimeFormatter DATE=DateTimeFormatter.BASIC_ISO_DATE,LOCAL=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),UTC=LOCAL.withZone(ZoneOffset.UTC);
    private static final Map<String,String> WINDOWS=windows();
    private static Map<String,String> windows(){
        try(var stream=InternetCalendarCodec.class.getResourceAsStream("windows-zones.json")){var result=new HashMap<String,String>();MAPPER.readTree(stream).path("zones").fields().forEachRemaining(entry->result.put(entry.getKey(),entry.getValue().asText()));return Map.copyOf(result);}
        catch(Exception failure){throw new IllegalStateException("Calendar time-zone mappings are unavailable");}
    }
    static ZoneId zone(String id){try{return ZoneId.of(WINDOWS.getOrDefault(id,id));}catch(RuntimeException invalid){throw invalid();}}
    static String feed(TaskCloudProvider.Provider provider,Collection<? extends JsonNode> events){
        try{return build(provider,events);}catch(ApiFailure failure){throw failure;}catch(RuntimeException failure){throw invalid();}
    }
    private static String build(TaskCloudProvider.Provider provider,Collection<? extends JsonNode> events){
        var result=new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Dagsverk//Internet accounts//EN\r\n");
        var records=new LinkedHashMap<String,JsonNode>();for(var event:events){String id=required(event,"id",1024);if(records.put(id,event)!=null)throw invalid();}
        for(var event:records.values()){
            if(provider==TaskCloudProvider.Provider.GOOGLE)google(result,event,records);else microsoft(result,event);
            if(result.length()>CalendarFeed.MAX_BYTES)throw new ApiFailure(422,"TASK_PROVIDER_RESPONSE_TOO_LARGE");
        }
        result.append("END:VCALENDAR\r\n");String body=result.toString();new CalendarFeed(body,true,false);return body;
    }
    private static void google(StringBuilder result,JsonNode value,Map<String,JsonNode> records){
        boolean exception=value.hasNonNull("recurringEventId"),cancelled=value.path("status").asText().equals("cancelled");
        if(cancelled&&!exception)return;
        String id=required(value,"id",1024),master=exception?required(value,"recurringEventId",1024):id;
        if(exception&&(!records.containsKey(master)||records.get(master).path("status").asText().equals("cancelled")))return;
        result.append("BEGIN:VEVENT\r\n");text(result,"UID","google:"+hash(master));
        if(exception)googleDate(result,"RECURRENCE-ID",value.path("originalStartTime"));
        if(cancelled)result.append("STATUS:CANCELLED\r\n");
        else{
            googleDate(result,"DTSTART",value.path("start"));googleDate(result,"DTEND",value.path("end"));
            text(result,"SUMMARY",value.path("summary").asText(""));text(result,"DESCRIPTION",value.path("description").asText(""));text(result,"LOCATION",value.path("location").asText(""));
            if(value.has("recurrence")){
                if(!value.get("recurrence").isArray()||value.get("recurrence").size()>100)throw invalid();
                for(var rule:value.get("recurrence")){String line=rule.asText();if(!rule.isTextual()||line.length()>8192||line.indexOf('\r')>=0||line.indexOf('\n')>=0||!line.matches("(?:RRULE|EXRULE|RDATE|EXDATE)(?:;[^:\\r\\n]+)?:.+"))throw invalid();result.append(line).append("\r\n");}
            }
        }
        result.append("END:VEVENT\r\n");
    }
    private static void googleDate(StringBuilder result,String property,JsonNode value){
        try{
            if(value.hasNonNull("date")){result.append(property).append(";VALUE=DATE:").append(LocalDate.parse(value.get("date").asText()).format(DATE)).append("\r\n");return;}
            String raw=required(value,"dateTime",100);String tz=value.path("timeZone").asText("");
            if(!tz.isBlank()){ZoneId zone=zone(tz);var time=OffsetDateTime.parse(raw).toInstant().atZone(zone);local(result,property,time.toLocalDateTime(),zone);}
            else instant(result,property,OffsetDateTime.parse(raw).toInstant());
        }catch(RuntimeException failure){throw invalid();}
    }
    private static void microsoft(StringBuilder result,JsonNode value){
        if(value.path("isCancelled").asBoolean())return;
        String id=required(value,"id",1024);boolean recurring=value.path("type").asText().equals("seriesMaster"),allDay=value.path("isAllDay").asBoolean();
        if(!value.path("type").asText("singleInstance").equals("singleInstance")&&!recurring)throw invalid();
        var recurrence=value.path("recurrence");String tz=recurrence.path("range").path("recurrenceTimeZone").asText(value.path("originalStartTimeZone").asText(value.path("start").path("timeZone").asText("UTC")));ZoneId zone=zone(tz);
        Instant start=msTime(value.path("start")),end=msTime(value.path("end"));
        result.append("BEGIN:VEVENT\r\n");text(result,"UID","microsoft:"+hash(id));msDate(result,"DTSTART",start,zone,allDay,recurring);msDate(result,"DTEND",end,zone,allDay,recurring);
        text(result,"SUMMARY",value.path("subject").asText(""));text(result,"DESCRIPTION",value.path("body").path("contentType").asText().equalsIgnoreCase("text")?value.path("body").path("content").asText():value.path("bodyPreview").asText(""));text(result,"LOCATION",value.path("location").path("displayName").asText(""));
        if(recurring){
            result.append("RRULE:").append(msRule(recurrence,start.atZone(zone),allDay)).append("\r\n");
            for(var cancelled:value.path("cancelledOccurrences")){var date=occurrenceDate(cancelled.asText());msDate(result,"EXDATE",date.atTime(start.atZone(zone).toLocalTime()).atZone(zone).toInstant(),zone,allDay,true);}
        }
        result.append("END:VEVENT\r\n");
        if(recurring)for(var exception:value.path("exceptionOccurrences")){
            var original=exception.hasNonNull("originalStart")?OffsetDateTime.parse(exception.path("originalStart").asText()).toInstant():occurrenceDate(required(exception,"occurrenceId",2048)).atTime(start.atZone(zone).toLocalTime()).atZone(zone).toInstant();
            result.append("BEGIN:VEVENT\r\n");text(result,"UID","microsoft:"+hash(id));msDate(result,"RECURRENCE-ID",original,zone,allDay,true);
            if(exception.path("isCancelled").asBoolean())result.append("STATUS:CANCELLED\r\n");
            else{boolean exceptionAllDay=exception.path("isAllDay").asBoolean(allDay);msDate(result,"DTSTART",msTime(exception.path("start")),zone,exceptionAllDay,false);msDate(result,"DTEND",msTime(exception.path("end")),zone,exceptionAllDay,false);text(result,"SUMMARY",exception.path("subject").asText(value.path("subject").asText("")));text(result,"DESCRIPTION",exception.path("bodyPreview").asText(value.path("bodyPreview").asText("")));text(result,"LOCATION",exception.path("location").path("displayName").asText(value.path("location").path("displayName").asText("")));}
            result.append("END:VEVENT\r\n");
        }
    }
    private static LocalDate occurrenceDate(String value){try{if(value.length()<10)throw invalid();return LocalDate.parse(value.substring(value.length()-10));}catch(RuntimeException invalid){throw invalid();}}
    private static Instant msTime(JsonNode value){
        try{String time=required(value,"dateTime",100);try{return OffsetDateTime.parse(time).toInstant();}catch(java.time.format.DateTimeParseException noOffset){return LocalDateTime.parse(time).atZone(zone(required(value,"timeZone",100))).toInstant();}}
        catch(RuntimeException failure){throw invalid();}
    }
    private static String msRule(JsonNode recurrence,ZonedDateTime start,boolean allDay){
        var pattern=recurrence.path("pattern");var range=recurrence.path("range");String type=pattern.path("type").asText();int interval=pattern.path("interval").asInt();if(interval<1||interval>9999)throw invalid();
        String frequency=switch(type){case "daily"->"DAILY";case "weekly"->"WEEKLY";case "absoluteMonthly","relativeMonthly"->"MONTHLY";case "absoluteYearly","relativeYearly"->"YEARLY";default->throw invalid();};
        var rule=new StringBuilder("FREQ=").append(frequency).append(";INTERVAL=").append(interval);
        if(type.equals("weekly")||type.startsWith("relative")){
            var days=new ArrayList<String>();for(var day:pattern.path("daysOfWeek"))days.add(day(day.asText()));if(days.isEmpty())throw invalid();rule.append(";BYDAY=").append(String.join(",",days));
            if(type.equals("weekly"))rule.append(";WKST=").append(day(pattern.path("firstDayOfWeek").asText("sunday")));
            else rule.append(";BYSETPOS=").append(switch(pattern.path("index").asText("first")){case "first"->1;case "second"->2;case "third"->3;case "fourth"->4;case "last"->-1;default->throw invalid();});
        }
        if(type.startsWith("absolute")){int date=pattern.path("dayOfMonth").asInt();if(date<1||date>31)throw invalid();rule.append(";BYMONTHDAY=").append(date);}
        if(type.endsWith("Yearly")){int month=pattern.path("month").asInt();if(month<1||month>12)throw invalid();rule.append(";BYMONTH=").append(month);}
        switch(range.path("type").asText()){
            case "numbered"->{int count=range.path("numberOfOccurrences").asInt();if(count<1)throw invalid();rule.append(";COUNT=").append(count);}
            case "endDate"->{LocalDate last=LocalDate.parse(required(range,"endDate",10));rule.append(";UNTIL=").append(allDay?last.format(DATE):UTC.format(last.plusDays(1).atStartOfDay(start.getZone()).minusSeconds(1).toInstant())+"Z");}
            case "noEnd"->{}default->throw invalid();
        }
        return rule.toString();
    }
    private static String day(String value){return switch(value){case "monday"->"MO";case "tuesday"->"TU";case "wednesday"->"WE";case "thursday"->"TH";case "friday"->"FR";case "saturday"->"SA";case "sunday"->"SU";default->throw invalid();};}
    private static void msDate(StringBuilder result,String name,Instant value,ZoneId zone,boolean allDay,boolean recurring){if(allDay)result.append(name).append(";VALUE=DATE:").append(value.atZone(zone).toLocalDate().format(DATE)).append("\r\n");else if(recurring)local(result,name,value.atZone(zone).toLocalDateTime(),zone);else instant(result,name,value);}
    private static void instant(StringBuilder result,String name,Instant value){result.append(name).append(':').append(UTC.format(value)).append("Z\r\n");}
    private static void local(StringBuilder result,String name,LocalDateTime value,ZoneId zone){result.append(name).append(";TZID=").append(zone.getId()).append(':').append(LOCAL.format(value)).append("\r\n");}
    private static void text(StringBuilder result,String name,String value){if(value.length()>(name.equals("DESCRIPTION")?16000:4000))throw invalid();result.append(name).append(':').append(value.replace("\\","\\\\").replace("\r\n","\n").replace("\r","\n").replace("\n","\\n").replace(";","\\;").replace(",","\\,")).append("\r\n");}
    private static String required(JsonNode value,String field,int max){var item=value.get(field);if(item==null||!item.isTextual()||item.asText().isBlank()||item.asText().length()>max||item.asText().codePoints().anyMatch(Character::isISOControl))throw invalid();return item.asText();}
    private static ApiFailure invalid(){return new ApiFailure(503,"TASK_PROVIDER_INVALID_RESPONSE");}
}
