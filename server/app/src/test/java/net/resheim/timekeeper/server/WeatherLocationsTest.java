package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WeatherLocationsTest {
    private final Instant now=Instant.parse("2026-11-17T12:00:00Z");
    static String places(){return "{\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[10.3952,63.4304]},\"properties\":{\"name\":\"Trondheim\",\"city\":\"Trondheim\",\"state\":\"Trøndelag\",\"country\":\"Norge\",\"ignored\":\"not returned\"}}]}";}
    private WeatherLocations service(AtomicInteger calls,int status,String body){return new WeatherLocations(new TaskProviderHttp(Set.of("photon.komoot.io"),request->{
        calls.incrementAndGet();assertEquals("photon.komoot.io",request.uri().getHost());assertEquals(Duration.ofSeconds(8),request.timeout().orElseThrow());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());assertTrue(request.headers().firstValue("Cookie").isEmpty());assertTrue(request.headers().firstValue("User-Agent").orElseThrow().contains("Dagsverk"));
        assertFalse(request.uri().getRawQuery().contains("lang=nb"));return new TaskProviderHttp.Reply(status,Map.of(),body.getBytes(StandardCharsets.UTF_8));
    }));}
    @Test void normalizedCachedResultsKeepCoordinatesAndNeverReturnProviderFields(){
        var calls=new AtomicInteger();var service=service(calls,200,places());var result=service.search("  TRONDHEIM  ","nb",now);
        var place=result.path("items").get(0);assertEquals("Trondheim, Trøndelag, Norge",place.path("name").asText());assertEquals(63.4304,place.path("latitude").asDouble());assertEquals(10.3952,place.path("longitude").asDouble());assertFalse(result.toString().contains("ignored"));
        result.withArray("items").removeAll();assertEquals(1,service.search("Trondheim","nb",now).path("items").size());assertEquals(1,calls.get());
        service.search("Trondheim","en",now.plusSeconds(1));assertEquals(2,calls.get());service.search("Trondheim","nb",now.plusSeconds(86401));assertEquals(3,calls.get());
    }
    @Test void malformedQueriesNeverLeaveTheServerAndUserTextCannotChangeTheDestination(){
        var calls=new AtomicInteger();var service=service(calls,200,places());
        for(String query:List.of(""," ","x","a".repeat(121),"city\nprivate"))assertThrows(ApiFailure.class,()->service.search(query,"en",now));
        assertThrows(ApiFailure.class,()->service.search("Oslo","unsupported",now));assertEquals(0,calls.get());
        var protectedTransport=new WeatherLocations(new TaskProviderHttp(Set.of("photon.komoot.io"),request->{assertEquals("https://photon.komoot.io/api/",request.uri().toString().split("\\?")[0]);assertTrue(request.uri().getRawQuery().contains("q=city+%26+host%3Dlocalhost"));return new TaskProviderHttp.Reply(200,Map.of(),places().getBytes(StandardCharsets.UTF_8));}));
        assertEquals(1,protectedTransport.search("City & host=localhost","en",now).path("items").size());
    }
    @Test void providerFailureIsSanitizedAndBrieflyCachedWhileEmptyResultsRemainSuccessful(){
        var calls=new AtomicInteger();var service=service(calls,503,"{\"private\":\"provider details\"}");
        assertEquals("WEATHER_LOCATION_UNAVAILABLE",assertThrows(ApiFailure.class,()->service.search("Oslo","en",now)).code);
        assertEquals(503,assertThrows(ApiFailure.class,()->service.search("Oslo","en",now.plusSeconds(1))).status);assertEquals(1,calls.get());
        assertThrows(ApiFailure.class,()->service.search("Oslo","en",now.plusSeconds(31)));assertEquals(2,calls.get());
        assertEquals(0,service(new AtomicInteger(),200,"{\"features\":[]}").search("Missing city","en",now).path("items").size());
        assertThrows(ApiFailure.class,()->service(new AtomicInteger(),200,"{}").search("Oslo","en",now));
    }
    @Test void invalidCoordinatesAreDiscardedAndLookupBudgetsDoNotApplyToCachedReads(){
        var data=parse(places());var features=(com.fasterxml.jackson.databind.node.ArrayNode)data.path("features");var invalid=features.get(0).deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)invalid.path("geometry").path("coordinates")).set(1,com.fasterxml.jackson.databind.node.DoubleNode.valueOf(91));features.add(invalid);
        features.add(parse("{\"geometry\":{\"type\":\"Point\",\"coordinates\":[0,0]},\"properties\":{\"name\":\"\\u0000\"}}"));
        var calls=new AtomicInteger();var service=service(calls,200,data.toString());assertEquals(1,service.search("Oslo","en",now).path("items").size());
        assertEquals(429,assertThrows(ApiFailure.class,()->service.search("Bergen","en",now)).status);assertEquals(1,service.search("Oslo","en",now).path("items").size());assertEquals(1,calls.get());
        for(int i=1;i<500;i++)service.search("Place "+i,"en",now.plusSeconds(i));assertEquals(500,calls.get());
        assertEquals(429,assertThrows(ApiFailure.class,()->service.search("Daily limit","en",now.plusSeconds(501))).status);service.search("Fresh day","en",now.plusSeconds(86400));assertEquals(501,calls.get());
    }
}
