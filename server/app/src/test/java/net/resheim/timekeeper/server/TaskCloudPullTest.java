package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import net.resheim.eclipse.timekeeper.domain.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(TaskCloudPullTest.Profile.class)
class TaskCloudPullTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){var config=TaskProviderTestDatabase.settings("task_cloud_pull");return config;}
    }
    @Inject ServerStore store;
    @Inject CommandReceipts receipts;
    @Inject ContractInputs inputs;
    private static final Instant TIME=Instant.parse("2026-11-09T12:00:00Z");
    record Fixture(String owner,UUID connection,UUID list) {}
    private TaskCloudProvider google(){return new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE);}
    private Fixture fixture(){return fixture(TaskCloudProvider.Provider.GOOGLE);}
    private Fixture fixture(TaskCloudProvider.Provider provider){
        String owner="cloud-test-"+UUID.randomUUID();UUID connection=UUID.randomUUID(),list=UUID.randomUUID();
        var adapter=new TaskCloudProvider(provider);
        store.transaction(owner,()->{
            store.save("TASK_CONNECTION",new TaskConnection(connection,adapter.id(),"remote-account","Task account",true,false,true,capabilities(adapter.capabilities()),0));
            store.save("TASK_LIST",new TaskList(list,connection,"remote-list","Selected list",TaskListKind.STANDARD,true,false,false,true,true,0,null,0));return null;
        });return new Fixture(owner,connection,list);
    }
    private <T>T tx(Fixture f,Supplier<T> work){return store.transaction(f.owner(),work);}
    private TaskCloudPull pull(){return new TaskCloudPull(store,receipts,inputs);}
    private TaskCloudProvider.RemoteTask task(String id,String title,String parent,String position){
        var value=object().put("id",id).put("title",title).put("status","needsAction").put("position",position).put("etag","provider-v1").put("updated","2026-11-08T11:00:00Z");
        if(parent!=null)value.put("parent",parent);return google().normalize(value);
    }
    private TaskCloudPull.Scan scan(TaskCloudProvider.RemoteTask...tasks){return new TaskCloudPull.Scan("google","remote-account","remote-list",List.of(tasks),TIME);}
    private ObjectNode apply(Fixture f,TaskCloudPull.Scan scan){return tx(f,()->pull().apply(f.connection(),f.list(),scan));}
    private JsonNode stored(Fixture f,String id){return tx(f,()->store.byReference(new ExternalTaskReferenceKey("google",TaskCloudPull.repository("remote-account","remote-list"),id)).orElseThrow());}
    private TaskProviderHttp.Reply reply(int code,String json){return new TaskProviderHttp.Reply(code,Map.of(),json.getBytes(StandardCharsets.UTF_8));}

    @Test void googleCompletionUpdatesTheLinkedNoteCheckboxAndKeepsLocalReopeningInImportOnlyMode(){
        var f=fixture();var first=task("one","Checklist task",null,"001");
        String original="\ufeff- [ ] Checklist **task**\r\n";TaskId[] nativeId={null};UUID[] entryId={null};
        tx(f,()->{
            var vault=new VaultCollection(store,store.vaultLimits());vault.initialize();
            var entry=vault.put(new VaultPath("Google checklist.md"),null,true,original.getBytes(StandardCharsets.UTF_8));
            var local=store.service().tasks().getFirst();nativeId[0]=local.id();entryId[0]=VaultCollection.id(entry);
            var fields=GoogleTaskContext.fields(first,TaskCloudPull.repository("remote-account","remote-list"));
            fields.putNull("parentTaskId").put("siblingOrder",0);
            var reference=new ExternalTaskReference("google",TaskCloudPull.repository("remote-account","remote-list"),"one",Optional.empty())
                    .withTaskList(Optional.of(f.list())).withBaseline(Optional.of(new ExternalTaskBaseline(1,TIME,fields.toString())));
            store.service().linkExternalReference(new net.resheim.eclipse.timekeeper.service.Commands.LinkExternalReference(local.id(),local.version(),reference));
            return null;
        });
        assertTrue(apply(f,scan(first)).path("complete").asBoolean());
        var changed=((ObjectNode)first.original()).deepCopy().put("status","completed").put("completed","2026-11-09T12:00:00Z").put("etag","provider-v2");
        var completed=google().normalize(changed);assertTrue(apply(f,scan(completed)).path("complete").asBoolean());
        tx(f,()->{
            var vault=new VaultCollection(store,store.vaultLimits());var entry=vault.get(entryId[0]);
            assertArrayEquals(original.replace("[ ]","[x]").getBytes(StandardCharsets.UTF_8),vault.read(entry));
            assertEquals(TaskStatus.COMPLETED,store.service().task(nativeId[0]).orElseThrow().planning().status());
            vault.put(new VaultPath("Google checklist.md"),vault.tag(entry),false,original.getBytes(StandardCharsets.UTF_8));
            return null;
        });
        assertTrue(apply(f,scan(completed)).path("complete").asBoolean());
        tx(f,()->{
            var vault=new VaultCollection(store,store.vaultLimits());assertArrayEquals(original.getBytes(StandardCharsets.UTF_8),vault.read(vault.get(entryId[0])));
            assertEquals(TaskStatus.NOT_STARTED,store.service().task(nativeId[0]).orElseThrow().planning().status());
            assertEquals(nativeId[0].value().toString(),stored(f,"one").path("id").asText());assertEquals(1,store.service().tasks().size());
            return null;
        });
    }

    @Test void googleSourceContextImportsWithoutReplacingTheUsersPrimaryUrlAndSurvivesRepeatedScans(){
        var f=fixture();var raw=object().put("id","one").put("title","Source task").put("status","needsAction").put("position","001").put("webViewLink","https://tasks.google.com/one");
        raw.putArray("links").add(object().put("type","email").put("description","Email source").put("link","https://mail.google.com/mail/u/0/#inbox/source"));raw.putObject("assignmentInfo").put("linkToTask","https://docs.google.com/document/d/source/edit").putObject("driveResourceInfo").put("driveFileId","source");
        var observed=google().normalize(raw);assertTrue(apply(f,scan(observed)).path("complete").asBoolean());var imported=stored(f,"one");assertTrue(imported.path("url").isNull());assertEquals("https://tasks.google.com/one",imported.path("externalReferences").get(0).path("externalUrl").asText());assertEquals("drive:source",imported.path("externalReferences").get(0).path("assignment").path("sourceId").asText());assertEquals(1,imported.path("metadata").path("links").size());
        assertTrue(apply(f,scan(observed)).path("complete").asBoolean());assertEquals(imported,stored(f,"one"));
        tx(f,()->{var task=(Task)decode("TASK",stored(f,"one"));store.service().updateTask(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTask(task.id(),task.version(),task.projectId(),task.parentTaskId(),task.summary(),Optional.of("https://example.invalid/own-url")));return null;});
        raw.put("webViewLink","https://tasks.google.com/updated");assertTrue(apply(f,scan(google().normalize(raw))).path("complete").asBoolean());assertEquals("https://example.invalid/own-url",stored(f,"one").path("url").asText());assertEquals("https://tasks.google.com/updated",stored(f,"one").path("externalReferences").get(0).path("externalUrl").asText());
    }
    @Test void providerLinkChangesCannotReplaceLocalResourcesAndRetainTheCompletedCheckpoint(){
        var f=fixture();var raw=object().put("id","one").put("title","Source task").put("status","needsAction").put("position","001");raw.putArray("links").add(object().put("type","email").put("link","https://mail.google.com/source"));apply(f,scan(google().normalize(raw)));
        tx(f,()->{var task=(ObjectNode)stored(f,"one");var local=task.withObject("metadata").withArray("links").addObject();local.put("id",UUID.randomUUID().toString()).put("kind","LOCAL").put("url","https://example.invalid/own-link").putNull("title").putNull("providerId").putNull("remoteId");task.put("version",task.path("version").asLong()+1);store.save("TASK",(Task)decode("TASK",task));return null;});
        var before=stored(f,"one");var cursor=tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst());raw.withArray("links").add(object().put("type","generic").put("link","https://docs.google.com/new-source"));var result=apply(f,scan(google().normalize(raw)));
        assertFalse(result.path("complete").asBoolean());assertEquals("CONFLICT",result.path("pending").get(0).path("status").asText());assertEquals(before,stored(f,"one"));assertEquals(cursor,tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst()));
    }
    @Test void reviewedGoogleValuesKeepResourcesOutsideTheSourceBaselineAndRejectStaleTaskChoices(){
        var f=fixture();var raw=object().put("id","one").put("title","Before").put("status","needsAction").put("position","001");raw.putArray("links").add(object().put("type","email").put("description","Old source").put("link","https://mail.google.com/source"));apply(f,scan(google().normalize(raw)));
        String localId=UUID.randomUUID().toString();tx(f,()->{var task=(ObjectNode)stored(f,"one");task.put("summary","Local title");task.withObject("metadata").withArray("links").add(object().put("id",localId).put("kind","LOCAL").put("url","https://example.invalid/own").putNull("title").putNull("providerId").putNull("remoteId"));task.put("version",task.path("version").asLong()+1);store.save("TASK",(Task)decode("TASK",task));return null;});
        raw.put("title","Google title");raw.withArray("links").removeAll().add(object().put("type","generic").put("link","https://docs.google.com/new"));var remote=google().normalize(raw);var fields=GoogleTaskContext.fields(remote,TaskCloudPull.repository("remote-account","remote-list"));fields.putNull("parentTaskId").put("siblingOrder",0);
        var input=object().put("connectionId",f.connection().toString()).put("listId",f.list().toString()).put("sourceState","HEALTHY");var item=input.putObject("item").put("operationId",UUID.randomUUID().toString()).put("providerId","google").put("repositoryId",TaskCloudPull.repository("remote-account","remote-list")).put("externalId","one").put("deleted",false);item.set("fields",fields);
        var before=stored(f,"one");assertEquals(412,assertThrows(ApiFailure.class,()->tx(f,()->new TaskSync(store,receipts).resolve(input,"\"stale-task\"",true))).status);
        var reply=tx(f,()->new TaskSync(store,receipts).resolve(input,etag(before),true));assertEquals("Google title",reply.body().path("summary").asText());var links=reply.body().path("metadata").path("links");assertEquals(2,links.size());assertTrue(links.toString().contains(localId));
        var baseline=reply.body().path("externalReferences").get(0).path("baseline").path("fields").path("metadata").path("links");assertEquals(1,baseline.size());assertFalse(baseline.toString().contains(localId));var repeated=tx(f,()->new TaskSync(store,receipts).resolve(input,etag(before),true));assertEquals(reply.etag(),repeated.etag());assertEquals(canonical(reply.body()).toString(),canonical(repeated.body()).toString());assertEquals(reply.etag(),etag(stored(f,"one")));
    }
    @Test void keepingTheLocalConflictStillAcceptsIndependentGoogleCompletionAndKeepsRichNotes(){
        var f=fixture();var raw=object().put("id","one").put("title","Before").put("notes","Original notes").put("status","needsAction").put("position","001");apply(f,scan(google().normalize(raw)));
        tx(f,()->{var task=(ObjectNode)stored(f,"one");task.put("summary","Local title").put("priority","HIGH");task.putObject("body").put("format","HTML").put("content","<b>Local rich notes</b>");task.put("version",task.path("version").asLong()+1);store.save("TASK",(Task)decode("TASK",task));return null;});
        raw.put("title","Google title").put("status","completed").put("completed","2026-11-10T09:00:00Z");var remote=google().normalize(raw);var fields=GoogleTaskContext.fields(remote,TaskCloudPull.repository("remote-account","remote-list"));fields.putNull("parentTaskId").put("siblingOrder",0);
        var input=object().put("connectionId",f.connection().toString()).put("listId",f.list().toString()).put("sourceState","HEALTHY");var item=input.putObject("item").put("operationId",UUID.randomUUID().toString()).put("providerId","google").put("repositoryId",TaskCloudPull.repository("remote-account","remote-list")).put("externalId","one").put("deleted",false);item.set("fields",fields);
        var before=stored(f,"one");var applied=tx(f,()->new TaskSync(store,receipts).resolve(input,etag(before),false)).body();assertEquals("Local title",applied.path("summary").asText());assertEquals("COMPLETED",applied.path("status").asText());assertEquals("<b>Local rich notes</b>",applied.path("body").path("content").asText());assertEquals("HIGH",applied.path("priority").asText());assertEquals("Google title",applied.path("externalReferences").get(0).path("baseline").path("fields").path("summary").asText());
        assertTrue(apply(f,scan(remote)).path("complete").asBoolean());assertEquals("Local title",stored(f,"one").path("summary").asText());assertEquals("<b>Local rich notes</b>",stored(f,"one").path("body").path("content").asText());
    }
    @Test void childBeforeParentImportsTheOwnedHierarchyAndRepeatedScansDoNotDuplicateOrAdvanceTaskVersions(){
        var f=fixture();var child=task("child"," Child ","parent","0001");var parent=task("parent","Parent",null,"0002");
        var result=apply(f,scan(child,parent));assertTrue(result.path("complete").asBoolean());assertEquals(2,result.path("created").asInt());
        var before=stored(f,"child");var root=stored(f,"parent");
        assertEquals(root.path("id"),before.path("parentTaskId"));
        assertEquals("Child",before.path("summary").asText());
        assertEquals("2026-11-08T11:00:00Z",before.path("externalReferences").get(0).path("source").path("modifiedAt").asText());
        assertEquals(child.original(),before.path("externalReferences").get(0).path("providerData").path("payload").path("observation"));
        var again=apply(f,scan(child,parent));assertEquals(0,again.path("created").asInt());assertEquals(2,again.path("unchanged").asInt());
        assertEquals(before,stored(f,"child"));assertEquals(root,stored(f,"parent"));
        assertEquals(2,tx(f,()->store.list("TASK").size()));
        assertEquals(1,tx(f,()->store.list("TASK_SYNC_CURSOR").size()));
    }
    @Test void remoteOnlyChangesApplyWhileIndependentLocalChangesRemain(){
        var f=fixture();var first=task("one","Before",null,"001");apply(f,scan(first));
        tx(f,()->{var value=(ObjectNode)stored(f,"one");value.put("priority","HIGH").put("version",value.path("version").asLong()+1);store.save("TASK",(Task)decode("TASK",value));return null;});
        var next=task("one","After",null,"001");var result=apply(f,scan(next));
        assertEquals(1,result.path("updated").asInt());assertTrue(result.path("complete").asBoolean());
        assertEquals("After",stored(f,"one").path("summary").asText());assertEquals("HIGH",stored(f,"one").path("priority").asText());
    }
    @Test void opposingEditsReturnConflictAndRetainBothTheTaskAndPreviousCheckpoint(){
        var f=fixture();apply(f,scan(task("one","Before",null,"001")));
        tx(f,()->{var value=(ObjectNode)stored(f,"one");value.put("summary","Local edit").put("version",value.path("version").asLong()+1);store.save("TASK",(Task)decode("TASK",value));return null;});
        var before=stored(f,"one");var cursor=tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst().deepCopy());
        var result=apply(f,scan(task("one","Remote edit",null,"001")));
        assertFalse(result.path("complete").asBoolean());assertEquals("CONFLICT",result.path("pending").get(0).path("status").asText());
        assertEquals("Remote edit",result.path("pending").get(0).path("observation").path("fields").path("summary").asText());
        assertEquals(before,stored(f,"one"));assertEquals(cursor,tx(f,()->store.list("TASK_SYNC_CURSOR").getFirst()));
    }
    @Test void explicitRemoteDeletionRequiresReviewAndAbsenceNeverDeletes(){
        var f=fixture();apply(f,scan(task("one","Keep me",null,"001")));
        var before=stored(f,"one");var deleted=google().normalize(parse("{\"id\":\"one\",\"deleted\":true}"));
        var result=apply(f,scan(deleted));assertFalse(result.path("complete").asBoolean());
        assertTrue(result.path("pending").get(0).path("status").asText().startsWith("DELETE_"));assertEquals(before,stored(f,"one"));
        assertTrue(apply(f,scan()).path("complete").asBoolean());assertEquals(before,stored(f,"one"));
    }
    @Test void aLocallyDeletedTaskDoesNotReturnOrBlockOtherSafeImports(){
        var f=fixture();var deleted=task("deleted","Do not resurrect",null,"001");apply(f,scan(deleted));
        tx(f,()->{var current=stored(f,"deleted");store.service().deleteTask(new net.resheim.eclipse.timekeeper.service.Commands.DeleteTask(new TaskId(uuid(current.get("id"))),current.path("version").asLong()));return null;});
        var result=apply(f,scan(deleted,task("new-task","Import this",null,"002")));
        assertFalse(result.path("complete").asBoolean());assertEquals(1,result.path("created").asInt());assertEquals(1,result.path("pending").size());assertEquals("CONFLICT",result.path("pending").get(0).path("status").asText());
        assertEquals("LOCAL_DELETED",result.path("pending").get(0).path("reason").asText());
        assertEquals("Import this",stored(f,"new-task").path("summary").asText());
        tx(f,()->{assertEquals(1,store.list("TASK").size());assertEquals(1,store.list("TASK_TOMBSTONE").size());return null;});
    }
    @Test void unresolvedHierarchyDoesNotFlattenOrInventParents(){
        var f=fixture();var result=apply(f,scan(task("child","Child","missing-parent","001")));
        assertFalse(result.path("complete").asBoolean());assertEquals("PARENT_UNRESOLVED",result.path("pending").get(0).path("reason").asText());
        assertTrue(tx(f,()->store.list("TASK").isEmpty()));assertTrue(tx(f,()->store.list("TASK_SYNC_CURSOR").isEmpty()));
    }
    @Test void unreadableProviderPositionCannotInventAnOrderingBaseline(){
        var f=fixture();var remote=google().normalize(parse("{\"id\":\"id\",\"title\":\"Task\",\"status\":\"needsAction\"}"));
        var result=apply(f,scan(remote));assertFalse(result.path("complete").asBoolean());
        assertEquals("POSITION_UNOBSERVED",result.path("pending").get(0).path("reason").asText());assertTrue(tx(f,()->store.list("TASK").isEmpty()));
    }
    @Test void wrongOwnerAccountOrListCannotApplyTheScan(){
        var f=fixture();var other=fixture();var scan=scan(task("one","Task",null,"001"));
        assertThrows(ApiFailure.class,()->tx(other,()->pull().apply(f.connection(),f.list(),scan)));
        assertThrows(ApiFailure.class,()->apply(f,new TaskCloudPull.Scan("google","another-account","remote-list",scan.tasks(),TIME)));
        assertThrows(ApiFailure.class,()->apply(f,new TaskCloudPull.Scan("google","remote-account","another-list",scan.tasks(),TIME)));
        assertTrue(tx(f,()->store.list("TASK").isEmpty()));assertTrue(tx(other,()->store.list("TASK").isEmpty()));
    }
    @Test void pageFailureOrRepeatedTokenCannotBecomeACompleteOrEmptyScan(){
        var calls=new AtomicInteger();
        var provider=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("tasks.googleapis.com","openidconnect.googleapis.com"),request->{
            if(request.uri().getHost().equals("openidconnect.googleapis.com"))return reply(200,"{\"sub\":\"remote-account\"}");
            return calls.incrementAndGet()==1?reply(200,"{\"items\":[{\"id\":\"one\",\"title\":\"Title\",\"status\":\"needsAction\"}],\"nextPageToken\":\"next\"}"):reply(503,"{\"private\":\"details\"}");
        }));
        var failure=assertThrows(TaskProviderHttp.Failure.class,()->TaskCloudPull.fetch(provider,"token","remote-account","remote-list",TIME));
        assertEquals("TASK_PROVIDER_UNAVAILABLE",failure.getMessage());assertEquals(2,calls.get());
        var repeat=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("tasks.googleapis.com","openidconnect.googleapis.com"),request->
                request.uri().getHost().equals("openidconnect.googleapis.com")?reply(200,"{\"sub\":\"remote-account\"}"):reply(200,"{\"nextPageToken\":\"repeated\"}")));
        assertEquals("TASK_PROVIDER_SCAN_INCOMPLETE",assertThrows(TaskProviderHttp.Failure.class,()->TaskCloudPull.fetch(repeat,"token","remote-account","remote-list",TIME)).getMessage());
    }
    @Test void aTokenForAnotherProviderAccountStopsBeforeAnyTaskPage(){
        var calls=new AtomicInteger();var provider=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE,new TaskProviderHttp(Set.of("openidconnect.googleapis.com"),request->{calls.incrementAndGet();return reply(200,"{\"sub\":\"other-account\"}");}));
        assertEquals("TASK_PROVIDER_ACCOUNT_CHANGED",assertThrows(TaskProviderHttp.Failure.class,()->TaskCloudPull.fetch(provider,"token","remote-account","remote-list",TIME)).getMessage());assertEquals(1,calls.get());
    }
    @Test void microsoftImportsRetainHtmlDatesAndProviderDataWithoutClearingLocalAppleTags(){
        var f=fixture(TaskCloudProvider.Provider.MICROSOFT);var adapter=new TaskCloudProvider(TaskCloudProvider.Provider.MICROSOFT);
        var raw=parse("""
            {"id":"graph-task","title":"Plan","status":"notStarted","categories":["Work"],"body":{"contentType":"html","content":"<p>Original</p>"},
             "dueDateTime":{"dateTime":"2026-11-10T17:00:00.0000000","timeZone":"W. Europe Standard Time"},"importance":"normal",
             "createdDateTime":"2026-10-01T12:00:00Z","recurrence":{"pattern":{"type":"weekly"}}}
            """);
        var observation=adapter.normalize(raw);var scan=new TaskCloudPull.Scan("microsoft","remote-account","remote-list",List.of(observation),TIME);
        assertTrue(apply(f,scan).path("complete").asBoolean());
        var key=new ExternalTaskReferenceKey("microsoft",TaskCloudPull.repository("remote-account","remote-list"),"graph-task");
        tx(f,()->{
            var task=((ObjectNode)store.byReference(key).orElseThrow()).deepCopy();
            assertEquals("HTML",task.path("body").path("format").asText());assertEquals("W. Europe Standard Time",task.path("due").path("timeZone").asText());
            assertEquals("2026-10-01T12:00:00Z",task.path("externalReferences").get(0).path("source").path("createdAt").asText());
            assertEquals(raw,task.path("externalReferences").get(0).path("providerData").path("payload").path("observation"));
            ((ObjectNode)task.path("metadata")).putArray("tags").add("Apple tag");task.put("flagged",true).put("version",task.path("version").asLong()+1);
            store.save("TASK",(Task)decode("TASK",task));return null;
        });
        var next=((ObjectNode)raw).deepCopy();next.putArray("categories");
        assertTrue(apply(f,new TaskCloudPull.Scan("microsoft","remote-account","remote-list",List.of(adapter.normalize(next)),TIME)).path("complete").asBoolean());
        tx(f,()->{var task=store.byReference(key).orElseThrow();assertTrue(task.path("metadata").path("categories").isEmpty());assertEquals("Apple tag",task.path("metadata").path("tags").get(0).asText());assertTrue(task.path("flagged").asBoolean());return null;});
    }
}
