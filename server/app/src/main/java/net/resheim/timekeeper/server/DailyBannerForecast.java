package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.net.http.HttpRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

/** Public weather data only. No account identifiers or credentials leave the server. */
@ApplicationScoped
class DailyBannerForecast {
    private record Cached(Instant expires,JsonNode data){}
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    private final Semaphore capacity=new Semaphore(4);
    private final Object[] locks=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private final TaskProviderHttp http;
    DailyBannerForecast(){this(new TaskProviderHttp(Set.of("api.met.no")));}
    DailyBannerForecast(TaskProviderHttp http){this.http=http;}

    ObjectNode preview(JsonNode profile,LocalDate date,Instant now){
        var location=profile.get("weatherLocation");var zone=ZoneId.of(profile.path("zoneId").asText());
        boolean south=location!=null&&!location.isNull()&&location.path("latitude").asDouble()<0;
        int month=(date.getMonthValue()-1+(south?6:0))%12+1;
        String season=month==11?"late-autumn":month==12||month<=2?"winter":month<=5?"spring":month<=8?"summer":"autumn";
        var result=object().put("version",1).put("date",date.toString()).put("zoneId",zone.getId()).put("season",season).put("weather","cloudy");
        var slots=result.putArray("forecast");
        if(location==null||location.isNull()||date.isBefore(now.atZone(zone).toLocalDate())||date.isAfter(now.atZone(zone).toLocalDate().plusDays(9)))return result;
        try{
            var data=forecast(location,now);var candidates=new ArrayList<JsonNode>();
            if(!data.path("properties").path("meta").path("units").path("air_temperature").asText().equals("celsius"))return result;
            for(var row:data.path("properties").path("timeseries")){
                var instant=Instant.parse(row.path("time").asText());double temp=row.path("data").path("instant").path("details").path("air_temperature").asDouble(Double.NaN);
                if(instant.atZone(zone).toLocalDate().equals(date)&&Double.isFinite(temp)&&temp>=-120&&temp<=80&&!symbol(row).isEmpty())candidates.add(row);
            }
            if(candidates.size()<3)return result;
            var chosen=new ArrayList<JsonNode>();
            for(int hour:List.of(9,12,15)){
                var target=date.atTime(hour,0).atZone(zone).toInstant();
                var row=candidates.stream().min(Comparator.comparingLong(value->Math.abs(Duration.between(target,Instant.parse(value.path("time").asText())).toSeconds()))).orElseThrow();
                chosen.add(row);candidates.remove(row);
            }
            chosen.sort(Comparator.comparing(value->value.path("time").asText()));
            for(var row:chosen){
                var instant=Instant.parse(row.path("time").asText());String condition=condition(symbol(row));
                slots.add(object().put("time",instant.atZone(zone).toLocalTime().toString()).put("instant",instant.toString()).put("symbol",symbol(row)).put("condition",condition).put("temperatureCelsius",row.path("data").path("instant").path("details").path("air_temperature").asDouble()));
                if(Set.of("snow","sleet","rain","thunder").contains(condition)||slots.size()==1)result.put("weather",condition);
            }
            result.put("provider","MET Norway").put("capturedAt",now.toString());
        }catch(RuntimeException unavailable){/* A seasonal image remains useful without a forecast. */}
        return result;
    }
    private JsonNode forecast(JsonNode location,Instant now){
        String latitude=String.format(Locale.ROOT,"%.4f",location.path("latitude").asDouble()),longitude=String.format(Locale.ROOT,"%.4f",location.path("longitude").asDouble());
        String key=latitude+","+longitude;
        synchronized(locks[Math.floorMod(key.hashCode(),locks.length)]){return fetch(latitude,longitude,key,now);}
    }
    private JsonNode fetch(String latitude,String longitude,String key,Instant now){
        var previous=cache.get(key);if(previous!=null&&previous.expires().isAfter(now))return previous.data();
        if(!capacity.tryAcquire())throw new IllegalStateException("Weather busy");
        try{
            // Three hours also respects MET Norway's minimum interval outside the Nordic region.
            var request=HttpRequest.newBuilder(http.address("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat="+latitude+"&lon="+longitude)).timeout(Duration.ofSeconds(8)).header("Accept","application/json").header("User-Agent","Dagsverk/2.0 (https://github.com/turesheim/eclipse-timekeeper)").GET().build();
            if(cache.size()>=512)cache.entrySet().removeIf(entry->!entry.getValue().expires().isAfter(now));if(cache.size()>=512)cache.clear();
            try{var data=http.send(request);cache.put(key,new Cached(now.plusSeconds(10800),data));return data;}
            catch(RuntimeException unavailable){cache.put(key,new Cached(now.plusSeconds(300),object()));throw unavailable;}
        }finally{capacity.release();}
    }
    private static String symbol(JsonNode row){for(String period:List.of("next_1_hours","next_6_hours","next_12_hours")){String symbol=row.path("data").path(period).path("summary").path("symbol_code").asText();if(!symbol.isBlank()&&symbol.length()<80)return symbol;}return "";}
    private static String condition(String symbol){if(symbol.contains("thunder"))return "thunder";if(symbol.contains("sleet"))return "sleet";if(symbol.contains("snow"))return "snow";if(symbol.contains("rain"))return "rain";if(symbol.contains("fog"))return "fog";if(symbol.startsWith("clearsky"))return "clear";if(symbol.startsWith("fair"))return "fair";if(symbol.startsWith("partlycloudy"))return "partlycloudy";return "cloudy";}
}
