package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import net.resheim.eclipse.timekeeper.domain.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(TaskProviderConnectionsTest.Profile.class)
class TaskProviderConnectionsTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:task_provider_connections");}
    }
    @Inject ServerStore store;
    @Inject CommandReceipts receipts;
    @Inject ContractInputs inputs;
    private String owner(){return "provider-owner-"+UUID.randomUUID();}
    private <T>T tx(String owner,Supplier<T> work){return store.transaction(owner,work);}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    private TaskProviderOAuth.Settings settings(){return new TaskProviderOAuth.Settings("client-id","private-client-secret","https://dagsverk.example.invalid/web/task-provider-callback.html");}
    private String begin(String owner,String browser){
        var result=tx(owner,()->connections().begin(TaskCloudProvider.Provider.GOOGLE,settings(),browser));
        return Arrays.stream(URI.create(result.path("authorizationUrl").asText()).getRawQuery().split("&")).filter(pair->pair.startsWith("state=")).map(pair->URLDecoder.decode(pair.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    private TaskProviderConnections.Grant grant(String owner){return tx(owner,()->connections().consume(begin(owner,"browser-a"),"browser-a"));}
    private TaskProviderOAuth.Tokens tokens(){return new TaskProviderOAuth.Tokens("private-access","private-refresh",Instant.now().plusSeconds(3600),Set.of("openid","https://www.googleapis.com/auth/tasks.readonly"));}
    private List<TaskCloudProvider.RemoteList> catalog(){return List.of(new TaskCloudProvider.RemoteList("remote-list","Work","STANDARD",false,true,parse("{\"id\":\"remote-list\",\"title\":\"Work\",\"unknown\":\"private-provider-detail\"}")));}
    private ObjectNode connect(String owner,TaskProviderConnections.Grant grant){return tx(owner,()->connections().connect(grant,settings(),new TaskCloudProvider.Account("stable-account","Account name"),tokens(),catalog()));}

    @Test void stateIsOwnerAndBrowserBoundAndCanOnlyBeConsumedOnce(){
        String owner=owner(),state=begin(owner,"browser-a"),other=owner();
        assertThrows(ApiFailure.class,()->tx(other,()->connections().consume(state,"browser-a")));
        assertThrows(ApiFailure.class,()->tx(owner,()->connections().consume(state,"browser-b")));
        assertEquals(1,tx(owner,()->store.list(TaskProviderConnections.FLOW).size()));
        var grant=tx(owner,()->connections().consume(state,"browser-a"));assertEquals(owner,grant.owner());assertEquals(TaskCloudProvider.Provider.GOOGLE,grant.provider());
        assertFalse(grant.toString().contains(grant.verifier()));
        assertThrows(ApiFailure.class,()->tx(owner,()->connections().consume(state,"browser-a")));
    }
    @Test void expiredAuthorizationIsRejectedAndPrunedBeforeTheNextAttempt(){
        String owner=owner(),state=begin(owner,"browser-a");
        tx(owner,()->{var row=((ObjectNode)store.list(TaskProviderConnections.FLOW).getFirst()).deepCopy();row.put("expires",store.now().minusSeconds(1).toString());connections().save(TaskProviderConnections.FLOW,row);return null;});
        assertThrows(ApiFailure.class,()->tx(owner,()->connections().consume(state,"browser-a")));
        begin(owner,"browser-a");assertEquals(1,tx(owner,()->store.list(TaskProviderConnections.FLOW).size()));
    }
    @Test void outstandingAuthorizationAttemptsAreBoundedAndRequireABrowser(){
        String owner=owner();assertThrows(ApiFailure.class,()->begin(owner,null));
        for(int i=0;i<5;i++)begin(owner,"browser-a");assertThrows(ApiFailure.class,()->begin(owner,"browser-a"));
        assertEquals(5,tx(owner,()->store.list(TaskProviderConnections.FLOW).size()));
    }
    @Test void changedOperatorConfigurationOrOwnerCannotCompleteTheGrant(){
        String owner=owner();var grant=grant(owner);String other=owner();
        var changed=new TaskProviderOAuth.Settings("another-client","another-private-secret",settings().redirect());
        assertThrows(ApiFailure.class,()->tx(owner,()->connections().connect(grant,changed,new TaskCloudProvider.Account("account","Name"),tokens(),catalog())));
        assertThrows(ApiFailure.class,()->connect(other,grant));
        assertTrue(tx(owner,()->store.list("TASK_CONNECTION").isEmpty()));assertTrue(tx(other,()->store.list(TaskProviderConnections.AUTH).isEmpty()));
    }
    @Test void credentialsAndPrivateCatalogDataNeverAppearInVisibleConnectionSnapshots(){
        String owner=owner();var result=connect(owner,grant(owner));String id=result.path("id").asText();
        for(String secret:List.of("private-access","private-refresh","private-client-secret","private-provider-detail","settingsHash","scopes","observation"))assertFalse(result.toString().contains(secret));
        assertEquals("IMPORT",result.path("direction").asText());assertTrue(result.path("selectedLists").isEmpty());assertEquals("Work",result.path("availableLists").get(0).path("name").asText());
        tx(owner,()->{var stored=connections().required(UUID.fromString(id));assertEquals("private-access",stored.path("accessToken").asText());assertEquals("private-refresh",stored.path("refreshToken").asText());return null;});
        assertTrue(tx(owner(),()->connections().list().path("items").isEmpty()));
    }
    @Test void reconnectingTheSameProviderAccountRetainsIdentityListSelectionAndCustomName(){
        String owner=owner();var initial=connect(owner,grant(owner));UUID id=uuid(initial.get("id"));
        var selected=tx(owner,()->connections().select(id,List.of("remote-list"),etag(initial)));
        tx(owner,()->{var before=(TaskConnection)decode("TASK_CONNECTION",store.find("TASK_CONNECTION",id).orElseThrow());store.save("TASK_CONNECTION",before.update("Custom name",true,false,true));return null;});
        var again=connect(owner,grant(owner));assertEquals(initial.get("id"),again.get("id"));assertEquals("Custom name",again.path("name").asText());assertEquals(selected.get("selectedLists"),again.get("selectedLists"));
        assertEquals(1,tx(owner,()->store.list("TASK_CONNECTION").size()));assertEquals(1,tx(owner,()->store.list(TaskProviderConnections.AUTH).size()));
    }
    private TaskProviderConnections.Grant writableGrant(String owner){
        var attempt=tx(owner,()->connections().begin(TaskCloudProvider.Provider.GOOGLE,settings(),"browser-a",true));
        var address=URI.create(attempt.path("authorizationUrl").asText());
        var values=new HashMap<String,String>();for(String pair:address.getRawQuery().split("&")){var parts=pair.split("=",2);values.put(parts[0],URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}
        assertTrue(Arrays.asList(values.get("scope").split(" ")).contains("https://www.googleapis.com/auth/tasks"));
        return tx(owner,()->connections().consume(values.get("state"),"browser-a"));
    }
    @Test void twoWayRequiresTheBoundConsentAndActualWriteScopeAndReauthorizationCanDowngradeIt(){
        String owner=owner();var grant=writableGrant(owner);assertTrue(grant.writable());
        assertEquals("TASK_PROVIDER_PERMISSION_REQUIRED",assertThrows(ApiFailure.class,()->connect(owner,grant)).code);
        assertTrue(tx(owner,()->store.list("TASK_CONNECTION").isEmpty()));
        var broad=new TaskProviderOAuth.Tokens("private-write-access","private-refresh",Instant.now().plusSeconds(3600),Set.of("openid","https://www.googleapis.com/auth/tasks"));
        var result=tx(owner,()->connections().connect(grant,settings(),new TaskCloudProvider.Account("stable-account","Name"),broad,catalog()));UUID id=uuid(result.get("id"));
        assertEquals("TWO_WAY",result.path("direction").asText());
        tx(owner,()->connections().select(id,List.of("remote-list"),etag(result)));
        var claim=tx(owner,()->connections().claim(id,false));assertTrue(claim.writable());
        tx(owner,()->{var row=store.find("TASK_CONNECTION",id).orElseThrow();assertTrue(row.path("writable").asBoolean());
            var fields=row.path("capabilities").path("fields");assertTrue(fields.path("summary").path("writable").asBoolean());assertFalse(fields.path("parentTaskId").path("writable").asBoolean());
            assertTrue(store.list("TASK_LIST").getFirst().path("writable").asBoolean());return null;});
        // A Google grant may include scopes from earlier consent. An import-only flow still stays import-only.
        var readGrant=grant(owner);var readonly=tx(owner,()->connections().connect(readGrant,settings(),new TaskCloudProvider.Account("stable-account","Name"),broad,catalog()));
        assertEquals(id.toString(),readonly.path("id").asText());assertEquals("IMPORT",readonly.path("direction").asText());
        tx(owner,()->{assertNull(connections().active(claim));assertFalse(store.find("TASK_CONNECTION",id).orElseThrow().path("writable").asBoolean());assertFalse(store.list("TASK_LIST").getFirst().path("writable").asBoolean());return null;});
        assertFalse(tx(owner,()->connections().claim(id,false)).writable());
    }
    @Test void configurationGenerationSurvivesTokenRotationAndStatusUpdatesButNotChangedSelection(){
        String owner=owner();var connected=connect(owner,grant(owner));UUID id=uuid(connected.get("id"));
        var selected=tx(owner,()->connections().select(id,List.of("remote-list"),etag(connected)));var claim=tx(owner,()->connections().claim(id,false));
        tx(owner,()->{assertTrue(connections().credentials(claim,tokens()));assertNotNull(connections().active(claim));assertEquals(claim.generation(),connections().required(id).path("generation").asText());return null;});
        var latest=tx(owner,()->connections().visible(connections().required(id)));
        tx(owner,()->connections().select(id,List.of(),etag(latest)));
        tx(owner,()->{assertNull(connections().active(claim));assertNotEquals(claim.generation(),connections().required(id).path("generation").asText());return null;});
        assertThrows(ApiFailure.class,()->tx(owner(),()->connections().required(id)));
    }
    @Test void unsupportedProviderCannotAcquireATwoWayFlow(){
        String owner=owner();assertThrows(ApiFailure.class,()->tx(owner,()->connections().begin(TaskCloudProvider.Provider.MICROSOFT,settings(),"browser-a",true)));
        assertTrue(tx(owner,()->store.list(TaskProviderConnections.FLOW).isEmpty()));
    }
    @Test void listSelectionChecksEtagAndCatalogAndRollsBackUnknownSelections(){
        String owner=owner();var result=connect(owner,grant(owner));UUID id=uuid(result.get("id"));
        assertThrows(ApiFailure.class,()->tx(owner,()->connections().select(id,List.of("remote-list"),"\"stale\"")));
        assertThrows(ApiFailure.class,()->tx(owner,()->connections().select(id,List.of("remote-list","unknown-list"),etag(result))));
        assertTrue(tx(owner,()->store.list("TASK_LIST").isEmpty()));
        var selected=tx(owner,()->connections().select(id,List.of("remote-list"),etag(result)));
        assertEquals("remote-list",selected.path("selectedLists").get(0).asText());
        tx(owner,()->{var list=store.list("TASK_LIST").getFirst();assertFalse(list.path("writable").asBoolean());assertEquals("private-provider-detail",list.path("providerData").path("payload").path("observation").path("unknown").asText());return null;});
        assertThrows(ApiFailure.class,()->tx(owner,()->connections().select(id,List.of(),etag(result))));
    }
    @Test void disconnectErasesCredentialsAndDisablesTheConnectionWithoutRemovingImportedTasks(){
        String owner=owner();var result=connect(owner,grant(owner));UUID id=uuid(result.get("id"));
        var selected=tx(owner,()->connections().select(id,List.of("remote-list"),etag(result)));
        tx(owner,()->{
            var remote=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(parse("{\"id\":\"task\",\"title\":\"Keep local work\",\"status\":\"needsAction\",\"position\":\"001\"}"));
            new TaskCloudPull(store,receipts,inputs).apply(id,uuid(store.list("TASK_LIST").getFirst().get("id")),new TaskCloudPull.Scan("google","stable-account","remote-list",List.of(remote),store.now()));
            connections().disconnect(id,etag(selected));return null;
        });
        tx(owner,()->{assertTrue(store.find(TaskProviderConnections.AUTH,id).isEmpty());assertFalse(store.find("TASK_CONNECTION",id).orElseThrow().path("enabled").asBoolean());assertEquals(1,store.list("TASK").size());assertEquals(1,store.list("TASK_LIST").size());return null;});
    }
    @Test void concurrentReplicasCanOnlyClaimOneRefresh()throws Exception{
        String owner=owner();var connection=connect(owner,grant(owner));UUID id=uuid(connection.get("id"));
        tx(owner,()->connections().select(id,List.of("remote-list"),etag(connection)));
        var start=new java.util.concurrent.CyclicBarrier(2);
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<java.util.concurrent.Future<TaskProviderConnections.Claim>>();
            for(int i=0;i<2;i++)jobs.add(workers.submit(()->{start.await();return tx(owner,()->connections().claim(id,false));}));
            int claims=0;for(var job:jobs)if(job.get()!=null)claims++;assertEquals(1,claims);
        }
    }
    @Test void changedSelectionExpiredLeaseAndReconnectFenceOldCredentialsAndResults(){
        String owner=owner();var connection=connect(owner,grant(owner));UUID id=uuid(connection.get("id"));
        var selected=tx(owner,()->connections().select(id,List.of("remote-list"),etag(connection)));
        var first=tx(owner,()->connections().claim(id,false));assertNotNull(first);
        assertFalse(first.toString().contains("private-access"));
        tx(owner,()->{var value=connections().required(id);value.put("leaseUntil",store.now().minusSeconds(1).toString()).put("nextRefresh",store.now().toString());connections().save(TaskProviderConnections.AUTH,value);return null;});
        var second=tx(owner,()->connections().claim(id,false));assertNotNull(second);assertNotEquals(first.lease(),second.lease());
        tx(owner,()->{assertFalse(connections().credentials(first,tokens()));assertFalse(connections().finish(first,null,"TASK_PROVIDER_UNAVAILABLE",0));return null;});
        var before=tx(owner,()->connections().visible(connections().required(id)));
        tx(owner,()->connections().select(id,List.of(),etag(before)));
        tx(owner,()->{assertNull(connections().active(second));assertFalse(connections().credentials(second,tokens()));return null;});
        var reconnected=connect(owner,grant(owner));assertEquals(id.toString(),reconnected.path("id").asText());assertFalse(reconnected.path("syncing").asBoolean());
        tx(owner,()->{assertFalse(connections().finish(second,object(),null,0));return null;});
    }
    @Test void aRefreshRoundAdvancesListsAndOnlyPublishesSuccessWhenAllSelectedListsFinish(){
        String owner=owner();var remote=new ArrayList<>(catalog());remote.add(new TaskCloudProvider.RemoteList("second-list","Home","STANDARD",false,true,parse("{\"id\":\"second-list\",\"title\":\"Home\"}")));
        var grant=grant(owner);var connection=tx(owner,()->connections().connect(grant,settings(),new TaskCloudProvider.Account("stable-account","Name"),tokens(),remote));UUID id=uuid(connection.get("id"));
        tx(owner,()->connections().select(id,List.of("remote-list","second-list"),etag(connection)));
        var first=tx(owner,()->connections().claim(id,false));assertEquals("remote-list",first.remoteList());
        assertTrue(tx(owner,()->connections().finish(first,object(),null,0)));
        var partial=tx(owner,()->connections().visible(connections().required(id)));assertTrue(partial.path("syncing").asBoolean());assertTrue(partial.path("lastSuccess").isNull());
        var second=tx(owner,()->connections().claim(id,false));assertEquals("second-list",second.remoteList());
        assertFalse(tx(owner,()->connections().finish(second,object(),null,0)));
        var complete=tx(owner,()->connections().visible(connections().required(id)));assertFalse(complete.path("syncing").asBoolean());assertTrue(complete.path("lastSuccess").isTextual());
        assertNull(tx(owner,()->connections().claim(id,false)));
    }
    @Test void disabledAccountOrRevokedProviderAccessCannotPublishOrContinuePulls(){
        String owner=owner();var connection=connect(owner,grant(owner));UUID id=uuid(connection.get("id"));
        tx(owner,()->connections().select(id,List.of("remote-list"),etag(connection)));var claim=tx(owner,()->connections().claim(id,false));
        tx(owner,()->{store.update("UPDATE tk_account SET username=?,password_hash='fixture-hash',state='DISABLED' WHERE owner_id=?","user-"+owner,owner);assertNull(connections().active(claim));assertFalse(connections().finish(claim,object(),null,0));return null;});
        tx(owner,()->{store.update("UPDATE tk_account SET username=NULL,password_hash=NULL,state='UNCLAIMED' WHERE owner_id=?",owner);connections().finish(claim,null,"TASK_PROVIDER_RECONNECT_REQUIRED",0);return null;});
        assertNull(tx(owner,()->connections().claim(id,false)));assertThrows(ApiFailure.class,()->tx(owner,()->connections().claim(id,true)));
        var visible=tx(owner,()->connections().visible(connections().required(id)));assertFalse(visible.path("syncing").asBoolean());assertEquals("TASK_PROVIDER_RECONNECT_REQUIRED",visible.path("lastError").asText());
    }
    @Test void expiredCatalogLeaseIsRetryableWithoutSelectedLists(){
        String owner=owner();var connection=connect(owner,grant(owner));UUID id=uuid(connection.get("id"));
        var first=tx(owner,()->connections().claim(id,true));assertTrue(first.catalogOnly());
        tx(owner,()->{var value=connections().required(id);value.put("leaseUntil",store.now().minusSeconds(1).toString()).put("nextRefresh",store.now().toString());connections().save(TaskProviderConnections.AUTH,value);return null;});
        var retried=tx(owner,()->connections().claim(id,false));assertNotNull(retried);assertTrue(retried.catalogOnly());assertNotEquals(first.lease(),retried.lease());
        assertFalse(tx(owner,()->connections().finish(retried,object(),null,0)));
        assertFalse(tx(owner,()->connections().visible(connections().required(id))).path("syncing").asBoolean());
    }
    @Test void blockedConnectionsKeepSelectionsWithoutRemainingQueued(){
        String owner=owner();var connection=connect(owner,grant(owner));UUID id=uuid(connection.get("id"));
        tx(owner,()->connections().select(id,List.of("remote-list"),etag(connection)));var claim=tx(owner,()->connections().claim(id,false));
        tx(owner,()->connections().finish(claim,null,"TASK_PROVIDER_RECONNECT_REQUIRED",0));
        var blocked=tx(owner,()->connections().visible(connections().required(id)));
        var selected=tx(owner,()->connections().select(id,List.of("remote-list"),etag(blocked)));
        assertFalse(selected.path("syncing").asBoolean());assertEquals("TASK_PROVIDER_RECONNECT_REQUIRED",selected.path("lastError").asText());assertEquals(blocked.get("selectedLists"),selected.get("selectedLists"));
        var paused=tx(owner,()->connections().pause(id,true,etag(selected)));var resumed=tx(owner,()->connections().pause(id,false,etag(paused)));
        assertFalse(resumed.path("syncing").asBoolean());assertTrue(resumed.path("nextRefresh").isNull());assertEquals("TASK_PROVIDER_RECONNECT_REQUIRED",resumed.path("lastError").asText());
    }
    @Test void providerRetryAfterAlsoFencesManualRefreshAndNewlyQueuedSelections(){
        String owner=owner();var connection=connect(owner,grant(owner));UUID id=uuid(connection.get("id"));
        tx(owner,()->connections().select(id,List.of("remote-list"),etag(connection)));var claim=tx(owner,()->connections().claim(id,false));
        tx(owner,()->connections().finish(claim,null,"TASK_PROVIDER_RATE_LIMITED",3600));
        var before=tx(owner,()->connections().visible(connections().required(id)));String retry=before.path("nextRefresh").asText();
        var changed=tx(owner,()->connections().select(id,List.of("remote-list"),etag(before)));assertEquals(retry,changed.path("nextRefresh").asText());
        assertNull(tx(owner,()->connections().claim(id,false)));assertEquals(429,assertThrows(ApiFailure.class,()->tx(owner,()->connections().claim(id,true))).status);
        tx(owner,()->{var value=connections().required(id);value.put("retryUntil",store.now().minusSeconds(1).toString());connections().save(TaskProviderConnections.AUTH,value);return null;});assertNotNull(tx(owner,()->connections().claim(id,false)));
    }
}
