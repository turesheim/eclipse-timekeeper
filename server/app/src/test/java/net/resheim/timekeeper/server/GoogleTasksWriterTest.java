package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;

class GoogleTasksWriterTest {
    private GoogleTasksWriter writer(TaskProviderHttp.Transport transport){return new GoogleTasksWriter(new TaskProviderHttp(Set.of("tasks.googleapis.com"),transport));}
    private GoogleTasksWriter offline(){return writer(request->{fail("Rejected writes must not call Google");return null;});}
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
    @Test void patchUsesProviderRevisionAndLeavesUnchangedProviderFieldsOutOfTheRequest(){
        var writer=writer(request->{
            assertEquals("PATCH",request.method());assertEquals("\"google-v1\"",request.headers().firstValue("If-Match").orElseThrow());assertEquals("Bearer private-access",request.headers().firstValue("Authorization").orElseThrow());assertTrue(request.headers().firstValue("Cookie").isEmpty());
            assertTrue(request.uri().toString().contains("list%20%2F%3F/tasks/task%2F%2B"));assertEquals(parse("{\"title\":\"Reviewed title\"}"),parse(body(request)));
            return reply(200,"{\"id\":\"task/+\",\"title\":\"Reviewed title\",\"status\":\"needsAction\",\"etag\":\"\\\"google-v2\\\"\",\"futureField\":{\"keep\":true}}");
        });var result=writer.update("private-access","list /?","task/+","\"google-v1\"",object().put("summary","Reviewed title"));assertTrue(result.original().path("futureField").path("keep").asBoolean());assertEquals("\"google-v2\"",result.source().path("remoteEtag").asText());
    }
    @Test void schedulesStayCivilDaysAndCompletionOrReopenKeepsItsPairedFields(){
        var input=object().put("scheduledDate","2026-11-10").put("status","COMPLETED").put("completedAt","2026-11-09T23:00:00+01:00");
        assertEquals(parse("{\"due\":\"2026-11-10T00:00:00Z\",\"status\":\"completed\",\"completed\":\"2026-11-09T22:00:00Z\"}"),GoogleTasksWriter.body(input));
        var cleared=GoogleTasksWriter.body(object().putNull("scheduledDate").put("status","NOT_STARTED").putNull("completedAt"));assertTrue(cleared.path("due").isNull());assertEquals("needsAction",cleared.path("status").asText());assertTrue(cleared.path("completed").isNull());
        assertFalse(GoogleTasksWriter.body(object().put("status","COMPLETED").putNull("completedAt")).has("completed"));
    }
    @Test void unsupportedValuesAreRetainedForReviewInsteadOfCoercedOrTruncated(){
        var values=new ArrayList<JsonNode>();values.add(object().put("status","WAITING"));values.add(object().put("completedAt","2026-11-09T22:00:00Z"));values.add(object().put("summary","x".repeat(1025)));values.add(object().put("due","2026-11-10T08:30:00Z"));values.add(object().putNull("parentTaskId"));
        values.add(object().set("body",object().put("format","HTML").put("content","<p>Keep HTML locally</p>")));values.add(object().set("body",object().put("format","TEXT").put("content","x".repeat(8193))));
        for(var value:values){String before=value.toString();assertThrows(GoogleTasksWriter.Unsupported.class,()->offline().update("token","list","task","revision",value));assertEquals(before,value.toString());}
        var emoji=object().set("body",object().put("format","TEXT").put("content","😀".repeat(8192)));assertEquals(8192,GoogleTasksWriter.body(emoji).path("notes").asText().codePointCount(0,GoogleTasksWriter.body(emoji).path("notes").asText().length()));
    }
    @Test void updatesAndReviewedDeletesRequireASpecificRevision(){
        for(String revision:Arrays.asList(null,"","*"," * ","\"one\", \"two\"","W/\"weak\"","bad\r\nheader")){
            assertThrows(TaskProviderHttp.Failure.class,()->offline().update("token","list","task",revision,object().put("summary","Title")));
            assertThrows(TaskProviderHttp.Failure.class,()->offline().delete("token","list","task",revision));
        }
        var writer=writer(request->{assertEquals("DELETE",request.method());assertEquals("etag-v1",request.headers().firstValue("If-Match").orElseThrow());return reply(204,"");});writer.delete("token","list","task","etag-v1");
    }
    @Test void lostOrUnusableSuccessfulCreateRepliesRemainUnknownAndAreNotRetried(){
        var requests=new java.util.concurrent.atomic.AtomicInteger();var lost=writer(request->{requests.incrementAndGet();throw new IOException("private transport detail");});
        var failure=assertThrows(TaskProviderHttp.Failure.class,()->lost.create("token","list",object().put("summary","Create once")));assertTrue(failure.uncertain);assertEquals(1,requests.get());assertFalse(failure.toString().contains("private"));
        var invalid=writer(request->reply(200,"{\"id\":\"created\",\"title\":\"Create once\",\"status\":\"needsAction\"}"));assertTrue(assertThrows(TaskProviderHttp.Failure.class,()->invalid.create("token","list",object().put("summary","Create once"))).uncertain);
        var valid=writer(request->{assertEquals("POST",request.method());assertTrue(request.headers().firstValue("If-Match").isEmpty());return reply(200,"{\"id\":\"created\",\"title\":\"Create once\",\"status\":\"needsAction\",\"etag\":\"new-revision\"}");});assertEquals("created",valid.create("token","list",object().put("summary","Create once")).id());
    }
    @Test void providerConflictsAreDefiniteAndGetFailuresDoNotClaimAWrite(){
        var conflict=writer(request->reply(412,"{\"private\":\"provider body\"}"));var failure=assertThrows(TaskProviderHttp.Failure.class,()->conflict.update("token","list","task","old",object().put("summary","Changed")));assertEquals("TASK_PROVIDER_CHANGED",failure.getMessage());assertFalse(failure.uncertain);
        var read=writer(request->{assertEquals("GET",request.method());throw new IOException("private");});assertFalse(assertThrows(TaskProviderHttp.Failure.class,()->read.get("token","list","task")).uncertain);
    }
}
