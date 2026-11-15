package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MicrosoftTasksWriterTest {
    private MicrosoftTasksWriter writer(TaskProviderHttp.Transport transport){return new MicrosoftTasksWriter(new TaskProviderHttp(Set.of("graph.microsoft.com"),transport));}
    private TaskProviderHttp.Reply reply(int status,String value){return new TaskProviderHttp.Reply(status,Map.of(),value.getBytes(StandardCharsets.UTF_8));}
    private String body(java.net.http.HttpRequest request){
        var bytes=new java.io.ByteArrayOutputStream();var done=new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription value){value.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer value){byte[] part=new byte[value.remaining()];value.get(part);bytes.writeBytes(part);}
            public void onError(Throwable failure){done.completeExceptionally(failure);}
            public void onComplete(){done.complete(null);}
        });done.join();return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test void patchesUseTheExactGraphRevisionAndPreserveProviderOnlyFields(){
        var writer=writer(request->{assertEquals("PATCH",request.method());assertEquals("W/\"v1\"",request.headers().firstValue("If-Match").orElseThrow());assertEquals(parse("{\"title\":\"Changed\"}"),parse(body(request)));assertTrue(request.uri().toString().endsWith("list%2Fone/tasks/id%2Ftwo"));return reply(200,"{\"id\":\"id/two\",\"title\":\"Changed\",\"status\":\"notStarted\",\"@odata.etag\":\"W/\\\"v2\\\"\",\"futureProperty\":true}");});
        var accepted=writer.update("token","list/one","id/two","W/\"v1\"",object().put("summary","Changed"));assertEquals("W/\"v2\"",accepted.source().path("remoteEtag").asText());assertTrue(accepted.original().path("futureProperty").asBoolean());
    }
    @Test void dateTimesRetainWallTimeAndProviderZoneAndExplicitClears(){
        var fields=object();fields.set("start",object().put("kind","DATE_TIME").put("date","2026-10-25").put("time","02:30:00.123").put("timeZone","W. Europe Standard Time"));fields.putNull("due");
        var body=MicrosoftTasksWriter.body(fields);assertEquals("2026-10-25T02:30:00.123",body.path("startDateTime").path("dateTime").asText());assertEquals("W. Europe Standard Time",body.path("startDateTime").path("timeZone").asText());assertTrue(body.path("dueDateTime").isNull());assertFalse(body.has("due"));
        assertThrows(GoogleTasksWriter.Unsupported.class,()->MicrosoftTasksWriter.body(object().set("due",object().put("kind","DATE").put("date","2026-10-25"))));
    }
    @Test void onlyHtmlNotesAndSupportedPrioritiesAreRepresentableAndCategoriesStaySeparate(){
        var fields=object().put("priority","HIGH");fields.putObject("body").put("format","HTML").put("content","<p>Notes</p>");fields.putObject("metadata").putArray("categories").add("Work");var body=MicrosoftTasksWriter.body(fields);
        assertEquals("html",body.path("body").path("contentType").asText());assertEquals("<p>Notes</p>",body.path("body").path("content").asText());assertEquals("high",body.path("importance").asText());assertEquals(array().add("Work"),body.path("categories"));assertFalse(body.has("tags"));
        assertThrows(GoogleTasksWriter.Unsupported.class,()->MicrosoftTasksWriter.body(object().set("body",object().put("format","TEXT").put("content","Preserve text"))));
        assertThrows(GoogleTasksWriter.Unsupported.class,()->MicrosoftTasksWriter.body(object().put("priority","UNSPECIFIED")));
        assertTrue(MicrosoftTasksWriter.body(object().putNull("body")).path("body").path("content").asText().isEmpty());
    }
    @Test void completionReopeningAndDeletionKeepTheirPreconditions(){
        var completed=MicrosoftTasksWriter.body(object().put("status","COMPLETED").put("completedAt","2026-11-15T10:00:00Z"));assertEquals("completed",completed.path("status").asText());assertEquals("UTC",completed.path("completedDateTime").path("timeZone").asText());
        var reopened=MicrosoftTasksWriter.body(object().put("status","NOT_STARTED").putNull("completedAt"));assertTrue(reopened.path("completedDateTime").isNull());
        writer(request->{assertEquals("DELETE",request.method());assertEquals("W/\"v1\"",request.headers().firstValue("If-Match").orElseThrow());return reply(204,"");}).delete("token","list","task","W/\"v1\"");
        for(String revision:Arrays.asList(null,"","*","W/*","W/\"a\", \"b\"","bad\r\nheader"))assertThrows(TaskProviderHttp.Failure.class,()->MicrosoftTasksWriter.revision(revision));
    }
    @Test void aLostCreationResponseIsUnknownAndNeverRetriedByTheAdapter(){
        var count=new java.util.concurrent.atomic.AtomicInteger();var writer=writer(request->{count.incrementAndGet();throw new IOException("lost");});var failure=assertThrows(TaskProviderHttp.Failure.class,()->writer.create("token","list",object().put("summary","Native")));assertTrue(failure.uncertain);assertEquals(1,count.get());
    }
}
