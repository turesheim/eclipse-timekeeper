package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

/** Public place names only. Account data and credentials never enter a lookup. */
@ApplicationScoped
class WeatherLocations {
    private record Cached(Instant expires,ObjectNode data){}
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    private final Semaphore capacity=new Semaphore(1);
    private final TaskProviderHttp http;
    private Instant next=Instant.MIN;
    private LocalDate day;
    private int requests;
    WeatherLocations(){this(new TaskProviderHttp(Set.of("photon.komoot.io")));}
    WeatherLocations(TaskProviderHttp http){this.http=http;}

    ObjectNode search(String value,String language,Instant now){
        if(value==null||language==null||value.codePoints().anyMatch(Character::isISOControl)||!Set.of("en","nb").contains(language))throw ApiFailure.badRequest();
        String query=Normalizer.normalize(value.strip(),Normalizer.Form.NFKC).replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        int length=query.codePointCount(0,query.length());if(length<2||length>120)throw ApiFailure.validation();
        String key=language+"\0"+query;var cached=cache.get(key);
        if(cached!=null&&cached.expires().isAfter(now)){if(cached.data()==null)throw unavailable();return cached.data().deepCopy();}
        if(!capacity.tryAcquire())throw limited();
        try{
            reserve(now);
            // The public instance supports English and local names, not nb.
            String preferred=language.equals("en")?"&lang=en":"";
            var request=HttpRequest.newBuilder(http.address("https://photon.komoot.io/api/?limit=8"+preferred+"&q="+URLEncoder.encode(query,StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8)).header("Accept","application/json").header("User-Agent","Dagsverk/2.0 (https://github.com/turesheim/eclipse-timekeeper)").GET().build();
            final ObjectNode result;
            try{result=locations(http.send(request));}
            catch(RuntimeException failed){remember(key,new Cached(now.plusSeconds(30),null),now);throw unavailable();}
            remember(key,new Cached(now.plusSeconds(86400),result),now);return result.deepCopy();
        }finally{capacity.release();}
    }
    private synchronized void reserve(Instant now){
        var today=now.atZone(ZoneOffset.UTC).toLocalDate();if(!today.equals(day)){day=today;requests=0;}
        if(now.isBefore(next)||requests>=500)throw limited();requests++;next=now.plusSeconds(1);
    }
    private void remember(String key,Cached value,Instant now){
        if(cache.size()>=256)cache.entrySet().removeIf(entry->!entry.getValue().expires().isAfter(now));
        if(cache.size()>=256)cache.remove(cache.keySet().iterator().next());cache.put(key,value);
    }
    private static ObjectNode locations(JsonNode data){
        if(!data.path("features").isArray())throw unavailable();var result=object();var items=result.putArray("items");var seen=new HashSet<String>();int examined=0;
        for(var feature:data.path("features")){
            if(++examined>64||items.size()>=8)break;var geometry=feature.path("geometry");var point=geometry.path("coordinates");
            if(!geometry.path("type").asText().equals("Point")||!point.isArray()||point.size()!=2||!point.get(0).isNumber()||!point.get(1).isNumber())continue;
            double longitude=point.get(0).asDouble(),latitude=point.get(1).asDouble();
            if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180)continue;
            var properties=feature.path("properties");if(!properties.path("name").isTextual()||properties.path("name").asText().isBlank())continue;
            var names=new LinkedHashSet<String>();for(String part:List.of("name","city","state","country")){var text=properties.path(part);if(text.isTextual()){String clean=text.asText().replaceAll("\\p{Cntrl}"," ").strip();if(!clean.isEmpty())names.add(clean);}}
            String name=String.join(", ",names);if(name.isEmpty())continue;if(name.codePointCount(0,name.length())>120)name=name.substring(0,name.offsetByCodePoints(0,120));
            if(seen.add(name+"\0"+latitude+","+longitude))items.add(object().put("name",name).put("latitude",latitude).put("longitude",longitude));
        }
        return result;
    }
    private static ApiFailure limited(){return new ApiFailure(429,"WEATHER_LOCATION_RATE_LIMITED");}
    private static ApiFailure unavailable(){return new ApiFailure(503,"WEATHER_LOCATION_UNAVAILABLE");}
}
