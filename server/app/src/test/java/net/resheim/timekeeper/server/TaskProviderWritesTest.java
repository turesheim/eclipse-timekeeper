package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(TaskProviderWritesTest.Profile.class)
class TaskProviderWritesTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:task_provider_writes","timekeeper.tasks.automatic","false");}
    }
    @Inject ServerStore store;
    @Inject CommandReceipts receipts;
    @Inject ContractInputs inputs;
    record Fixture(String owner,UUID connection,UUID list,UUID task){}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    private TaskProviderWrites writes(){return new TaskProviderWrites(store,receipts,inputs);}
    private <T>T tx(Fixture f,Supplier<T> work){return store.transaction(f.owner(),work);}
    private TaskCloudProvider.RemoteTask remote(String title,String revision){return new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(raw(title,revision));}
    private ObjectNode raw(String title,String revision){return object().put("id","remote-task").put("title",title).put("status","needsAction").put("position","001").put("notes","Original notes").put("due","2026-11-12T00:00:00Z").put("etag",revision);}
    private Fixture fixture(){return fixture(true,false);}
    private Fixture fixture(boolean writable,boolean assigned){
        String owner="write-owner-"+UUID.randomUUID();return store.transaction(owner,()->{
            var settings=new TaskProviderOAuth.Settings("client","private-secret","https://example.invalid/web/task-provider-callback.html");
            var started=connections().begin(TaskCloudProvider.Provider.GOOGLE,settings,"browser",writable);
            String state=Arrays.stream(URI.create(started.path("authorizationUrl").asText()).getRawQuery().split("&")).filter(value->value.startsWith("state=")).map(value->URLDecoder.decode(value.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
            var connection=connections().connect(connections().consume(state,"browser"),settings,new TaskCloudProvider.Account("account","Google Tasks"),
                    new TaskProviderOAuth.Tokens("private-access","private-refresh",store.now().plusSeconds(3600),Set.of("openid","https://www.googleapis.com/auth/tasks"+(writable?"":".readonly"))),
                    List.of(new TaskCloudProvider.RemoteList("remote-list","Work","STANDARD",false,true,parse("{\"id\":\"remote-list\",\"title\":\"Work\"}"))));
            UUID id=uuid(connection.get("id"));connections().select(id,List.of("remote-list"),etag(connection));UUID list=uuid(store.list("TASK_LIST").getFirst().get("id"));
            var original=raw("Original","\"g1\"");if(assigned)original.putObject("assignmentInfo").put("surfaceType","DOCUMENT").put("linkToTask","https://docs.google.com/document/d/source/edit");
            var claim=connections().claim(id,false);var scans=new TaskProviderScans(store,receipts,inputs);var scan=scans.begin(claim);
            scans.acknowledge(claim,scan,new TaskCloudProvider.Page<>(List.of(new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(original)),null,null));
            var preview=scans.review(id,list);scans.approve(id,list,etag(preview));return new Fixture(owner,id,list,uuid(store.list("TASK").getFirst().get("id")));
        });
    }
    private ObjectNode task(Fixture f){return tx(f,()->((ObjectNode)store.find("TASK",f.task()).orElseThrow()).deepCopy());}
    private void rename(Fixture f,String title){tx(f,()->{var task=(Task)decode("TASK",store.find("TASK",f.task()).orElseThrow());store.service().updateTask(new UpdateTask(task.id(),task.version(),task.projectId(),task.parentTaskId(),title,task.url()));return null;});}
    private ObjectNode preview(Fixture f,TaskProviderWrites.Action action){return tx(f,()->writes().prepare(f.connection(),f.list(),f.task(),action));}
    private ObjectNode confirm(Fixture f,ObjectNode preview){return tx(f,()->writes().confirm(f.connection(),uuid(preview.get("id")),etag(preview)));}
    private TaskProviderConnections.Claim claim(Fixture f){return tx(f,()->connections().claim(f.connection(),false));}
    private TaskProviderConnections.Claim reclaim(Fixture f){tx(f,()->{var auth=connections().required(f.connection());auth.putNull("lease").putNull("leaseUntil").put("nextRefresh",store.now().toString());connections().save(TaskProviderConnections.AUTH,auth);return null;});return claim(f);}
    private ObjectNode job(Fixture f,UUID id){return tx(f,()->writes().get(f.connection(),id));}
    private String baseline(Fixture f){return task(f).path("externalReferences").get(0).path("baseline").path("fields").path("summary").asText();}
    private TaskProviderHttp.Reply reply(int status,JsonNode body){return new TaskProviderHttp.Reply(status,Map.of(),body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8));}
    private GoogleTasksWriter writer(TaskProviderHttp.Transport transport){return new GoogleTasksWriter(new TaskProviderHttp(Set.of("tasks.googleapis.com"),request->{assertThrows(IllegalStateException.class,()->store.owner());return transport.send(request);}));}
    private static JsonNode body(HttpRequest request){
        var bytes=new java.io.ByteArrayOutputStream();var finished=new CompletableFuture<Void>();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}public void onNext(ByteBuffer buffer){byte[] value=new byte[buffer.remaining()];buffer.get(value);bytes.writeBytes(value);}public void onComplete(){finished.complete(null);}public void onError(Throwable error){finished.completeExceptionally(error);}
        });finished.join();return parse(bytes.toString(StandardCharsets.UTF_8));
    }
    @Test void importOnlyAndAnotherOwnerCannotPrepareOrSendGoogleWrites(){
        var f=fixture(false,false);rename(f,"Changed locally");assertEquals(409,assertThrows(ApiFailure.class,()->preview(f,TaskProviderWrites.Action.UPDATE)).status);
        var claim=claim(f);var calls=new AtomicInteger();writes().drain(claim,"private-access",writer(request->{calls.incrementAndGet();throw new AssertionError("Import-only cannot contact writer");}));assertEquals(0,calls.get());assertEquals("Original",baseline(f));
        assertEquals(404,assertThrows(ApiFailure.class,()->store.transaction("other-"+UUID.randomUUID(),()->writes().prepare(f.connection(),f.list(),f.task(),TaskProviderWrites.Action.UPDATE))).status);
    }
    @Test void reviewedDeltaHasDurableIntentBeforeHttpAndOnlyAnAcknowledgedResultAdvancesTheBaseline(){
        var f=fixture();rename(f,"Changed");tx(f,()->{var task=(Task)decode("TASK",store.find("TASK",f.task()).orElseThrow());store.service().updateTaskBody(new UpdateTaskBody(task.id(),task.version(),new TaskBody(TaskBodyFormat.HTML,"<strong>Local rich notes</strong>")));return null;});
        var preview=preview(f,TaskProviderWrites.Action.UPDATE);assertEquals(object().put("summary","Changed"),preview.path("writeFields"));assertTrue(preview.path("warnings").toString().contains("TEXT_ONLY"));
        for(String secret:List.of("private-access","private-refresh","private-secret","generation","observation","lease"))assertFalse(preview.toString().contains(secret));
        var queued=confirm(f,preview);UUID id=uuid(queued.get("id"));var claim=claim(f);var calls=new AtomicInteger();var cursor=tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst());
        writes().drain(claim,"private-access",writer(request->{calls.incrementAndGet();
            if(request.method().equals("GET"))return reply(200,raw("Original","\"g1\""));
            assertEquals("PATCH",request.method());assertEquals("\"g1\"",request.headers().firstValue("If-Match").orElseThrow());assertEquals(object().put("title","Changed"),body(request));
            assertEquals("SENDING",job(f,id).path("state").asText());assertEquals("Original",baseline(f));var result=raw("Changed","\"g2\"");result.putObject("futureField").put("retained",true);return reply(200,result);
        }));
        assertEquals(2,calls.get());assertEquals("DONE",job(f,id).path("state").asText());assertEquals("Changed",baseline(f));var result=task(f);
        assertEquals("<strong>Local rich notes</strong>",result.path("body").path("content").asText());assertEquals("2026-11-12",result.path("scheduledDate").asText());assertEquals("\"g2\"",result.path("externalReferences").get(0).path("source").path("remoteEtag").asText());
        assertTrue(result.path("externalReferences").get(0).path("providerData").path("payload").path("observation").path("futureField").path("retained").asBoolean());assertEquals(cursor,tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst()));
    }
    @Test void lostMutationReplyIsUnknownAndIsNeverRetriedAutomatically(){
        var f=fixture();rename(f,"Changed");UUID id=uuid(confirm(f,preview(f,TaskProviderWrites.Action.UPDATE)).get("id"));var claim=claim(f);var mutations=new AtomicInteger();
        var writer=writer(request->{if(request.method().equals("GET"))return reply(200,raw("Original","\"g1\""));mutations.incrementAndGet();throw new IOException("Lost reply containing private details");});
        assertTrue(assertThrows(TaskProviderHttp.Failure.class,()->writes().drain(claim,"private-access",writer)).uncertain);assertEquals("UNKNOWN",job(f,id).path("state").asText());assertEquals("Original",baseline(f));
        writes().drain(claim,"private-access",writer);var resumed=reclaim(f);writes().drain(resumed,"private-access",writer);assertEquals(1,mutations.get());assertEquals("TASK_PROVIDER_OUTCOME_UNKNOWN",job(f,id).path("lastError").asText());
    }
    private UUID unknown(Fixture f){
        UUID id=uuid(confirm(f,preview(f,TaskProviderWrites.Action.UPDATE)).get("id"));var claim=claim(f);
        assertThrows(TaskProviderHttp.Failure.class,()->writes().drain(claim,"private-access",writer(request->{if(request.method().equals("GET"))return reply(200,raw("Original","\"g1\""));throw new IOException("Lost result");})));return id;
    }
    @Test void checkingAnUncertainUpdateConfirmsItsCurrentGoogleValuesWithoutResendingIt(){
        var f=fixture();rename(f,"Changed");UUID id=unknown(f);var before=job(f,id);tx(f,()->writes().check(f.connection(),id,etag(before)));var claim=reclaim(f);var calls=new AtomicInteger();
        writes().drain(claim,"private-access",writer(request->{assertEquals("GET",request.method());calls.incrementAndGet();return reply(200,raw("Changed","\"g2\""));}));
        assertEquals(1,calls.get());assertEquals("DONE",job(f,id).path("state").asText());assertEquals("Changed",baseline(f));
    }
    @Test void aReadonlyReconnectCanCheckAnEarlierUncertainWriteWithoutRestoringWritePermission(){
        var f=fixture();rename(f,"Changed");UUID id=unknown(f);
        tx(f,()->{
            var settings=new TaskProviderOAuth.Settings("client","private-secret","https://example.invalid/web/task-provider-callback.html");var begun=connections().begin(TaskCloudProvider.Provider.GOOGLE,settings,"browser",false);
            String state=Arrays.stream(URI.create(begun.path("authorizationUrl").asText()).getRawQuery().split("&")).filter(value->value.startsWith("state=")).map(value->URLDecoder.decode(value.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
            connections().connect(connections().consume(state,"browser"),settings,new TaskCloudProvider.Account("account","Google Tasks"),new TaskProviderOAuth.Tokens("readonly-access","private-refresh",store.now().plusSeconds(3600),Set.of("openid","https://www.googleapis.com/auth/tasks.readonly")),List.of(new TaskCloudProvider.RemoteList("remote-list","Work","STANDARD",false,true,parse("{\"id\":\"remote-list\",\"title\":\"Work\"}"))));return null;
        });
        var before=job(f,id);tx(f,()->writes().check(f.connection(),id,etag(before)));var claim=reclaim(f);assertFalse(claim.writable());
        writes().drain(claim,"readonly-access",writer(request->{assertEquals("GET",request.method());return reply(200,raw("Changed","\"g2\""));}));
        assertEquals("DONE",job(f,id).path("state").asText());assertEquals("Changed",baseline(f));assertThrows(ApiFailure.class,()->preview(f,TaskProviderWrites.Action.UPDATE));
    }
    @Test void resolvingAConflictingTitlePreservesUnrelatedLocalRichNotesAndScheduling(){
        var f=fixture();rename(f,"Changed");
        tx(f,()->{var task=(Task)decode("TASK",store.find("TASK",f.task()).orElseThrow());task=store.service().updateTaskBody(new UpdateTaskBody(task.id(),task.version(),new TaskBody(TaskBodyFormat.HTML,"<strong>Local notes</strong>")));store.service().updateTaskPlanning(new UpdateTaskPlanning(task.id(),task.version(),new TaskPlanning(TaskStatus.NOT_STARTED,Optional.of(java.time.LocalDate.parse("2026-12-01")),Optional.empty())));return null;});
        UUID id=unknown(f);var before=job(f,id);tx(f,()->writes().check(f.connection(),id,etag(before)));var claim=reclaim(f);
        writes().drain(claim,"private-access",writer(request->{assertEquals("GET",request.method());return reply(200,raw("Changed at Google","\"g3\""));}));
        var comparison=job(f,id);assertEquals("UNKNOWN",comparison.path("state").asText());assertTrue(comparison.path("canResolve").asBoolean());assertEquals("Changed at Google",comparison.path("remoteFields").path("summary").asText());assertEquals("Changed",comparison.path("localFields").path("summary").asText());
        tx(f,()->writes().resolve(f.connection(),id,etag(comparison),comparison.path("currentTaskEtag").asText(),true));
        assertEquals("Changed at Google",task(f).path("summary").asText());assertEquals("<strong>Local notes</strong>",task(f).path("body").path("content").asText());assertEquals("2026-12-01",task(f).path("scheduledDate").asText());assertEquals("RECONCILED",job(f,id).path("state").asText());
    }
    @Test void keepingTheLocalTitleStillAcceptsIndependentGoogleCompletionAndRejectsAStaleTaskDecision(){
        var f=fixture();rename(f,"Changed");UUID id=unknown(f);var before=job(f,id);tx(f,()->writes().check(f.connection(),id,etag(before)));var claim=reclaim(f);
        writes().drain(claim,"private-access",writer(request->{assertEquals("GET",request.method());return reply(200,raw("Provider title","\"g3\"").put("status","completed").put("completed","2026-11-10T00:00:00Z"));}));
        var old=job(f,id);rename(f,"New local title");assertEquals(412,assertThrows(ApiFailure.class,()->tx(f,()->writes().resolve(f.connection(),id,etag(old),old.path("currentTaskEtag").asText(),false))).status);
        var current=job(f,id);tx(f,()->writes().resolve(f.connection(),id,etag(current),current.path("currentTaskEtag").asText(),false));
        assertEquals("New local title",task(f).path("summary").asText());assertEquals("COMPLETED",task(f).path("status").asText());assertEquals("2026-11-10T00:00:00Z",task(f).path("completedAt").asText());assertEquals("Provider title",baseline(f));
    }
    @Test void aCrashedSendingAttemptBecomesUnknownInsteadOfBeingSentAgain(){
        var f=fixture();rename(f,"Changed");UUID id=uuid(confirm(f,preview(f,TaskProviderWrites.Action.UPDATE)).get("id"));var first=claim(f);
        tx(f,()->writes().next(first));tx(f,()->writes().sending(first,id,remote("Original","\"g1\"")));var next=reclaim(f);
        writes().drain(next,"private-access",writer(request->{throw new AssertionError("A possibly sent request cannot be retried");}));assertEquals("UNKNOWN",job(f,id).path("state").asText());assertEquals("Original",baseline(f));
    }
    @Test void durableAcknowledgementSurvivesRestartAndReconcilesWithoutAnotherProviderRequest(){
        var f=fixture();rename(f,"Changed");UUID id=uuid(confirm(f,preview(f,TaskProviderWrites.Action.UPDATE)).get("id"));var first=claim(f);
        tx(f,()->writes().next(first));var sending=tx(f,()->writes().sending(first,id,remote("Original","\"g1\"")));assertTrue(tx(f,()->writes().acknowledge(id,sending.path("attempt").asText(),remote("Changed","\"g2\""))));
        assertEquals("ACK",job(f,id).path("state").asText());assertEquals("Original",baseline(f));var resumed=reclaim(f);
        writes().drain(resumed,"private-access",writer(request->{throw new AssertionError("Known result needs no second request");}));assertEquals("DONE",job(f,id).path("state").asText());assertEquals("Changed",baseline(f));
    }
    @Test void changedLocalTaskOrProviderRevisionPreventsTheMutation(){
        var f=fixture();rename(f,"Proposed");var preview=preview(f,TaskProviderWrites.Action.UPDATE);rename(f,"New local title");assertEquals(412,assertThrows(ApiFailure.class,()->confirm(f,preview)).status);
        UUID id=uuid(confirm(f,preview(f,TaskProviderWrites.Action.UPDATE)).get("id"));var claim=claim(f);var mutations=new AtomicInteger();
        writes().drain(claim,"private-access",writer(request->{if(!request.method().equals("GET"))mutations.incrementAndGet();return reply(200,raw("Changed at Google","\"g2\""));}));
        assertEquals(0,mutations.get());assertEquals("CONFLICT",job(f,id).path("state").asText());assertEquals("New local title",task(f).path("summary").asText());assertEquals("Original",baseline(f));
    }
    @Test void concurrentLocalEditDuringTheProviderCallKeepsBothTheDraftAndItsAcknowledgedRemoteResult(){
        var f=fixture();rename(f,"Proposed");UUID id=uuid(confirm(f,preview(f,TaskProviderWrites.Action.UPDATE)).get("id"));var claim=claim(f);
        writes().drain(claim,"private-access",writer(request->{if(request.method().equals("GET"))return reply(200,raw("Original","\"g1\""));rename(f,"Concurrent local edit");return reply(200,raw("Proposed","\"g2\""));}));
        assertEquals("ACK_REVIEW",job(f,id).path("state").asText());assertEquals("Concurrent local edit",task(f).path("summary").asText());assertEquals("Original",baseline(f));
        assertEquals("Proposed",tx(f,()->store.find(TaskProviderWrites.KIND,id).orElseThrow()).path("result").path("title").asText());
    }
    @Test void pauseAfterConfirmationFencesTheQueuedRequestBeforeItCanContactGoogle(){
        var f=fixture();rename(f,"Changed");UUID id=uuid(confirm(f,preview(f,TaskProviderWrites.Action.UPDATE)).get("id"));var claim=claim(f);
        tx(f,()->{var current=connections().visible(connections().required(f.connection()));connections().pause(f.connection(),true,etag(current));return null;});
        writes().drain(claim,"private-access",writer(request->{throw new AssertionError("Paused connection cannot make requests");}));assertEquals("QUEUED",job(f,id).path("state").asText());assertEquals("Original",baseline(f));
    }
    @Test void deletionRequiresExplicitReviewAndKeepsTheOrdinaryTaskWhileAssignedDeletesStayAtTheirSource(){
        var f=fixture();var preview=preview(f,TaskProviderWrites.Action.DELETE);assertTrue(preview.path("canConfirm").asBoolean());UUID id=uuid(confirm(f,preview).get("id"));var claim=claim(f);var deletes=new AtomicInteger();
        writes().drain(claim,"private-access",writer(request->{if(request.method().equals("GET"))return reply(200,raw("Original","\"g1\""));assertEquals("DELETE",request.method());assertEquals("\"g1\"",request.headers().firstValue("If-Match").orElseThrow());deletes.incrementAndGet();return reply(204,null);}));
        assertEquals(1,deletes.get());assertEquals("DONE",job(f,id).path("state").asText());assertEquals("Original",task(f).path("summary").asText());
        var assigned=fixture(true,true);var blocked=preview(assigned,TaskProviderWrites.Action.DELETE);assertFalse(blocked.path("canConfirm").asBoolean());assertTrue(blocked.path("warnings").toString().contains("DELETE_AT_ASSIGNMENT_SOURCE"));assertThrows(ApiFailure.class,()->confirm(assigned,blocked));
    }
    @Test void anUncertainDeleteNeedsAFreshExplicitTombstoneAndNeverInfersDeletionFromAbsence(){
        var f=fixture();UUID id=uuid(confirm(f,preview(f,TaskProviderWrites.Action.DELETE)).get("id"));var first=claim(f);
        assertThrows(TaskProviderHttp.Failure.class,()->writes().drain(first,"private-access",writer(request->{if(request.method().equals("GET"))return reply(200,raw("Original","\"g1\""));throw new IOException("Lost deletion reply");})));
        var before=job(f,id);tx(f,()->writes().check(f.connection(),id,etag(before)));var resumed=reclaim(f);
        tx(f,()->{var scans=new TaskProviderScans(store,receipts,inputs);var stage=scans.begin(resumed);scans.acknowledge(resumed,stage,new TaskCloudProvider.Page<>(List.of(),null,null));return null;});
        assertThrows(TaskProviderHttp.Failure.class,()->writes().drain(resumed,"private-access",writer(request->{assertEquals("GET",request.method());return reply(404,object());})));assertEquals("UNKNOWN",job(f,id).path("state").asText());
        var current=job(f,id);tx(f,()->writes().check(f.connection(),id,etag(current)));var fresh=reclaim(f);
        tx(f,()->{var scans=new TaskProviderScans(store,receipts,inputs);var old=((ObjectNode)store.find(TaskProviderScans.SCAN,f.list()).orElseThrow()).deepCopy();old.put("startedAt",store.now().minusSeconds(901).toString());connections().save(TaskProviderScans.SCAN,old);
            var stage=scans.begin(fresh);var remote=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(object().put("id","remote-task").put("deleted",true));scans.acknowledge(fresh,stage,new TaskCloudProvider.Page<>(List.of(remote),null,null));return null;});
        writes().drain(fresh,"private-access",writer(request->{throw new AssertionError("The acknowledged deletion record needs no mutation or guessed 404");}));assertEquals("DONE",job(f,id).path("state").asText());assertEquals("Original",task(f).path("summary").asText());
    }
}
