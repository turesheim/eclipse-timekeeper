package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import net.resheim.eclipse.timekeeper.domain.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(TaskProviderScansTest.Profile.class)
class TaskProviderScansTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){var config=TaskProviderTestDatabase.settings("task_provider_scans");config.put("timekeeper.tasks.automatic","false");return config;}
    }
    @Inject ServerStore store;
    @Inject CommandReceipts receipts;
    @Inject ContractInputs inputs;
    record Fixture(String owner,UUID connection,UUID list,UUID project){}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    private TaskProviderScans scans(){return new TaskProviderScans(store,receipts,inputs);}
    private <T>T tx(Fixture f,Supplier<T> work){return store.transaction(f.owner(),work);}
    private Fixture fixture(){
        String owner="scan-owner-"+UUID.randomUUID();return store.transaction(owner,()->{
            var settings=new TaskProviderOAuth.Settings("client","private-secret","https://example.invalid/web/task-provider-callback.html");
            var started=connections().begin(TaskCloudProvider.Provider.GOOGLE,settings,"browser");
            String state=Arrays.stream(URI.create(started.path("authorizationUrl").asText()).getRawQuery().split("&")).filter(value->value.startsWith("state=")).map(value->URLDecoder.decode(value.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
            var connection=connections().connect(connections().consume(state,"browser"),settings,new TaskCloudProvider.Account("account","Google Tasks"),
                    new TaskProviderOAuth.Tokens("private-access","private-refresh",store.now().plusSeconds(3600),Set.of("openid","https://www.googleapis.com/auth/tasks.readonly")),
                    List.of(new TaskCloudProvider.RemoteList("remote-list","Work","STANDARD",false,true,parse("{\"id\":\"remote-list\",\"title\":\"Work\"}"))));
            UUID project=UUID.randomUUID();store.save("PROJECT",new Project(new ProjectId(project),"Imported",0));
            connections().select(uuid(connection.get("id")),List.of("remote-list"),object().put("remote-list",project.toString()),etag(connection));
            return new Fixture(owner,uuid(connection.get("id")),uuid(store.list("TASK_LIST").getFirst().get("id")),project);
        });
    }
    private TaskCloudProvider.RemoteTask task(String id,String parent,String position,String title){
        var value=object().put("id",id).put("title",title).put("status","needsAction").put("etag","\"google-"+id+"\"").put("position",position);
        if(parent!=null)value.put("parent",parent);return new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(value);
    }
    private TaskCloudProvider.Page<TaskCloudProvider.RemoteTask> page(String next,TaskCloudProvider.RemoteTask...tasks){return new TaskCloudProvider.Page<>(List.of(tasks),next,null);}
    private TaskProviderConnections.Claim claim(Fixture f){return tx(f,()->connections().claim(f.connection(),false));}
    private TaskProviderConnections.Claim reclaim(Fixture f){
        tx(f,()->{var value=connections().required(f.connection());value.putNull("lease").putNull("leaseUntil").put("nextRefresh",store.now().toString());connections().save(TaskProviderConnections.AUTH,value);return null;});return claim(f);
    }
    private JsonNode stored(Fixture f,String id){return tx(f,()->store.byReference(new ExternalTaskReferenceKey("google",TaskCloudPull.repository("account","remote-list"),id)).orElseThrow());}
    @Test void savedPagesSurviveInterruptionAndFirstImportRequiresAnOwnedConditionalPreview(){
        var f=fixture();var claim=claim(f);var start=tx(f,()->scans().begin(claim));
        var first=tx(f,()->scans().acknowledge(claim,start,page("opaque-next",task("child","parent","001","Child"))));
        assertEquals("opaque-next",first.path("next").asText());assertTrue(tx(f,()->store.list("TASK").isEmpty()));assertTrue(tx(f,()->store.list("TASK_SYNC_CURSOR").isEmpty()));
        var resumed=reclaim(f);var same=tx(f,()->new TaskProviderScans(store,receipts,inputs).begin(resumed));assertEquals(etag(first),etag(same));
        var ready=tx(f,()->scans().acknowledge(resumed,same,page(null,task("parent",null,"002","Parent"))));assertEquals("READY",ready.path("state").asText());
        assertTrue(tx(f,()->scans().reconcile(resumed)).path("awaitingImport").asBoolean());assertTrue(tx(f,()->store.list("TASK").isEmpty()));
        var preview=tx(f,()->scans().review(f.connection(),f.list()));inputs.validate("TaskProviderImportPreview",preview);assertEquals(2,preview.path("createCount").asInt());assertEquals(f.project().toString(),preview.path("projectId").asText());
        assertThrows(ApiFailure.class,()->tx(f,()->scans().approve(f.connection(),f.list(),"\"stale\"")));
        assertThrows(ApiFailure.class,()->store.transaction("other-"+UUID.randomUUID(),()->scans().review(f.connection(),f.list())));
        var result=tx(f,()->scans().approve(f.connection(),f.list(),etag(preview)));assertTrue(result.path("complete").asBoolean());assertEquals(2,result.path("created").asInt());
        assertEquals(f.project().toString(),stored(f,"parent").path("projectId").asText());assertEquals(f.project().toString(),stored(f,"child").path("projectId").asText());assertEquals(stored(f,"parent").path("id"),stored(f,"child").path("parentTaskId"));
        assertThrows(ApiFailure.class,()->tx(f,()->scans().approve(f.connection(),f.list(),etag(preview))));assertEquals(2,tx(f,()->store.list("TASK").size()));
        assertNull(tx(f,()->connections().active(resumed)));
    }
    @Test void duplicatePagesAndCyclesRollbackWithoutAdvancingTheSavedCursor(){
        var f=fixture();var claim=claim(f);var start=tx(f,()->scans().begin(claim));
        var first=tx(f,()->scans().acknowledge(claim,start,page("next",task("one",null,"001","One"))));
        assertThrows(TaskProviderHttp.Failure.class,()->tx(f,()->scans().acknowledge(claim,first,page(null,task("one",null,"001","Duplicate")))));
        assertThrows(TaskProviderHttp.Failure.class,()->tx(f,()->scans().acknowledge(claim,first,page("next",task("two",null,"002","Two")))));
        assertEquals(etag(first),etag(tx(f,()->scans().begin(claim))));assertEquals(1,tx(f,()->store.list(TaskProviderScans.PAGE).size()));assertTrue(tx(f,()->store.list("TASK").isEmpty()));
    }
    @Test void changedSelectionFencesPagesAndPreviewsAndUnknownProjectOwnershipRollsBack(){
        var f=fixture();var claim=claim(f);var start=tx(f,()->scans().begin(claim));
        var before=tx(f,()->connections().visible(connections().required(f.connection())));
        assertThrows(ApiFailure.class,()->tx(f,()->connections().select(f.connection(),List.of("remote-list"),object().put("remote-list",UUID.randomUUID().toString()),etag(before))));
        tx(f,()->connections().select(f.connection(),List.of(),etag(before)));
        assertNull(tx(f,()->scans().acknowledge(claim,start,page(null,task("one",null,"001","One")))));
        assertThrows(ApiFailure.class,()->tx(f,()->scans().review(f.connection(),f.list())));assertTrue(tx(f,()->store.list(TaskProviderScans.PAGE).isEmpty()));
    }
    @Test void pauseFencesQueuedWorkButResumeRetainsAcknowledgedPages(){
        var f=fixture();var claim=claim(f);var start=tx(f,()->scans().begin(claim));
        var saved=tx(f,()->scans().acknowledge(claim,start,page("next",task("one",null,"001","One"))));
        var before=tx(f,()->connections().visible(connections().required(f.connection())));
        var paused=tx(f,()->connections().pause(f.connection(),true,etag(before)));assertTrue(paused.path("paused").asBoolean());
        assertNull(tx(f,()->connections().active(claim)));assertNull(claim(f));
        assertNull(tx(f,()->scans().acknowledge(claim,saved,page(null,task("two",null,"002","Two")))));
        tx(f,()->connections().pause(f.connection(),false,etag(paused)));var resumed=claim(f);assertNotNull(resumed);
        assertEquals(etag(saved),etag(tx(f,()->scans().begin(resumed))));
    }
    @Test void deselectingAListDiscardsPrivatePagesButKeepsImportedTasksAndTheirCursor(){
        var f=fixture();imported(f,task("one",null,"001","One"));var before=stored(f,"one");var cursor=tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst());
        var claim=reclaim(f);var start=tx(f,()->scans().begin(claim));tx(f,()->scans().acknowledge(claim,start,page("next",task("one",null,"001","Changed provider title"))));
        assertFalse(tx(f,()->store.list(TaskProviderScans.PAGE).isEmpty()));
        tx(f,()->{var current=connections().visible(connections().required(f.connection()));connections().select(f.connection(),List.of(),etag(current));return null;});
        assertTrue(tx(f,()->store.list(TaskProviderScans.PAGE).isEmpty()));assertTrue(tx(f,()->store.list(TaskProviderScans.SCAN).isEmpty()));assertEquals(before,stored(f,"one"));assertEquals(cursor,tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst()));
    }
    private void imported(Fixture f,TaskCloudProvider.RemoteTask...items){
        var claim=claim(f);var start=tx(f,()->scans().begin(claim));tx(f,()->scans().acknowledge(claim,start,page(null,items)));
        var preview=tx(f,()->scans().review(f.connection(),f.list()));tx(f,()->scans().approve(f.connection(),f.list(),etag(preview)));
    }
    @Test void incrementalScansOverlapAndKeepUnchangedSiblingsAndUnsupportedLocalFields(){
        var f=fixture();imported(f,task("one",null,"001","One"),task("two",null,"002","Two"),task("three",null,"003","Three"));
        var before=stored(f,"one");var third=stored(f,"three");var claim=reclaim(f);var next=tx(f,()->scans().begin(claim));
        assertFalse(next.path("full").asBoolean());assertEquals(Instant.parse(next.path("lastAppliedAt").asText()).minusSeconds(300).toString(),next.path("updatedMin").asText());
        tx(f,()->scans().acknowledge(claim,next,page(null,task("two",null,"002","Changed title"))));var result=tx(f,()->scans().reconcile(claim));assertTrue(result.path("complete").asBoolean());
        assertEquals(1,stored(f,"two").path("siblingOrder").asInt());assertEquals(before,stored(f,"one"));assertEquals(third,stored(f,"three"));assertEquals("Changed title",stored(f,"two").path("summary").asText());
        assertEquals("INCREMENTAL",tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst()).path("mode").asText());
        tx(f,()->{var value=((ObjectNode)store.find(TaskProviderScans.SCAN,f.list()).orElseThrow()).deepCopy();value.put("lastFullAt",store.now().minusSeconds(86401).toString());connections().save(TaskProviderScans.SCAN,value);return null;});
        var fullClaim=reclaim(f);assertTrue(tx(f,()->scans().begin(fullClaim)).path("full").asBoolean());
    }
    @Test void changingAListMappingOnlyAffectsNewRootsAndChildrenKeepTheirParentsProject(){
        var f=fixture();imported(f,task("parent",null,"001","Parent"));var original=stored(f,"parent");
        UUID project=UUID.randomUUID();tx(f,()->{store.save("PROJECT",new Project(new ProjectId(project),"New imports",0));var value=connections().visible(connections().required(f.connection()));connections().select(f.connection(),List.of("remote-list"),object().put("remote-list",project.toString()),etag(value));return null;});
        var claim=claim(f);var start=tx(f,()->scans().begin(claim));
        tx(f,()->scans().acknowledge(claim,start,page(null,task("child","parent","002","Child"),task("new-root",null,"003","New root"))));
        assertTrue(tx(f,()->scans().reconcile(claim)).path("complete").asBoolean());
        assertEquals(original,stored(f,"parent"));assertEquals(f.project().toString(),stored(f,"child").path("projectId").asText());assertEquals(project.toString(),stored(f,"new-root").path("projectId").asText());
    }
    @Test void firstImportApprovalCannotUseAComparisonFromBeforeAnotherLocalImport(){
        var f=fixture();var remote=task("one",null,"001","One");var claim=claim(f);var start=tx(f,()->scans().begin(claim));
        tx(f,()->scans().acknowledge(claim,start,page(null,remote)));var before=tx(f,()->scans().review(f.connection(),f.list()));assertEquals(1,before.path("createCount").asInt());
        tx(f,()->new TaskCloudPull(store,receipts,inputs).apply(f.connection(),f.list(),new TaskCloudPull.Scan("google","account","remote-list",List.of(remote),store.now())));
        assertEquals(412,assertThrows(ApiFailure.class,()->tx(f,()->scans().approve(f.connection(),f.list(),etag(before)))).status);
        var after=tx(f,()->scans().review(f.connection(),f.list()));assertEquals(0,after.path("createCount").asInt());assertEquals(1,after.path("existingCount").asInt());
        tx(f,()->scans().approve(f.connection(),f.list(),etag(after)));assertEquals(1,tx(f,()->store.list("TASK").size()));
    }
    @Test void explicitDeletionRequiresReviewAndDoesNotAdvanceTheLastAcknowledgedTaskCursor(){
        var f=fixture();imported(f,task("one",null,"001","One"));var before=stored(f,"one");var cursor=tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst());
        var claim=reclaim(f);var start=tx(f,()->scans().begin(claim));var deleted=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(object().put("id","one").put("deleted",true));
        tx(f,()->scans().acknowledge(claim,start,page(null,deleted)));var outcome=tx(f,()->scans().reconcile(claim));
        assertFalse(outcome.path("complete").asBoolean());assertEquals("DELETE_REVIEW",outcome.path("pending").get(0).path("status").asText());assertEquals(before,stored(f,"one"));assertEquals(cursor,tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst()));
    }
    private TaskProviderReviews reviews(){return new TaskProviderReviews(store,receipts,inputs);}
    private ObjectNode incoming(Fixture f,TaskCloudProvider.RemoteTask...items){
        var claim=tx(f,()->{var auth=connections().required(f.connection());auth.putNull("lease").putNull("leaseUntil").put("lastAttempt",store.now().minusSeconds(31).toString());connections().save(TaskProviderConnections.AUTH,auth);return connections().claim(f.connection(),true);});var start=tx(f,()->scans().begin(claim));var ready=tx(f,()->scans().acknowledge(claim,start,page(null,items)));
        tx(f,()->scans().reconcile(claim));return ready;
    }
    private void localTitle(Fixture f,String id,String title){
        tx(f,()->{var task=((ObjectNode)store.byReference(new ExternalTaskReferenceKey("google",TaskCloudPull.repository("account","remote-list"),id)).orElseThrow()).deepCopy();task.put("summary",title).put("version",task.path("version").asLong()+1);store.save("TASK",(Task)decode("TASK",task));return null;});
    }
    @Test void incomingConflictReviewBindsBothVersionsAndIdenticalReplayKeepsItsReceipt(){
        var f=fixture();imported(f,task("one",null,"001","Original"));localTitle(f,"one","Local title");incoming(f,task("one",null,"001","Google title"));
        var page=tx(f,()->reviews().list(f.connection(),f.list(),null));inputs.validate("TaskProviderReviews",page);var row=page.path("items").get(0);assertTrue(row.path("canResolve").asBoolean());assertEquals("Local title",row.path("localFields").path("summary").asText());assertEquals("Google title",row.path("remoteFields").path("summary").asText());
        UUID id=uuid(row.get("taskId"));var input=object().put("decision","REMOTE").put("taskEtag",row.path("taskEtag").asText());
        assertEquals(412,assertThrows(ApiFailure.class,()->tx(f,()->reviews().resolve(f.connection(),f.list(),id,"\"old\"",input))).status);
        assertThrows(ApiFailure.class,()->store.transaction("foreign-"+UUID.randomUUID(),()->reviews().list(f.connection(),f.list(),null)));
        var result=tx(f,()->reviews().resolve(f.connection(),f.list(),id,row.path("reviewEtag").asText(),input));inputs.validate("TaskProviderReviewResult",result.body());assertEquals("Google title",stored(f,"one").path("summary").asText());var after=stored(f,"one");
        var replay=tx(f,()->reviews().resolve(f.connection(),f.list(),id,row.path("reviewEtag").asText(),input));assertEquals(result.body().toString(),replay.body().toString());assertEquals(after,stored(f,"one"));
    }
    @Test void incomingReviewCannotOverwriteAnEditOrUseAPausedOrExpiredScan(){
        var f=fixture();imported(f,task("one",null,"001","Original"));localTitle(f,"one","Local");incoming(f,task("one",null,"001","Google"));
        var row=tx(f,()->reviews().list(f.connection(),f.list(),null)).path("items").get(0);UUID id=uuid(row.get("taskId"));var input=object().put("decision","LOCAL").put("taskEtag",row.path("taskEtag").asText());
        localTitle(f,"one","New draft");assertEquals(412,assertThrows(ApiFailure.class,()->tx(f,()->reviews().resolve(f.connection(),f.list(),id,row.path("reviewEtag").asText(),input))).status);assertEquals("New draft",stored(f,"one").path("summary").asText());
        tx(f,()->{var auth=connections().visible(connections().required(f.connection()));connections().pause(f.connection(),true,etag(auth));return null;});assertEquals(409,assertThrows(ApiFailure.class,()->tx(f,()->reviews().list(f.connection(),f.list(),null))).status);
        tx(f,()->{var auth=connections().visible(connections().required(f.connection()));connections().pause(f.connection(),false,etag(auth));var scan=((ObjectNode)store.find(TaskProviderScans.SCAN,f.list()).orElseThrow()).deepCopy();scan.put("startedAt",store.now().minusSeconds(901).toString());connections().save(TaskProviderScans.SCAN,scan);return null;});assertEquals(409,assertThrows(ApiFailure.class,()->tx(f,()->reviews().list(f.connection(),f.list(),null))).status);
    }
    @Test void explicitDeleteCanKeepTheNativeTaskAndLaterLiveObservationRequiresANewDeleteReview(){
        var f=fixture();imported(f,task("one",null,"001","Keep this"));var deleted=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(object().put("id","one").put("deleted",true));incoming(f,deleted);
        var row=tx(f,()->reviews().list(f.connection(),f.list(),null)).path("items").get(0);UUID id=uuid(row.get("taskId"));var before=stored(f,"one");
        tx(f,()->reviews().resolve(f.connection(),f.list(),id,row.path("reviewEtag").asText(),object().put("decision","LOCAL").put("taskEtag",row.path("taskEtag").asText())));
        assertEquals(before.path("summary"),stored(f,"one").path("summary"));assertTrue(tx(f,()->store.list("TASK_TOMBSTONE").isEmpty()));assertEquals("APPLIED",tx(f,()->store.find(TaskProviderScans.SCAN,f.list()).orElseThrow()).path("state").asText());
        incoming(f,deleted);assertEquals("APPLIED",tx(f,()->store.find(TaskProviderScans.SCAN,f.list()).orElseThrow()).path("state").asText());
        localTitle(f,"one","Local draft after deletion");incoming(f,task("one",null,"001","Live again with a conflicting title"));assertEquals("Local draft after deletion",stored(f,"one").path("summary").asText());assertFalse(stored(f,"one").path("externalReferences").get(0).path("providerData").path("payload").path("deletionReview").asBoolean());incoming(f,deleted);var again=tx(f,()->reviews().list(f.connection(),f.list(),null)).path("items").get(0);assertTrue(again.path("canDelete").asBoolean());
        var result=tx(f,()->reviews().resolve(f.connection(),f.list(),id,again.path("reviewEtag").asText(),object().put("decision","DELETE").put("taskEtag",again.path("taskEtag").asText())));assertTrue(result.body().path("deleted").asBoolean());assertTrue(tx(f,()->store.find("TASK",id).isEmpty()));assertFalse(tx(f,()->store.list("TASK_TOMBSTONE").isEmpty()));
    }

}
