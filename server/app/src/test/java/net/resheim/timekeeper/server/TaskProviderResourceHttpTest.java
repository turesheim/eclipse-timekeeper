package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

@QuarkusTest @TestProfile(TaskProviderResourceHttpTest.Profile.class)
class TaskProviderResourceHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){var config=TaskProviderTestDatabase.settings("task_provider_http");config.putAll(Map.of("timekeeper.auth.public-origin","https://localhost",
                "timekeeper.tasks.google.client-id","fixture-google-id","timekeeper.tasks.google.client-secret","fixture-google-secret",
                "timekeeper.tasks.microsoft.client-id","fixture-microsoft-id","timekeeper.tasks.microsoft.client-secret","fixture-microsoft-secret"));return config;}
        public Set<Class<?>> getEnabledAlternatives(){return Set.of(TaskProviderHttpTestFixture.class);}
    }
    @Inject ContractInputs inputs;
    @Inject ServerStore store;
    @Inject TaskProviderRefresh refresh;
    private final List<String> created=new ArrayList<>();
    private static final String BROWSER="a".repeat(43);
    private Response call(String owner,String browser,String method,String path,ObjectNode value,String match){
        var request=given().header("Authorization","Bearer test-"+owner).contentType("application/json");
        if(browser!=null)request.header("Cookie",BrowserCookies.BROWSER+"="+browser);if(value!=null)request.body(value.toString());if(match!=null)request.header("If-Match",match);
        return request.request(method,"/api/v1/task-providers"+path);
    }
    private Response call(String method,String path,ObjectNode value,String match){return call("a",BROWSER,method,path,value,match);}
    private ObjectNode json(Response response,int status){assertEquals(status,response.statusCode(),response.asString());return (ObjectNode)parse(response.asString());}
    private String begin(String provider){
        var value=json(call("POST","/"+provider+"/authorize",object(),null),200);inputs.validate("TaskProviderAuthorization",value);
        return Arrays.stream(URI.create(value.path("authorizationUrl").asText()).getRawQuery().split("&")).filter(item->item.startsWith("state=")).map(item->URLDecoder.decode(item.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    private ObjectNode connect(String provider){
        var value=json(call("POST","/complete",object().put("state",begin(provider)).put("code","fixture-code-"+UUID.randomUUID()),null),200);
        inputs.validate("TaskProviderConnection",value);created.add(value.path("id").asText());return value;
    }
    private ObjectNode connectWritable(){
        var attempt=json(call("POST","/google/authorize",object().put("direction","TWO_WAY"),null),200);
        String state=Arrays.stream(URI.create(attempt.path("authorizationUrl").asText()).getRawQuery().split("&")).filter(item->item.startsWith("state=")).map(item->URLDecoder.decode(item.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
        var value=json(call("POST","/complete",object().put("state",state).put("code","write-fixture-"+UUID.randomUUID()),null),200);created.add(value.path("id").asText());assertEquals("TWO_WAY",value.path("direction").asText());return value;
    }
    private ObjectNode current(String id){return json(call("GET","/"+id,null,null),200);}
    private ObjectNode selectAndImport(ObjectNode value,String...lists)throws InterruptedException{
        var input=object();var selected=input.putArray("selectedLists");Arrays.stream(lists).forEach(selected::add);String id=value.path("id").asText();
        json(call("PUT","/"+id+"/lists",input,etag(value)),200);var staged=idle(id);
        assertEquals(lists.length,staged.path("imports").size());
        for(var preview:staged.path("imports")){
            String path="/"+id+"/imports/"+preview.path("id").asText();var reviewed=call("GET",path,null,null);assertEquals(200,reviewed.statusCode(),reviewed.asString());
            inputs.validate("TaskProviderImportPreview",parse(reviewed.asString()));assertEquals(428,call("POST",path,null,null).statusCode());
            json(call("POST",path,null,reviewed.header("ETag")),200);
        }
        return idle(id);
    }
    private ObjectNode idle(String id)throws InterruptedException{
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
        do{var value=current(id);if(!value.path("syncing").asBoolean())return value;Thread.sleep(25);}while(System.nanoTime()<deadline);
        fail("Provider refresh did not finish");return null;
    }
    @AfterEach void disconnect(){for(String id:created)for(int attempt=0;attempt<3;attempt++){var before=call("GET","/"+id,null,null);if(before.statusCode()==404)break;var reply=call("DELETE","/"+id,null,before.header("ETag"));if(reply.statusCode()==204)break;assertEquals(412,reply.statusCode(),reply.asString());}}
    @Test void googleAndMicrosoftSignInSelectionAndBackgroundPullCreateOwnedNativeTasks()throws Exception{
        for(String provider:List.of("google","microsoft")){
            var connection=connect(provider);String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1","list-2");
            var complete=idle(id);inputs.validate("TaskProviderConnection",complete);assertTrue(complete.path("lastError").isNull());assertTrue(complete.path("lastSuccess").isTextual());
            assertTrue(TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText()).reads.get()>=2);
            store.transaction(owner,()->{var lists=store.list("TASK_LIST").stream().filter(list->list.path("connectionId").asText().equals(id)).toList();assertEquals(2,lists.size());
                for(var list:lists)assertTrue(store.byReference(new net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey(provider,TaskCloudPull.repository(connection.path("remoteAccountId").asText(),list.path("remoteListId").asText()),"task-"+list.path("remoteListId").asText())).isPresent());return null;});
        }
    }
    @Test void anotherOwnerOrBrowserCannotConsumeTheCodeAndReadOnlySessionsCannotStartIt(){
        String state=begin("google");var input=object().put("state",state).put("code","fixture-code");
        assertEquals(409,call("b",BROWSER,"POST","/complete",input,null).statusCode());
        assertEquals(403,call("a","b".repeat(43),"POST","/complete",input,null).statusCode());
        var connection=json(call("POST","/complete",input,null),200);created.add(connection.path("id").asText());
        assertEquals(409,call("POST","/complete",input,null).statusCode());
        assertEquals(404,call("b",BROWSER,"GET","/"+connection.path("id").asText(),null,null).statusCode());
        assertEquals(403,call("read",BROWSER,"POST","/google/authorize",object(),null).statusCode());
    }
    @Test void directAuthorizationSupportsOnlyTheSelectedServicesForBothProviders()throws Exception{
        var catalog=json(call("GET","",null,null),200);inputs.validate("TaskProviderConnections",catalog);assertTrue(catalog.path("calendarSynchronization").asBoolean());
        for(String provider:List.of("google","microsoft"))for(boolean[] selection:List.of(new boolean[]{false,true},new boolean[]{true,false},new boolean[]{true,true})){
            boolean tasks=selection[0],calendars=selection[1];var input=object();input.set("services",object().put("tasks",tasks).put("calendars",calendars));
            var attempt=json(call("POST","/"+provider+"/authorize",input,null),200);var params=new HashMap<String,String>();for(String pair:URI.create(attempt.path("authorizationUrl").asText()).getRawQuery().split("&")){var parts=pair.split("=",2);params.put(parts[0],URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}
            assertEquals(ProviderScopes.requested(provider,tasks,calendars,false),params.get("scope"));
            String code=(tasks?calendars?"combined-fixture-":"fixture-":"calendar-fixture-")+UUID.randomUUID();
            var connected=json(call("POST","/complete",object().put("state",params.get("state")).put("code",code),null),200);created.add(connected.path("id").asText());
            assertEquals(tasks,connected.path("services").path("tasks").asBoolean());assertEquals(calendars,connected.path("services").path("calendars").asBoolean());
            assertEquals(tasks?2:0,connected.path("availableLists").size());assertEquals(calendars?1:0,connected.path("availableCalendars").size());
            if(calendars){String owner=connected.path("ownerId").asText(),id=connected.path("id").asText();var client=store.transaction(owner,()->store.createClient(object().put("name","Calendar parity")));var selected=object();selected.putArray("selectedCalendars").add("calendar");selected.putObject("calendarMappings").put("calendar",client.path("id").asText());json(call("PUT","/"+id+"/calendars",selected,etag(connected)),200);var synced=idle(id);assertTrue(synced.path("calendarLastError").isNull(),synced.toString());assertTrue(synced.path("calendarLastSuccess").isTextual());}
        }
    }
    @Test void revokedProviderAuthorizationDoesNotLogOutTheDagsverkAccount(){
        var failure=call("POST","/complete",object().put("state",begin("google")).put("code","denied-fixture"),null);
        assertEquals(409,failure.statusCode(),failure.asString());assertEquals("TASK_PROVIDER_RECONNECT_REQUIRED",failure.jsonPath().getString("code"));assertFalse(failure.asString().contains("private-provider-detail"));
        var list=json(call("GET","",null,null),200);assertTrue(list.path("providers").path("google").asBoolean());
    }
    @Test void secretFieldsCannotBeSubmittedOrReadAndCancellationConsumesTheAttempt(){
        assertEquals(400,call("POST","/google/authorize",object().put("ownerId","foreign"),null).statusCode());
        String state=begin("google");assertEquals(204,call("POST","/cancel",object().put("state",state),null).statusCode());
        assertEquals(409,call("POST","/complete",object().put("state",state).put("code","code"),null).statusCode());
        var connection=connect("google");String id=connection.path("id").asText();
        assertFalse(current(id).toString().contains("fixture-access-"));assertFalse(current(id).toString().contains("fixture-refresh-"));
        var export=given().header("Authorization","Bearer test-a").get("/api/v1/data/export");assertEquals(200,export.statusCode());
        for(String privateValue:List.of("fixture-access-","fixture-refresh-","fixture-google-secret","TASK_AUTH","TASK_OAUTH"))assertFalse(export.asString().contains(privateValue));
        assertEquals(400,call("PUT","/"+id+"/lists",object().put("accessToken","private"),etag(connection)).statusCode());
    }
    @Test void failedProviderReadKeepsImportedTasksAndSurfacesStatus()throws Exception{
        var connection=connect("google");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1");var first=idle(id);
        var state=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());state.failure=503;
        store.transaction(owner,()->{var c=new TaskProviderConnections(store);var value=c.required(UUID.fromString(id));value.put("lastAttempt",store.now().minusSeconds(31).toString());c.save(TaskProviderConnections.AUTH,value);return null;});
        assertEquals(202,call("POST","/"+id+"/refresh",null,null).statusCode());var result=idle(id);
        assertEquals("TASK_PROVIDER_UNAVAILABLE",result.path("lastError").asText());assertEquals(first.path("lastSuccess"),result.path("lastSuccess"));
        store.transaction(owner,()->{assertTrue(store.byReference(new net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey("google",TaskCloudPull.repository(connection.path("remoteAccountId").asText(),"list-1"),"task-list-1")).isPresent());return null;});
    }
    @Test void listDiscoveryWorksWithoutSelectionAndDoesNotImportUnselectedTasks()throws Exception{
        var connection=connect("google");String id=connection.path("id").asText();
        TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText()).listName="Updated work list";
        assertEquals(202,call("POST","/"+id+"/refresh",null,null).statusCode());var result=idle(id);
        assertEquals("Updated work list",result.path("availableLists").get(0).path("name").asText());assertTrue(result.path("selectedLists").isEmpty());
        assertEquals(0,TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText()).reads.get());
    }
    @Test void manualRefreshReplacesAReadyPreviewWithFreshProviderObservations()throws Exception{
        var connection=connect("google");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();
        var input=object();input.putArray("selectedLists").add("list-1").add("list-2");json(call("PUT","/"+id+"/lists",input,etag(connection)),200);var ready=idle(id);
        assertTrue(ready.path("lastSuccess").isNull());var paths=new LinkedHashMap<String,String>();var etags=new HashMap<String,String>();
        for(var preview:ready.path("imports")){String path="/"+id+"/imports/"+preview.path("id").asText();var before=call("GET",path,null,null);assertEquals(200,before.statusCode());paths.put(preview.path("remoteListId").asText(),path);etags.put(path,before.header("ETag"));}assertEquals(2,paths.size());
        TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText()).title="Fresh provider title";
        store.transaction(owner,()->{var c=new TaskProviderConnections(store);var value=c.required(UUID.fromString(id));value.put("lastAttempt",store.now().minusSeconds(31).toString());c.save(TaskProviderConnections.AUTH,value);return null;});
        assertEquals(202,call("POST","/"+id+"/refresh",null,null).statusCode());idle(id);
        for(var entry:paths.entrySet()){var after=call("GET",entry.getValue(),null,null);assertEquals(200,after.statusCode());assertEquals("Fresh provider title "+entry.getKey(),after.jsonPath().getString("samples[0].title"));
            assertEquals(412,call("POST",entry.getValue(),null,etags.get(entry.getValue())).statusCode());}
        assertTrue(store.transaction(owner,()->store.list("TASK").stream().noneMatch(task->task.path("externalReferences").toString().contains(connection.path("remoteAccountId").asText()))));
    }
    @Test void expiredTokensRefreshAndListRenamesDoNotRewriteUnchangedTasks()throws Exception{
        var connection=connect("microsoft");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1");idle(id);
        var key=new net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey("microsoft",TaskCloudPull.repository(connection.path("remoteAccountId").asText(),"list-1"),"task-list-1");
        var before=store.transaction(owner,()->store.byReference(key).orElseThrow());
        var state=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());state.listName="Renamed list";
        store.transaction(owner,()->{var c=new TaskProviderConnections(store);var value=c.required(UUID.fromString(id));value.put("lastAttempt",store.now().minusSeconds(31).toString()).put("tokenExpires",store.now().minusSeconds(1).toString());c.save(TaskProviderConnections.AUTH,value);return null;});
        assertEquals(202,call("POST","/"+id+"/refresh",null,null).statusCode());var result=idle(id);assertTrue(result.path("lastError").isNull());assertEquals(1,state.refreshes.get());
        assertEquals("Renamed list",result.path("availableLists").get(0).path("name").asText());
        store.transaction(owner,()->{assertEquals(before,store.byReference(key).orElseThrow());var list=store.list("TASK_LIST").stream().filter(row->row.path("connectionId").asText().equals(id)).findFirst().orElseThrow();assertEquals("Renamed list",list.path("name").asText());return null;});
    }
    @Test void queuedRefreshCannotContactTheProviderAfterDisconnect(){
        var connection=connect("google");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();
        var claim=store.transaction(owner,()->new TaskProviderConnections(store).claim(UUID.fromString(id),true));
        var state=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());int before=state.requests.get();
        var current=call("GET","/"+id,null,null);assertEquals(204,call("DELETE","/"+id,null,current.header("ETag")).statusCode());
        refresh.execute(claim);assertEquals(before,state.requests.get());
    }
    @Test void interruptedCatalogOnlyRefreshIsRescheduledWithoutAutomaticTaskPolling()throws Exception{
        var connection=connect("google");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();
        store.transaction(owner,()->{var c=new TaskProviderConnections(store);c.claim(UUID.fromString(id),true);var value=c.required(UUID.fromString(id));value.put("leaseUntil",store.now().minusSeconds(1).toString()).put("nextRefresh",store.now().toString());c.save(TaskProviderConnections.AUTH,value);return null;});
        TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText()).listName="Recovered catalog";
        refresh.sweep();var result=idle(id);assertEquals("Recovered catalog",result.path("availableLists").get(0).path("name").asText());assertTrue(result.path("lastError").isNull());
    }
    @Test void reviewedGoogleWritesUseRealHttpOwnershipScopesAndConditionalApprovals()throws Exception{
        var connection=connectWritable();String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1");
        var task=store.transaction(owner,()->store.byReference(new net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey("google",TaskCloudPull.repository(connection.path("remoteAccountId").asText(),"list-1"),"task-list-1")).orElseThrow());
        var list=store.transaction(owner,()->store.list("TASK_LIST").stream().filter(value->value.path("connectionId").asText().equals(id)).findFirst().orElseThrow());
        store.transaction(owner,()->{var current=(net.resheim.eclipse.timekeeper.domain.Task)decode("TASK",store.find("TASK",uuid(task.get("id"))).orElseThrow());store.service().updateTask(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTask(current.id(),current.version(),current.projectId(),current.parentTaskId(),"Reviewed title",current.url()));return null;});
        var input=object().put("taskId",task.path("id").asText()).put("listId",list.path("id").asText()).put("action","UPDATE");
        assertEquals(403,call("read",BROWSER,"POST","/"+id+"/writes",input,null).statusCode());assertEquals(404,call("b",BROWSER,"POST","/"+id+"/writes",input,null).statusCode());
        var response=call("POST","/"+id+"/writes",input,null);var preview=json(response,201);inputs.validate("TaskProviderWrite",preview);String path="/"+id+"/writes/"+preview.path("id").asText();
        var provider=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());assertEquals(0,provider.writes.get());assertEquals("Reviewed title",preview.path("writeFields").path("summary").asText());
        assertEquals(428,call("POST",path+"/confirm",null,null).statusCode());assertEquals(412,call("POST",path+"/confirm",null,"\"stale\"").statusCode());assertEquals(403,call("read",BROWSER,"POST",path+"/confirm",null,response.header("ETag")).statusCode());
        json(call("POST",path+"/confirm",null,response.header("ETag")),202);idle(id);var completed=json(call("GET",path,null,null),200);inputs.validate("TaskProviderWrite",completed);assertEquals("DONE",completed.path("state").asText());assertEquals(1,provider.writes.get());
        store.transaction(owner,()->{var updated=store.find("TASK",uuid(task.get("id"))).orElseThrow();assertEquals("Reviewed title",updated.path("externalReferences").get(0).path("baseline").path("fields").path("summary").asText());return null;});
        var deletion=call("POST","/"+id+"/writes",input.put("action","DELETE"),null);var deletePreview=json(deletion,201);String deletePath="/"+id+"/writes/"+deletePreview.path("id").asText();assertEquals(1,provider.writes.get());
        json(call("POST",deletePath+"/confirm",null,deletion.header("ETag")),202);idle(id);assertEquals("DONE",json(call("GET",deletePath,null,null),200).path("state").asText());assertEquals(2,provider.writes.get());
        assertTrue(store.transaction(owner,()->store.find("TASK",uuid(task.get("id"))).isPresent()));assertEquals(412,call("POST",deletePath+"/confirm",null,deletion.header("ETag")).statusCode());assertEquals(2,provider.writes.get());
        var archive=given().header("Authorization","Bearer test-a").get("/api/v1/data/export");assertEquals(200,archive.statusCode());assertFalse(archive.asString().contains("TASK_WRITE"));
    }
    @Test void googleCompletionAndReviewedReopeningRetainTheOriginalNoteChecklistTask()throws Exception{
        var connection=connectWritable();String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1");
        var list=store.transaction(owner,()->store.list("TASK_LIST").stream().filter(value->value.path("connectionId").asText().equals(id)).findFirst().orElseThrow());
        String title="Note Google "+UUID.randomUUID(),source="- [ ] "+title+"\r\n",path="Google checklist "+UUID.randomUUID()+".md";
        var note=given().header("Authorization","Bearer test-a").queryParam("path",path).header("If-None-Match","*").contentType("application/octet-stream").body(source.getBytes(StandardCharsets.UTF_8)).put("/api/v1/notes/files");assertEquals(201,note.statusCode(),note.asString());String entry=note.jsonPath().getString("id");
        var task=store.transaction(owner,()->store.list("TASK").stream().filter(value->value.path("summary").asText().equals(title)).findFirst().orElseThrow());String taskId=task.path("id").asText();
        var prepared=call("POST","/"+id+"/writes",object().put("listId",list.path("id").asText()).put("taskId",taskId).put("action","CREATE"),null);String writePath="/"+id+"/writes/"+json(prepared,201).path("id").asText();json(call("POST",writePath+"/confirm",null,prepared.header("ETag")),202);idle(id);assertEquals("DONE",json(call("GET",writePath,null,null),200).path("state").asText());
        var linked=store.transaction(owner,()->store.find("TASK",UUID.fromString(taskId)).orElseThrow());var provider=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());String external=linked.path("externalReferences").get(0).path("externalId").asText(),key="list-1/"+external;
        provider.created.get(key).put("status","completed").put("completed","2026-11-10T05:00:00Z").put("etag","\"cloud-completed\"");
        store.transaction(owner,()->{var connections=new TaskProviderConnections(store);var auth=connections.required(UUID.fromString(id));auth.put("lastAttempt",store.now().minusSeconds(31).toString());connections.save(TaskProviderConnections.AUTH,auth);return null;});json(call("POST","/"+id+"/refresh",null,null),202);idle(id);
        String content="/api/v1/notes/entries/"+entry+"/content";var completed=given().header("Authorization","Bearer test-a").get(content);assertEquals(200,completed.statusCode());assertEquals(source.replace("[ ]","[x]"),completed.asString());assertEquals(1,provider.writes.get());
        assertEquals(412,given().header("Authorization","Bearer test-a").header("If-Match",note.header("ETag")).contentType("application/octet-stream").body(source.getBytes(StandardCharsets.UTF_8)).put(content).statusCode());
        assertEquals(200,given().header("Authorization","Bearer test-a").header("If-Match",completed.header("ETag")).contentType("application/octet-stream").body(source.getBytes(StandardCharsets.UTF_8)).put(content).statusCode());assertEquals(1,provider.writes.get());
        var reopen=call("POST","/"+id+"/writes",object().put("listId",list.path("id").asText()).put("taskId",taskId).put("action","UPDATE"),null);var preview=json(reopen,201);assertEquals("NOT_STARTED",preview.path("writeFields").path("status").asText());String reopenPath="/"+id+"/writes/"+preview.path("id").asText();json(call("POST",reopenPath+"/confirm",null,reopen.header("ETag")),202);idle(id);assertEquals("DONE",json(call("GET",reopenPath,null,null),200).path("state").asText());
        assertEquals("needsAction",provider.created.get(key).path("status").asText());assertEquals(2,provider.writes.get());assertEquals(source,given().header("Authorization","Bearer test-a").get(content).asString());
        store.transaction(owner,()->{var saved=store.find("TASK",UUID.fromString(taskId)).orElseThrow();assertEquals(external,saved.path("externalReferences").get(0).path("externalId").asText());assertEquals(1,store.list("TASK").stream().filter(value->value.path("summary").asText().equals(title)).count());return null;});
    }

    @Test void googleCreationLinksTheOriginalNativeTaskAndUncertainIdentityRecoveryUsesOwnedConditionalRoutes()throws Exception{
        for(boolean uncertain:List.of(false,true)){
            var connection=connectWritable();String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1");
            var list=store.transaction(owner,()->store.list("TASK_LIST").stream().filter(value->value.path("connectionId").asText().equals(id)).findFirst().orElseThrow());
            var task=store.transaction(owner,()->store.service().createTask(new net.resheim.eclipse.timekeeper.service.Commands.CreateTask(Optional.empty(),"HTTP native task",Optional.empty())));
            var provider=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());provider.loseCreationReply=uncertain;
            var response=call("POST","/"+id+"/writes",object().put("listId",list.path("id").asText()).put("taskId",task.id().value().toString()).put("action","CREATE"),null);var preview=json(response,201);inputs.validate("TaskProviderWrite",preview);assertEquals(0,provider.writes.get());String path="/"+id+"/writes/"+preview.path("id").asText();
            json(call("POST",path+"/confirm",null,response.header("ETag")),202);idle(id);var completed=call("GET",path,null,null);assertEquals(uncertain?"UNKNOWN":"DONE",json(completed,200).path("state").asText());assertEquals(1,provider.writes.get());
            if(uncertain){
                assertEquals(409,call("GET",path+"/candidates",null,null).statusCode());json(call("POST",path+"/check",null,completed.header("ETag")),202);idle(id);
                var choices=json(call("GET",path+"/candidates?q=HTTP",null,null),200);inputs.validate("TaskProviderCreationCandidates",choices);assertEquals(1,choices.path("count").asInt());
                var current=call("GET",path,null,null);var body=json(current,200);var identity=object().put("externalId",choices.path("items").get(0).path("externalId").asText()).put("scanEtag",choices.path("scanEtag").asText()).put("taskEtag",body.path("currentTaskEtag").asText());
                assertEquals(404,call("b",BROWSER,"GET",path+"/candidates",null,null).statusCode());assertEquals(403,call("read",BROWSER,"POST",path+"/identity",identity,current.header("ETag")).statusCode());assertEquals(428,call("POST",path+"/identity",identity,null).statusCode());
                var reconciled=json(call("POST",path+"/identity",identity,current.header("ETag")),200);inputs.validate("TaskProviderWrite",reconciled);assertEquals("RECONCILED",reconciled.path("state").asText());assertEquals(1,provider.writes.get());
            }
            var linked=store.transaction(owner,()->store.find("TASK",task.id().value()).orElseThrow());assertEquals(task.id().value().toString(),linked.path("id").asText());assertEquals(1,linked.path("externalReferences").size());
        }
    }

    @Test void incomingReviewHttpChecksOwnerPermissionComparisonAndDoesNotSendToGoogle()throws Exception{
        var connection=connect("google");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1");
        var task=store.transaction(owner,()->store.byReference(new net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey("google",TaskCloudPull.repository(connection.path("remoteAccountId").asText(),"list-1"),"task-list-1")).orElseThrow());
        store.transaction(owner,()->{var changed=((ObjectNode)task).deepCopy();changed.put("summary","Local draft").put("version",task.path("version").asLong()+1);store.save("TASK",(net.resheim.eclipse.timekeeper.domain.Task)decode("TASK",changed));var auth=new TaskProviderConnections(store).required(UUID.fromString(id));auth.put("lastAttempt",store.now().minusSeconds(31).toString());new TaskProviderConnections(store).save(TaskProviderConnections.AUTH,auth);return null;});
        var provider=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());provider.title="Changed Google title";assertEquals(202,call("POST","/"+id+"/refresh",null,null).statusCode());var after=idle(id);assertEquals(1,after.path("reviews").size());
        String path="/"+id+"/reviews/"+after.path("reviews").get(0).path("listId").asText();var page=json(call("GET",path,null,null),200);inputs.validate("TaskProviderReviews",page);var row=page.path("items").get(0);String resolution=path+"/"+row.path("taskId").asText();var choice=object().put("decision","REMOTE").put("taskEtag",row.path("taskEtag").asText());
        assertEquals(404,call("b",BROWSER,"GET",path,null,null).statusCode());assertEquals(403,call("read",BROWSER,"POST",resolution,choice,row.path("reviewEtag").asText()).statusCode());assertEquals(428,call("POST",resolution,choice,null).statusCode());assertEquals(412,call("POST",resolution,choice,"\"old\"").statusCode());assertEquals(400,call("POST",resolution,choice.deepCopy().put("accessToken","untrusted"),row.path("reviewEtag").asText()).statusCode());
        var result=json(call("POST",resolution,choice,row.path("reviewEtag").asText()),200);inputs.validate("TaskProviderReviewResult",result);assertEquals(result,json(call("POST",resolution,choice,row.path("reviewEtag").asText()),200));idle(id);assertEquals(0,provider.writes.get());
        assertEquals("Changed Google title list-1",store.transaction(owner,()->store.find("TASK",uuid(task.get("id"))).orElseThrow()).path("summary").asText());
    }

    @Test void leavingUnidentifiedCreationUnlinkedUsesOwnedConditionalHttpWithoutAnotherProviderMutation()throws Exception{
        var connection=connectWritable();String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1");var list=store.transaction(owner,()->store.list("TASK_LIST").stream().filter(value->value.path("connectionId").asText().equals(id)).findFirst().orElseThrow());var task=store.transaction(owner,()->store.service().createTask(new net.resheim.eclipse.timekeeper.service.Commands.CreateTask(Optional.empty(),"HTTP task kept unlinked",Optional.empty())));var provider=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());provider.loseCreationReply=true;
        var prepared=call("POST","/"+id+"/writes",object().put("listId",list.path("id").asText()).put("taskId",task.id().value().toString()).put("action","CREATE"),null);var preview=json(prepared,201);String path="/"+id+"/writes/"+preview.path("id").asText();json(call("POST",path+"/confirm",null,prepared.header("ETag")),202);idle(id);var current=call("GET",path,null,null);assertEquals("UNKNOWN",json(current,200).path("state").asText());var input=object().put("acknowledgePossibleSeparateImport",true);
        assertEquals(404,call("b",BROWSER,"POST",path+"/leave-unresolved",input,current.header("ETag")).statusCode());assertEquals(403,call("read",BROWSER,"POST",path+"/leave-unresolved",input,current.header("ETag")).statusCode());assertEquals(428,call("POST",path+"/leave-unresolved",input,null).statusCode());assertEquals(422,call("POST",path+"/leave-unresolved",object().put("acknowledgePossibleSeparateImport",false),current.header("ETag")).statusCode());
        var connected=current(id);assertEquals(204,call("DELETE","/"+id,null,etag(connected)).statusCode());var disconnected=current(id);inputs.validate("TaskProviderConnection",disconnected);assertFalse(disconnected.path("enabled").asBoolean());assertEquals(1,disconnected.path("pendingWrites").asInt());assertEquals(404,call("b",BROWSER,"GET","/"+id,null,null).statusCode());assertTrue(store.transaction(owner,()->store.find(TaskProviderConnections.AUTH,UUID.fromString(id)).isEmpty()));
        var result=json(call("POST",path+"/leave-unresolved",input,current.header("ETag")),200);inputs.validate("TaskProviderWrite",result);assertEquals("UNRESOLVED",result.path("state").asText());assertEquals(result,json(call("POST",path+"/leave-unresolved",input,current.header("ETag")),200));assertEquals(1,provider.writes.get());var saved=store.transaction(owner,()->store.find("TASK",task.id().value()).orElseThrow());assertEquals("HTTP task kept unlinked",saved.path("summary").asText());assertTrue(saved.path("externalReferences").isEmpty());
    }

    @Test void sourceIdentityReviewReplacesOnlyAnOwnedSameAccountReferenceWithoutProviderWrites()throws Exception{
        var connection=connect("google");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();selectAndImport(connection,"list-1","list-2");var provider=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());
        var original=store.transaction(owner,()->store.byReference(new net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey("google",TaskCloudPull.repository(connection.path("remoteAccountId").asText(),"list-1"),"task-list-1")).orElseThrow());var target=store.transaction(owner,()->store.list("TASK_LIST").stream().filter(list->list.path("connectionId").asText().equals(id)&&list.path("remoteListId").asText().equals("list-2")).findFirst().orElseThrow());
        provider.created.put("list-2/moved-id",object().put("id","moved-id").put("title","Moved provider task").put("status","needsAction").put("position","002").put("etag","\"moved-1\""));store.transaction(owner,()->{var auth=new TaskProviderConnections(store).required(UUID.fromString(id));auth.put("lastAttempt",store.now().minusSeconds(31).toString());new TaskProviderConnections(store).save(TaskProviderConnections.AUTH,auth);return null;});assertEquals(202,call("POST","/"+id+"/refresh",null,null).statusCode());idle(id);
        String path="/"+id+"/identities/"+target.path("id").asText();var page=json(call("GET",path,null,null),200);inputs.validate("TaskProviderIdentityReviews",page);var row=page.path("items").get(0);assertEquals("moved-id",row.path("externalId").asText());var task=store.transaction(owner,()->store.find("TASK",uuid(original.get("id"))).orElseThrow());var input=object().put("externalId","moved-id").put("decision","REPLACE").put("taskId",task.path("id").asText()).put("taskEtag",etag(task));input.set("previous",object().put("providerId","google").put("repositoryId",TaskCloudPull.repository(connection.path("remoteAccountId").asText(),"list-1")).put("externalId","task-list-1"));
        assertEquals(404,call("b",BROWSER,"GET",path,null,null).statusCode());assertEquals(403,call("read",BROWSER,"POST",path,input,row.path("reviewEtag").asText()).statusCode());assertEquals(428,call("POST",path,input,null).statusCode());assertEquals(412,call("POST",path,input,"\"stale\"").statusCode());var result=json(call("POST",path,input,row.path("reviewEtag").asText()),200);inputs.validate("Task",result);assertEquals(task.path("id"),result.path("id"));assertEquals("Moved provider task",result.path("summary").asText());assertEquals(result,json(call("POST",path,input,row.path("reviewEtag").asText()),200));assertEquals(0,provider.writes.get());assertEquals(2,store.transaction(owner,()->store.list("TASK").stream().filter(value->value.path("externalReferences").toString().contains(TaskCloudPull.repository(connection.path("remoteAccountId").asText(),"list-2"))).toList()).size());
    }

}
