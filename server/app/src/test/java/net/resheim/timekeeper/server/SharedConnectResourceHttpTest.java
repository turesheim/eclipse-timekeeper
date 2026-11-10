package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(SharedConnectResourceHttpTest.Profile.class)
class SharedConnectResourceHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){var config=TaskProviderTestDatabase.settings("shared_connect_http");config.putAll(Map.of("timekeeper.auth.public-origin","https://localhost","timekeeper.connect.shared","true","timekeeper.connect.service-url","https://connect.example.test"));return config;}
        public Set<Class<?>> getEnabledAlternatives(){return Set.of(Fixture.class);}
    }
    record Pending(String state,String challenge,String provider,ObjectNode services,String credential,String account,String code){}
    @Alternative @ApplicationScoped
    public static class Fixture extends TaskProviderHttpTestFixture {
        static final Map<String,Pending> PENDING=new ConcurrentHashMap<>(),GRANTS=new ConcurrentHashMap<>();
        static final AtomicInteger REFRESHES=new AtomicInteger(),CALENDAR_READS=new AtomicInteger(),REVOCATIONS=new AtomicInteger();
        static volatile boolean badTarget,failRevocation;
        @Override SharedConnectClient shared(URI endpoint){return new SharedConnectClient(endpoint,new TaskProviderHttp(Set.of("connect.example.test"),request->{
            String path=request.uri().getPath();
            if(path.equals("/v1/requests")){
                var input=parse(bytes(request));assertFalse(input.has("clientId"));assertFalse(input.has("clientSecret"));assertEquals("https://localhost/web/task-provider-callback.html",input.path("returnUri").asText());
                String state=input.path("state").asText(),id=TaskProviderOAuth.challenge(state),credential=TaskProviderOAuth.challenge("grant:"+state),account=hash(state).substring(0,24),code=TaskProviderOAuth.challenge("code:"+state);var services=(ObjectNode)input.path("services").deepCopy();
                PENDING.put(id,new Pending(state,input.path("challenge").asText(),input.path("provider").asText(),services,credential,account,code));return reply(200,object().put("authorizationUrl",badTarget?"https://unrelated.example/connect/"+id:endpoint+"/connect/"+id).put("expiresAt",Instant.now().plusSeconds(600).toString()).toString());
            }
            if(path.equals("/v1/exchanges")){
                var input=parse(bytes(request));var found=PENDING.entrySet().stream().filter(entry->entry.getValue().code().equals(input.path("code").asText())).findFirst().orElseThrow();var pending=found.getValue();assertEquals(pending.challenge(),TaskProviderOAuth.challenge(input.path("verifier").asText()));PENDING.remove(found.getKey());GRANTS.put(pending.credential(),pending);STATES.put(pending.account(),new State());return reply(200,bundle(pending).put("grantCredential",pending.credential()).toString());
            }
            String credential=request.headers().firstValue("Authorization").orElseThrow().substring(7);var grant=GRANTS.get(credential);if(grant==null)return reply(401,"{}");
            if(path.equals("/v1/grants/refresh")){REFRESHES.incrementAndGet();return reply(200,bundle(grant).toString());}
            if(path.equals("/v1/grants/current")&&request.method().equals("DELETE")){REVOCATIONS.incrementAndGet();if(failRevocation)return reply(503,"{}");GRANTS.remove(credential);return reply(204,"{}");}
            throw new AssertionError("Unexpected shared service endpoint");
        }));}
        private static ObjectNode bundle(Pending value){var result=object().put("provider",value.provider()).put("accessToken","fixture-access-"+value.account()).put("expiresAt",Instant.now().plusSeconds(3600).toString()).put("remoteAccountId",value.account()).put("name","Google fixture");result.set("services",value.services());result.putArray("scopes").add("openid").add("https://www.googleapis.com/auth/tasks.readonly").add("https://www.googleapis.com/auth/calendar.readonly");return result;}
        @Override CalendarCloudProvider calendars(TaskCloudProvider.Provider provider){return new CalendarCloudProvider(provider,new TaskProviderHttp(Set.of("www.googleapis.com"),request->{
            CALENDAR_READS.incrementAndGet();var page=object();if(request.uri().getPath().endsWith("calendarList"))page.putArray("items").add(object().put("id","calendar").put("summary","Calendar").put("accessRole","reader"));
            else{page.put("nextSyncToken","complete");var event=object().put("id","meeting").put("summary","Meeting");event.set("start",object().put("date","2026-11-10"));event.set("end",object().put("date","2026-11-11"));page.putArray("items").add(event);}return reply(200,page.toString());
        }));}
    }
    @Inject ServerStore store;
    @Inject ContractInputs inputs;
    @Inject TaskProviderRefresh refresh;
    private static final String BROWSER="s".repeat(43);
    private Response call(String owner,String browser,String method,String path,ObjectNode body,String match){var request=given().header("Authorization","Bearer test-"+owner).header("Cookie",BrowserCookies.BROWSER+"="+browser).contentType("application/json");if(body!=null)request.body(body.toString());if(match!=null)request.header("If-Match",match);return request.request(method,"/api/v1/task-providers"+path);}
    private Response call(String method,String path,ObjectNode body,String match){return call("a",BROWSER,method,path,body,match);}
    private ObjectNode json(Response response,int status){assertEquals(status,response.statusCode(),response.asString());return (ObjectNode)parse(response.asString());}
    private Pending begin(boolean tasks,boolean calendars){var input=object();input.set("services",object().put("tasks",tasks).put("calendars",calendars));var value=json(call("POST","/google/authorize",input,null),200);inputs.validate("TaskProviderAuthorization",value);assertEquals("https://connect.example.test",value.path("authorizationOrigin").asText());return Fixture.PENDING.get(URI.create(value.path("authorizationUrl").asText()).getPath().substring("/connect/".length()));}
    private ObjectNode complete(Pending pending){var value=json(call("POST","/complete",object().put("state",pending.state()).put("code",pending.code()),null),200);inputs.validate("TaskProviderConnection",value);return value;}
    private ObjectNode idle(String id)throws InterruptedException{long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();do{var value=json(call("GET","/"+id,null,null),200);if(!value.path("syncing").asBoolean())return value;Thread.sleep(25);}while(System.nanoTime()<deadline);fail("Shared account did not finish refreshing");return null;}
    @Test void ordinaryAccountsConnectThroughTheSharedServiceWithoutLocalAppRegistration()throws Exception{
        var providers=json(call("GET","",null,null),200);inputs.validate("TaskProviderConnections",providers);assertTrue(providers.path("calendarSynchronization").asBoolean());assertTrue(providers.path("providers").path("google").asBoolean());assertTrue(providers.path("providers").path("microsoft").asBoolean());
        var pending=begin(true,true);var connection=complete(pending);assertEquals(1,connection.path("availableCalendars").size());assertEquals(2,connection.path("availableLists").size());assertFalse(connection.toString().contains(pending.credential()));
        String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();store.transaction(owner,()->{var auth=new TaskProviderConnections(store).required(UUID.fromString(id));assertEquals(pending.credential(),auth.path("refreshToken").asText());assertEquals("https://connect.example.test",auth.path("brokerEndpoint").asText());auth.put("tokenExpires",Instant.now().minusSeconds(1).toString()).put("lastAttempt",Instant.now().minusSeconds(31).toString());new TaskProviderConnections(store).save(TaskProviderConnections.AUTH,auth);return null;});
        int before=Fixture.REFRESHES.get();json(call("POST","/"+id+"/refresh",null,null),202);assertTrue(idle(id).path("lastError").isNull());assertEquals(before+1,Fixture.REFRESHES.get());
    }
    @Test void theSharedHandoffStillRequiresTheOriginalDagsverkOwnerAndBrowser(){
        var pending=begin(false,true);var input=object().put("state",pending.state()).put("code",pending.code());assertEquals(409,call("b",BROWSER,"POST","/complete",input,null).statusCode());assertEquals(403,call("a","q".repeat(43),"POST","/complete",input,null).statusCode());
        var connection=complete(pending);assertEquals(0,connection.path("availableLists").size());assertEquals(409,call("POST","/complete",input,null).statusCode());assertEquals(404,call("b",BROWSER,"GET","/"+connection.path("id").asText(),null,null).statusCode());assertEquals(403,call("read",BROWSER,"POST","/google/authorize",object(),null).statusCode());
    }
    @Test void aSharedServiceCannotRedirectTheBrowserToAnUnrelatedOrigin(){Fixture.badTarget=true;try{var response=call("POST","/google/authorize",object(),null);assertEquals(503,response.statusCode());assertEquals("TASK_PROVIDER_INVALID_RESPONSE",response.jsonPath().getString("code"));}finally{Fixture.badTarget=false;}}
    @Test void aTaskCatalogFailureDoesNotPreventTheSeparateCalendarSynchronizer()throws Exception{
        var pending=begin(true,true);var connection=complete(pending);String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();UUID client=store.transaction(owner,()->uuid(store.createClient(object().put("name","Calendar client")).get("id")));
        var selection=object();selection.putArray("selectedCalendars").add("calendar");selection.putObject("calendarMappings").put("calendar",client.toString());Fixture.STATES.get(pending.account()).catalogFailure=true;
        try{json(call("PUT","/"+id+"/calendars",selection,etag(connection)),200);var result=idle(id);assertEquals("TASK_PROVIDER_UNAVAILABLE",result.path("lastError").asText());assertTrue(result.path("calendarLastError").isNull());assertTrue(result.path("calendarLastSuccess").isTextual());store.transaction(owner,()->{assertEquals(1,new CalendarSubscriptions(store).events("2026-11-09","2026-11-16").path("items").size());return null;});}
        finally{Fixture.STATES.get(pending.account()).catalogFailure=false;}
    }
    @Test void disconnectWorksWhileTheServiceIsUnavailableAndRetriesRevocationLater()throws InterruptedException{
        var pending=begin(true,false);var connection=complete(pending);String id=connection.path("id").asText(),owner=connection.path("ownerId").asText();int before=Fixture.REVOCATIONS.get();Fixture.failRevocation=true;
        try{
            assertEquals(204,call("DELETE","/"+id,null,etag(connection)).statusCode());assertEquals(404,call("GET","/"+id,null,null).statusCode());
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();while(Fixture.REVOCATIONS.get()==before&&System.nanoTime()<deadline)Thread.sleep(20);assertTrue(Fixture.REVOCATIONS.get()>before);
            store.transaction(owner,()->{assertTrue(store.find(TaskProviderConnections.AUTH,UUID.fromString(id)).isEmpty());assertTrue(store.list(SharedConnectRevocations.KIND).stream().anyMatch(value->value.path("credential").asText().equals(pending.credential())));return null;});
        }finally{Fixture.failRevocation=false;}
        store.transaction(owner,()->{for(var value:store.list(SharedConnectRevocations.KIND))if(value.path("credential").asText().equals(pending.credential())){var record=((ObjectNode)value).deepCopy();record.put("nextAttempt",Instant.now().minusSeconds(1).toString()).putNull("leaseUntil");new TaskProviderConnections(store).save(SharedConnectRevocations.KIND,record);}return null;});refresh.wake();
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();while(Fixture.GRANTS.containsKey(pending.credential())&&System.nanoTime()<deadline)Thread.sleep(20);assertFalse(Fixture.GRANTS.containsKey(pending.credential()));
    }
}
