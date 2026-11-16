package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DailyBannerForecastTest {
    private final Instant now=Instant.parse("2026-11-15T00:00:00Z");
    private com.fasterxml.jackson.databind.node.ObjectNode profile(String zone,double latitude){return object().put("zoneId",zone).set("weatherLocation",object().put("latitude",latitude).put("longitude",10.3951));}
    private String forecast(){
        var value=object();var properties=value.putObject("properties");properties.putObject("meta").putObject("units").put("air_temperature","celsius");var series=properties.putArray("timeseries");
        for(int hour=0;hour<72;hour++){var row=series.addObject().put("time",now.plusSeconds(3600L*hour).toString());var data=row.putObject("data");data.putObject("instant").putObject("details").put("air_temperature",hour-30);data.putObject("next_1_hours").putObject("summary").put("symbol_code",hour%3==0?"lightsnow":"cloudy");}return value.toString();
    }
    private DailyBannerForecast service(AtomicInteger calls,boolean fail){return new DailyBannerForecast(new TaskProviderHttp(Set.of("api.met.no"),request->{calls.incrementAndGet();assertEquals("https",request.uri().getScheme());assertEquals("api.met.no",request.uri().getHost());assertTrue(request.headers().firstValue("User-Agent").orElseThrow().contains("github.com/turesheim"));assertTrue(request.headers().firstValue("Authorization").isEmpty());return new TaskProviderHttp.Reply(fail?503:200,Map.of(),forecast().getBytes(java.nio.charset.StandardCharsets.UTF_8));}));}
    @Test void slotsUseTheNotesLocalDateAndActualLocalTimesWithOneCachedFetch(){
        var calls=new AtomicInteger();var weather=service(calls,false);var date=LocalDate.parse("2026-11-16");
        for(String zone:List.of("Europe/Oslo","Pacific/Auckland","America/New_York")){
            var reply=weather.preview(profile(zone,63.43),date,now);assertEquals(3,reply.path("forecast").size());assertEquals(zone,reply.path("zoneId").asText());assertEquals("late-autumn",reply.path("season").asText());
            for(int index=0;index<3;index++){var slot=reply.path("forecast").get(index);assertEquals(List.of("09:00","12:00","15:00").get(index),slot.path("time").asText());assertEquals(date,Instant.parse(slot.path("instant").asText()).atZone(ZoneId.of(zone)).toLocalDate());assertTrue(slot.path("temperatureCelsius").isNumber());}
        }
        assertEquals(1,calls.get());weather.preview(profile("Europe/Oslo",63.43),date,now.plusSeconds(10801));assertEquals(2,calls.get());
    }
    @Test void absencePastDatesAndProviderFailureProduceUsableSeasonalOnlyProposals(){
        var calls=new AtomicInteger();var weather=service(calls,true);var date=LocalDate.parse("2026-11-16");
        assertEquals(0,weather.preview(object().put("zoneId","Europe/Oslo"),date,now).path("forecast").size());
        assertEquals(0,weather.preview(profile("Europe/Oslo",63),date.minusDays(3),now).path("forecast").size());assertEquals(0,calls.get());
        var southern=weather.preview(profile("Australia/Sydney",-33),date,now);assertEquals("spring",southern.path("season").asText());assertEquals(0,southern.path("forecast").size());
        weather.preview(profile("Australia/Sydney",-33),date,now);assertEquals(1,calls.get());
    }
    @Test void concurrentCreationAtOneLocationCoalescesTheProviderRead()throws Exception{
        var calls=new AtomicInteger();var weather=service(calls,false);
        try(var executor=Executors.newFixedThreadPool(8)){var tasks=new ArrayList<Future<?>>();for(int i=0;i<16;i++)tasks.add(executor.submit(()->assertEquals(3,weather.preview(profile("Europe/Oslo",63),LocalDate.parse("2026-11-16"),now).path("forecast").size())));for(var task:tasks)task.get();}
        assertEquals(1,calls.get());
    }
}
