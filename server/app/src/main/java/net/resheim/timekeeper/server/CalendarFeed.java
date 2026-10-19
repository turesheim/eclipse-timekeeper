package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.StringReader;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;

/** Bounded, local RFC 5545 expansion. Feed data never triggers attachment or timezone downloads. */
final class CalendarFeed {
    static final int MAX_BYTES=2*1024*1024,MAX_EVENTS=2000;
    private final Map<String,Map<String,VEvent>> series=new LinkedHashMap<>();
    private static final class Budget {
        int candidates;final long deadline=System.nanoTime()+3_000_000_000L;
        void use() {if(++candidates>20000||System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw CalendarFailure.complex();}
    }
    record Occurrence(String key,String uid,String recurrenceId,String title,String description,String location,
            Instant start,Instant end,boolean allDay,String firstDate,String endDate) {
        ObjectNode json() {
            var value=object().put("key",key).put("uid",uid).put("recurrenceId",recurrenceId).put("title",title)
                    .put("description",description).put("location",location).put("start",start.toString()).put("end",end.toString())
                    .put("allDay",allDay).put("firstDate",firstDate).put("endDate",endDate);
            value.put("eventVersion",hash(canonical(value).toString()));return value;
        }
    }
    CalendarFeed(String text) {
        if(text!=null&&text.startsWith("\ufeff"))text=text.substring(1);
        if(text==null||text.length()>MAX_BYTES||!text.stripLeading().regionMatches(true,0,"BEGIN:VCALENDAR",0,15))throw CalendarFailure.invalid();
        int lines=0,depth=0;
        for(String line:text.lines().toList()){
            if(++lines>50000||line.length()>65536)throw CalendarFailure.complex();
            if(line.regionMatches(true,0,"BEGIN:",0,6)&&++depth>12)throw CalendarFailure.complex();
            if(line.regionMatches(true,0,"END:",0,4)&&--depth<0)throw CalendarFailure.invalid();
        }
        if(depth!=0)throw CalendarFailure.invalid();
        try {
            var calendar=new CalendarBuilder().build(new StringReader(text));int count=0;
            for(var component:calendar.getComponents()) if(component instanceof VEvent event) {
                if(++count>5000)throw CalendarFailure.complex();
                String uid=text(event,"UID",null,1024);if(uid==null||uid.isBlank())throw CalendarFailure.invalid();
                Temporal rid=date(event,"RECURRENCE-ID"),start=date(event,"DTSTART");
                if(start==null&&!cancelled(event))throw CalendarFailure.invalid();
                if(start!=null)duration(event);
                for(String name:List.of("SUMMARY","DESCRIPTION","LOCATION"))text(event,name,"",name.equals("DESCRIPTION")?16000:4000);
                for(String name:List.of("RRULE","EXRULE"))for(var rule:event.getProperties(name))validateRule(rule.getValue());
                var revisions=series.computeIfAbsent(uid,ignored->new LinkedHashMap<>());String key=rid==null?"":identity(rid);
                var previous=revisions.get(key);if(previous==null||revision(event).compareTo(revision(previous))>=0)revisions.put(key,event);
            }
        } catch(CalendarFailure failure) {throw failure;}catch(Exception failure) {throw CalendarFailure.invalid();}
    }
    private static void validateRule(String rule) {
        long combinations=1;
        for(String part:rule.split(";")) {
            var pair=part.split("=",2);if(pair.length!=2)throw CalendarFailure.invalid();
            if(pair[0].startsWith("BY")) {combinations*=pair[1].split(",").length;if(combinations>10000)throw CalendarFailure.complex();}
        }
    }
    private static String revision(VEvent event) {
        int sequence;try{sequence=Integer.parseInt(text(event,"SEQUENCE","0",12));}catch(Exception failure){throw CalendarFailure.invalid();}
        if(sequence<0)throw CalendarFailure.invalid();return String.format(Locale.ROOT,"%010d",sequence)+text(event,"LAST-MODIFIED",text(event,"DTSTAMP","",100),100);
    }
    private static String text(VEvent event,String name,String fallback,int max) {
        var property=event.getProperty(name);if(property.isEmpty())return fallback;
        String value=property.get().getValue();if(value.length()>max)throw CalendarFailure.complex();return value;
    }
    private static Temporal date(VEvent event,String name) {return event.getProperty(name).map(value->((DateProperty<?>)value).getDate()).orElse(null);}
    private static boolean cancelled(VEvent event) {return "CANCELLED".equals(text(event,"STATUS","",30));}
    private static boolean range(VEvent event) {
        return event.<RecurrenceId<?>>getProperty("RECURRENCE-ID")
                .flatMap(value->value.<net.fortuna.ical4j.model.parameter.Range>getParameter("RANGE"))
                .map(value->"THISANDFUTURE".equals(value.getValue())).orElse(false);
    }
    private static String identity(Temporal value) {
        if(value instanceof LocalDate)return "D:"+value;if(value instanceof LocalDateTime)return "F:"+value;
        return "T:"+Instant.from(value);
    }
    static Instant instant(Temporal value,ZoneId zone) {
        if(value instanceof LocalDate date)return date.atStartOfDay(zone).toInstant();
        if(value instanceof LocalDateTime date)return date.atZone(zone).toInstant();return Instant.from(value);
    }
    private static TemporalAmount duration(VEvent event) {
        var start=date(event,"DTSTART");var end=date(event,"DTEND");
        TemporalAmount result=event.getProperty("DURATION").map(value->((net.fortuna.ical4j.model.property.Duration)value).getDuration()).orElse(null);
        if(start==null)throw CalendarFailure.invalid();
        if(result!=null&&end!=null)throw CalendarFailure.invalid();
        if(result==null)result=end==null?(start instanceof LocalDate?java.time.Period.ofDays(1):java.time.Duration.ZERO):
                start instanceof LocalDate?java.time.Period.ofDays(Math.toIntExact(ChronoUnit.DAYS.between(start,end))):java.time.Duration.between(start,end);
        Instant a=instant(start,ZoneOffset.UTC),b=instant(start.plus(result),ZoneOffset.UTC);
        if(b.isBefore(a)||start instanceof LocalDate&&!b.isAfter(a)||java.time.Duration.between(a,b).compareTo(java.time.Duration.ofDays(366))>0)throw CalendarFailure.invalid();return result;
    }
    private static Temporal boundary(Instant instant,Temporal sample,ZoneId account) {
        if(sample instanceof LocalDate)return instant.atZone(account).toLocalDate();
        if(sample instanceof LocalDateTime)return instant.atZone(account).toLocalDateTime();
        if(sample instanceof ZonedDateTime date)return instant.atZone(date.getZone());
        if(sample instanceof OffsetDateTime date)return instant.atOffset(date.getOffset());return instant;
    }
    private static Temporal move(Temporal value,java.time.Duration delta) {
        if(value instanceof LocalDate date)return date.plusDays(delta.toDays());
        if(value instanceof ZonedDateTime date)return date.toLocalDateTime().plus(delta).atZone(date.getZone());
        return value.plus(delta);
    }
    private static java.time.Duration shift(VEvent event,ZoneId zone) {
        Temporal a=date(event,"RECURRENCE-ID"),b=date(event,"DTSTART");if(b==null)return java.time.Duration.ZERO;
        java.time.Duration result;
        if(a instanceof LocalDate)result=java.time.Duration.ofDays(ChronoUnit.DAYS.between(a,b));
        else if(a instanceof ZonedDateTime date)result=java.time.Duration.between(date.toLocalDateTime(),instant(b,zone).atZone(date.getZone()).toLocalDateTime());
        else result=java.time.Duration.between(a,b);
        if(result.abs().compareTo(java.time.Duration.ofDays(366))>0)throw CalendarFailure.complex();return result;
    }
    @SuppressWarnings("unchecked")
    private static List<net.fortuna.ical4j.model.Period<Temporal>> periods(VEvent event,Instant from,Instant to,ZoneId zone,java.time.Duration shift,Budget budget) {
        Temporal seed=date(event,"DTSTART"),begin=move(boundary(from,seed,zone),shift.negated()),finish=move(boundary(to,seed,zone),shift.negated());
        TemporalAmount length=duration(event);var dates=new ArrayList<Temporal>();var exceptions=new ArrayList<Temporal>();
        var explicit=new HashSet<net.fortuna.ical4j.model.Period<Temporal>>();
        for(String name:List.of("RRULE","EXRULE"))for(var property:event.getProperties(name)) {
            Recur<Temporal> rule=name.equals("RRULE")?((RRule<Temporal>)property).getRecur():((ExRule<Temporal>)property).getRecur();
            try(var stream=rule.getDatesAsStream(seed,begin.minus(length),finish,MAX_EVENTS+1)) {
                var values=stream.limit(MAX_EVENTS+1L).peek(value->budget.use()).toList();
                if(values.size()>MAX_EVENTS)throw CalendarFailure.complex();(name.equals("RRULE")?dates:exceptions).addAll(values);
            }
        }
        for(String name:List.of("RDATE","EXDATE"))for(var property:event.getProperties(name)) {
            if(property instanceof RDate<?> rdate&&rdate.getPeriods().isPresent())explicit.addAll((Set<net.fortuna.ical4j.model.Period<Temporal>>)(Set<?>)rdate.getPeriods().get());
            else for(var value:((DateListProperty<Temporal>)property).getDates())(name.equals("RDATE")?dates:exceptions).add(
                    name.equals("EXDATE")?boundary(instant(value,zone),seed,zone):value);
        }
        var bounds=new net.fortuna.ical4j.model.Period<Temporal>(begin,finish);
        // Rules have already been expanded through a bounded stream. The library
        // still applies the RFC duration, RDATE periods and exclusion algebra.
        return List.copyOf(new RecurrenceSet.Builder<Temporal>().start(seed).duration(length).period(bounds)
                .recurrenceDates(dates).recurrencePeriods(explicit).exceptionDates(exceptions).build());
    }
    List<Occurrence> occurrences(LocalDate first,LocalDate last,ZoneId zone) {
        if(!last.isAfter(first)||ChronoUnit.DAYS.between(first,last)>31)throw CalendarFailure.invalid();
        Instant from=first.atStartOfDay(zone).toInstant(),to=last.atStartOfDay(zone).toInstant();
        var result=new LinkedHashMap<String,Occurrence>();var budget=new Budget();
        try {
            for(var group:series.entrySet()) {
                budget.use();String uid=group.getKey();var revisions=group.getValue();var master=revisions.get("");
                if(master!=null&&cancelled(master))continue;
                var overrides=revisions.entrySet().stream().filter(value->!value.getKey().isEmpty()).map(Map.Entry::getValue).toList();
                var ranges=overrides.stream().filter(CalendarFeed::range).sorted(Comparator.comparing(value->instant(date(value,"RECURRENCE-ID"),zone))).toList();
                if(master==null&&!ranges.isEmpty())throw CalendarFailure.invalid();
                boolean recurring=master!=null&&(!master.getProperties("RRULE").isEmpty()||!master.getProperties("RDATE").isEmpty()||!overrides.isEmpty());
                if(master!=null)for(int segment=-1;segment<ranges.size();segment++) {
                    var override=segment<0?null:ranges.get(segment);if(override!=null&&cancelled(override))continue;
                    Instant lower=override==null?Instant.MIN:instant(date(override,"RECURRENCE-ID"),zone),upper=segment+1<ranges.size()?instant(date(ranges.get(segment+1),"RECURRENCE-ID"),zone):Instant.MAX;
                    var delta=override==null?java.time.Duration.ZERO:shift(override,zone);
                    for(var period:periods(master,from,to,zone,delta,budget)) {
                        budget.use();Temporal original=period.getStart();Instant position=instant(original,zone);
                        if(position.isBefore(lower)||!position.isBefore(upper)||overrides.stream().anyMatch(value->!range(value)&&identity(date(value,"RECURRENCE-ID")).equals(identity(original))))continue;
                        var source=override==null?master:override;Temporal start=move(original,delta),end=override!=null&&(override.getProperty("DTEND").isPresent()||override.getProperty("DURATION").isPresent())?start.plus(duration(override)):start.plus(period.getDuration());
                        add(result,uid,recurring?identity(original):"single",source,master,start,end,from,to,zone);
                    }
                }
                for(var override:overrides)if(!range(override)&&!cancelled(override)) {
                    Temporal start=date(override,"DTSTART");add(result,uid,identity(date(override,"RECURRENCE-ID")),override,master,start,start.plus(duration(override)),from,to,zone);
                }
            }
        } catch(CalendarFailure failure) {throw failure;}catch(Exception failure) {throw CalendarFailure.invalid();}
        return result.values().stream().sorted(Comparator.comparing(Occurrence::start).thenComparing(Occurrence::key)).toList();
    }
    private static void add(Map<String,Occurrence> result,String uid,String rid,VEvent source,VEvent master,Temporal start,Temporal end,Instant from,Instant to,ZoneId zone) {
        Instant a=instant(start,zone),b=instant(end,zone);if(a.isBefore(from)&&!b.isAfter(from)||!a.isBefore(to))return;
        String key=hash(array().add(uid).add(rid).toString());boolean allDay=start instanceof LocalDate;
        String title=text(source,"SUMMARY",master==null?"":text(master,"SUMMARY","",4000),4000);
        String description=text(source,"DESCRIPTION",master==null?"":text(master,"DESCRIPTION","",16000),16000);
        String location=text(source,"LOCATION",master==null?"":text(master,"LOCATION","",4000),4000);
        result.put(key,new Occurrence(key,uid,rid,title,description,location,a,b,allDay,allDay?start.toString():null,allDay?end.toString():null));
        if(result.size()>MAX_EVENTS)throw CalendarFailure.complex();
    }
}
