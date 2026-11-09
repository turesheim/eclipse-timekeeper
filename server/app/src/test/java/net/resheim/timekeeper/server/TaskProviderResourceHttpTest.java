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
    private ObjectNode current(String id){return json(call("GET","/"+id,null,null),200);}
    private ObjectNode select(ObjectNode value,String...lists){var input=object();var selected=input.putArray("selectedLists");Arrays.stream(lists).forEach(selected::add);return json(call("PUT","/"+value.path("id").asText()+"/lists",input,etag(value)),200);}
    private ObjectNode idle(String id)throws InterruptedException{
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
        do{var value=current(id);if(!value.path("syncing").asBoolean())return value;Thread.sleep(25);}while(System.nanoTime()<deadline);
        fail("Provider refresh did not finish");return null;
    }
    @AfterEach void disconnect(){for(String id:created)for(int attempt=0;attempt<3;attempt++){var before=call("GET","/"+id,null,null);if(before.statusCode()==404)break;var reply=call("DELETE","/"+id,null,before.header("ETag"));if(reply.statusCode()==204)break;assertEquals(412,reply.statusCode(),reply.asString());}}
    @Test void googleAndMicrosoftSignInSelectionAndBackgroundPullCreateOwnedNativeTasks()throws Exception{
        for(String provider:List.of("google","microsoft")){
            var connection=connect(provider);String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();select(connection,"list-1","list-2");
            var complete=idle(id);inputs.validate("TaskProviderConnection",complete);assertTrue(complete.path("lastError").isNull());assertTrue(complete.path("lastSuccess").isTextual());
            assertEquals(2,TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText()).reads.get());
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
        var connection=connect("google");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();select(connection,"list-1");var first=idle(id);
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
    @Test void expiredTokensRefreshAndListRenamesDoNotRewriteUnchangedTasks()throws Exception{
        var connection=connect("microsoft");String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();select(connection,"list-1");idle(id);
        var key=new net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey("microsoft",TaskCloudPull.repository(connection.path("remoteAccountId").asText(),"list-1"),"task-list-1");
        var before=store.transaction(owner,()->store.byReference(key).orElseThrow());
        var state=TaskProviderHttpTestFixture.STATES.get(connection.path("remoteAccountId").asText());state.listName="Renamed list";
        store.transaction(owner,()->{var c=new TaskProviderConnections(store);var value=c.required(UUID.fromString(id));value.put("lastAttempt",store.now().minusSeconds(31).toString()).put("tokenExpires",store.now().minusSeconds(1).toString());c.save(TaskProviderConnections.AUTH,value);return null;});
        assertEquals(202,call("POST","/"+id+"/refresh",null,null).statusCode());var result=idle(id);assertTrue(result.path("lastError").isNull());assertEquals(1,state.refreshes.get());
        assertEquals("Renamed list",result.path("availableLists").get(0).path("name").asText());
        store.transaction(owner,()->{assertEquals(before,store.byReference(key).orElseThrow());var list=store.list("TASK_LIST").stream().filter(row->row.path("connectionId").asText().equals(id)).findFirst().orElseThrow();assertEquals("Renamed list",list.path("name").asText());return null;});
    }
}
