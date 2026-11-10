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
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:task_provider_http","timekeeper.auth.public-origin","https://localhost",
                "timekeeper.tasks.google.client-id","fixture-google-id","timekeeper.tasks.google.client-secret","fixture-google-secret",
                "timekeeper.tasks.microsoft.client-id","fixture-microsoft-id","timekeeper.tasks.microsoft.client-secret","fixture-microsoft-secret");}
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
}
