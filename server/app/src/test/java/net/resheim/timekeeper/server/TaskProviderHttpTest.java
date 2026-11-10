package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskProviderHttpTest {
    private static final String ADDRESS="https://tasks.googleapis.com/tasks/v1/users/@me/lists";
    private TaskProviderHttp http(TaskProviderHttp.Transport transport){return new TaskProviderHttp(Set.of("tasks.googleapis.com"),transport);}
    private TaskProviderHttp.Reply reply(int status,String body){return new TaskProviderHttp.Reply(status,Map.of(),body.getBytes(StandardCharsets.UTF_8));}

    @Test void authenticatedReadsRetainPaginationWithoutExposingTokens(){
        var transport=http(request->{
            assertEquals("GET",request.method());assertEquals(ADDRESS,request.uri().toString());
            assertEquals("Bearer private-token",request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("application/json",request.headers().firstValue("Accept").orElseThrow());
            assertTrue(request.timeout().orElseThrow().toSeconds()<=30);
            assertFalse(request.headers().firstValue("Cookie").isPresent());
            return reply(200,"{\"items\":[{\"id\":\"list-1\"}],\"nextPageToken\":\"opaque+/token\"}");
        });
        var page=transport.json("GET",ADDRESS,"private-token",null,null);
        assertEquals("list-1",page.path("items").get(0).path("id").asText());
        assertEquals("opaque+/token",page.path("nextPageToken").asText());
    }
    @ParameterizedTest @ValueSource(strings={"http://tasks.googleapis.com/tasks","https://tasks.googleapis.com.evil.invalid/tasks","https://user@tasks.googleapis.com/tasks","https://tasks.googleapis.com:443/tasks","https://tasks.googleapis.com/tasks#secret","https://127.0.0.1/tasks","file:///etc/passwd","//tasks.googleapis.com/tasks"})
    void untrustedAddressesCannotReceiveCredentials(String address){
        var transport=http(request->{fail("No request may be sent");return null;});
        assertEquals("TASK_PROVIDER_INVALID_ADDRESS",assertThrows(TaskProviderHttp.Failure.class,()->transport.json("GET",address,"private-token",null,null)).getMessage());
    }
    @Test void redirectsAndProviderBodiesAreNotFollowedOrDisclosed(){
        var calls=new AtomicInteger();var transport=http(request->{calls.incrementAndGet();return reply(302,"{\"error\":\"private provider detail\"}");});
        var failure=assertThrows(TaskProviderHttp.Failure.class,()->transport.json("GET",ADDRESS,"private-token",null,null));
        assertEquals(1,calls.get());assertEquals("TASK_PROVIDER_UNAVAILABLE",failure.getMessage());assertFalse(failure.toString().contains("private"));
    }
    @Test void throttledCallsAreNotRetriedAndRetainBoundedBackoff(){
        var calls=new AtomicInteger();var transport=http(request->{calls.incrementAndGet();return new TaskProviderHttp.Reply(429,Map.of("retry-after","3600"),new byte[0]);});
        var failure=assertThrows(TaskProviderHttp.Failure.class,()->transport.json("GET",ADDRESS,"private-token",null,null));
        assertEquals(1,calls.get());assertEquals("TASK_PROVIDER_RATE_LIMITED",failure.getMessage());assertEquals(3600,failure.retryAfterSeconds);
    }
    @Test void retryAfterDatesAndLongDelaysAreNotShortenedToTheOrdinaryPollingInterval(){
        var now=java.time.Instant.parse("2026-11-10T00:00:00.500Z");
        assertEquals(3600,TaskProviderHttp.retryAfter("Tue, 10 Nov 2026 01:00:00 GMT",now));
        assertEquals(86400,TaskProviderHttp.retryAfter("86400",now));assertEquals(0,TaskProviderHttp.retryAfter("Tue, 10 Nov 2026 00:00:00 GMT",now));
        assertEquals(0,TaskProviderHttp.retryAfter("invalid",now));assertEquals(0,TaskProviderHttp.retryAfter("-1",now));assertEquals(Integer.MAX_VALUE,TaskProviderHttp.retryAfter("99999999999999999999",now));
    }
    @Test void conditionalWritesUseProviderEtagAndNeverRetryAnUncertainOutcome(){
        var calls=new AtomicInteger();var transport=http(request->{
            calls.incrementAndGet();assertEquals("PATCH",request.method());assertEquals("\"provider-version\"",request.headers().firstValue("If-Match").orElseThrow());
            throw new IOException("private network details");
        });
        var failure=assertThrows(TaskProviderHttp.Failure.class,()->transport.json("PATCH",ADDRESS,"private-token",JsonValues.object().put("title","Changed"),"\"provider-version\""));
        assertEquals(1,calls.get());assertTrue(failure.uncertain);assertEquals("TASK_PROVIDER_UNAVAILABLE",failure.getMessage());
    }
    @Test void staleProviderVersionsAndExpiredAuthorizationAreDistinct(){
        var stale=http(request->reply(412,"{}"));var expired=http(request->reply(401,"{}"));
        assertEquals("TASK_PROVIDER_CHANGED",assertThrows(TaskProviderHttp.Failure.class,()->stale.json("PATCH",ADDRESS,"token",JsonValues.object(),"\"old\"")).getMessage());
        assertEquals("TASK_PROVIDER_RECONNECT_REQUIRED",assertThrows(TaskProviderHttp.Failure.class,()->expired.json("GET",ADDRESS,"token",null,null)).getMessage());
    }
    @Test void oversizedOrMalformedWriteResponsesRemainUncertain(){
        var large=http(request->new TaskProviderHttp.Reply(200,Map.of(),new byte[TaskProviderHttp.MAX_BYTES+1]));
        var malformed=http(request->reply(200,"not json: private detail"));
        for(var transport:new TaskProviderHttp[]{large,malformed}){
            var failure=assertThrows(TaskProviderHttp.Failure.class,()->transport.json("PATCH",ADDRESS,"token",JsonValues.object(),"\"v1\""));
            assertTrue(failure.uncertain);assertFalse(failure.toString().contains("private detail"));
        }
    }
    @Test void interruptedRequestsPreserveCancellation(){
        var transport=http(request->{throw new InterruptedException();});
        try{var failure=assertThrows(TaskProviderHttp.Failure.class,()->transport.json("GET",ADDRESS,"token",null,null));assertTrue(Thread.currentThread().isInterrupted());assertEquals("TASK_PROVIDER_CANCELLED",failure.getMessage());}
        finally{Thread.interrupted();}
    }
    @Test void chunkedResponsesAreCancelledAtTheByteLimit(){
        var subscriber=TaskProviderHttp.limitedBody(200);var cancelled=new AtomicInteger();
        subscriber.onSubscribe(new Flow.Subscription(){public void request(long count){}public void cancel(){cancelled.incrementAndGet();}});
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[TaskProviderHttp.MAX_BYTES])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        assertEquals(1,cancelled.get());assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
        subscriber.onComplete();assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void ordinaryChunkedResponsesRemainComplete(){
        var subscriber=TaskProviderHttp.limitedBody(200);
        subscriber.onSubscribe(new Flow.Subscription(){public void request(long count){}public void cancel(){fail("Ordinary response must complete");}});
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2})));subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{3})));subscriber.onComplete();
        assertArrayEquals(new byte[]{1,2,3},subscriber.getBody().toCompletableFuture().join());
    }
    @Test void oauthFormRequestsCannotBypassTheDeadlineOrSendCookies(){
        var transport=http(request->{fail("Invalid requests cannot reach the provider");return null;});
        var missingDeadline=HttpRequest.newBuilder(URI.create(ADDRESS)).GET().build();
        var cookies=HttpRequest.newBuilder(URI.create(ADDRESS)).timeout(Duration.ofSeconds(25)).header("Cookie","private-session").POST(HttpRequest.BodyPublishers.noBody()).build();
        for(var request:new HttpRequest[]{missingDeadline,cookies})assertEquals("TASK_PROVIDER_INVALID_REQUEST",assertThrows(TaskProviderHttp.Failure.class,()->transport.send(request)).getMessage());
    }
}
