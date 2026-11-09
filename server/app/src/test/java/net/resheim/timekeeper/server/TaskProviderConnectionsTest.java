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
}
