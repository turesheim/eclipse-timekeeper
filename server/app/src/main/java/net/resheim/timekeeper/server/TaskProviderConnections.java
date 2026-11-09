package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;

/** Private, owner-scoped provider grants. Neither resource kind is part of portable account data. */
final class TaskProviderConnections {
    static final String AUTH="TASK_AUTH",FLOW="TASK_OAUTH";
    record Grant(String owner,TaskCloudProvider.Provider provider,String verifier,String settingsHash) {
        @Override public String toString(){return "Consumed task-provider authorization grant";}
    }
    private final ServerStore store;
    TaskProviderConnections(ServerStore store){this.store=store;}

    ObjectNode begin(TaskCloudProvider.Provider provider,TaskProviderOAuth.Settings settings,String browser){
        String binding=binding(browser);Instant now=store.now();
        for(var flow:store.list(FLOW))if(!Instant.parse(flow.path("expires").asText()).isAfter(now))remove(FLOW,flow);
        if(store.list(FLOW).size()>=5)throw new ApiFailure(429,"TASK_PROVIDER_AUTHORIZATION_LIMIT");
        var attempt=new TaskProviderOAuth(provider).begin(settings);
        var value=object().put("provider",provider.name()).put("stateHash",hash(attempt.state())).put("browserHash",binding)
                .put("verifier",attempt.verifier()).put("settingsHash",settingsHash(settings)).put("expires",now.plusSeconds(600).toString());
        insert(FLOW,flowId(attempt.state()),value);
        return object().put("authorizationUrl",attempt.address()).put("expiresAt",value.path("expires").asText());
    }
    Grant consume(String state,String browser){
        if(state==null||!state.matches("[A-Za-z0-9_-]{43}"))throw new ApiFailure(409,"TASK_PROVIDER_AUTHORIZATION_EXPIRED");
        var value=store.find(FLOW,flowId(state)).orElseThrow(()->new ApiFailure(409,"TASK_PROVIDER_AUTHORIZATION_EXPIRED"));
        if(!equal(value.path("stateHash").asText(),hash(state))||!equal(value.path("browserHash").asText(),binding(browser)))throw new ApiFailure(403,"TASK_PROVIDER_BROWSER_CHANGED");
        if(!Instant.parse(value.path("expires").asText()).isAfter(store.now()))throw new ApiFailure(409,"TASK_PROVIDER_AUTHORIZATION_EXPIRED");
        remove(FLOW,value);
        return new Grant(store.owner(),TaskCloudProvider.Provider.valueOf(value.path("provider").asText()),value.path("verifier").asText(),value.path("settingsHash").asText());
    }
    ObjectNode connect(Grant grant,TaskProviderOAuth.Settings settings,TaskCloudProvider.Account account,TaskProviderOAuth.Tokens tokens,List<TaskCloudProvider.RemoteList> catalog){
        if(!grant.owner().equals(store.owner()))throw ApiFailure.notFound();
        if(!equal(grant.settingsHash(),settingsHash(settings)))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
        var provider=new TaskCloudProvider(grant.provider());var seen=new HashSet<String>();var lists=array();
        if(catalog.size()>1000)throw new ApiFailure(422,"TASK_PROVIDER_LIST_LIMIT");
        for(var list:catalog){
            if(!seen.add(list.id()))throw new ApiFailure(409,"TASK_PROVIDER_SCAN_INCOMPLETE");
            var envelope=object().put("namespace",provider.id()+".tasks").put("version",1);envelope.set("payload",list.original());providerData(envelope);
            var item=object().put("id",list.id()).put("name",list.name()).put("kind",list.kind()).put("shared",list.shared()).put("owner",list.owner());item.set("observation",list.original().deepCopy());lists.add(item);
        }
        TaskConnection old=null;
        for(var connection:store.list("TASK_CONNECTION"))if(connection.path("providerId").asText().equals(provider.id())&&connection.path("remoteAccountId").asText().equals(account.id())){old=(TaskConnection)decode("TASK_CONNECTION",connection);break;}
        UUID id=old==null?UUID.randomUUID():old.id();
        var connection=new TaskConnection(id,provider.id(),account.id(),old==null?account.name():old.name(),true,false,true,capabilities(provider.capabilities()),old==null?0:old.version()+1);
        store.save("TASK_CONNECTION",connection);store.change("TASK_CONNECTION",old==null?"CREATED":"UPDATED",id.toString());
        var previous=store.find(AUTH,id);var value=previous.map(node->((ObjectNode)node).deepCopy()).orElseGet(JsonValues::object);
        value.put("accessToken",tokens.access()).put("refreshToken",tokens.refresh()).put("tokenExpires",tokens.expires().toString()).put("settingsHash",settingsHash(settings));
        var scopes=value.putArray("scopes");tokens.scopes().stream().sorted().forEach(scopes::add);value.set("catalog",lists);
        if(!value.has("selected"))value.putArray("selected");
        value.putNull("lastError").putNull("lease").putNull("leaseUntil").put("nextRefresh",store.now().toString());
        value=previous.isEmpty()?insert(AUTH,id,value):save(AUTH,value);
        return visible(value);
    }
    ObjectNode list(){var result=object();var items=result.putArray("items");for(var value:store.list(AUTH))if(store.find("TASK_CONNECTION",uuid(value.get("id"))).isPresent())items.add(visible(value));return result;}
    ObjectNode required(UUID id){return store.find(AUTH,id).map(node->((ObjectNode)node).deepCopy()).orElseThrow(ApiFailure::notFound);}
    ObjectNode visible(JsonNode value){
        var connection=store.find("TASK_CONNECTION",uuid(value.get("id"))).orElseThrow(ApiFailure::notFound);
        var result=object();for(String field:List.of("id","ownerId","providerId","remoteAccountId","name","enabled"))result.set(field,connection.path(field));
        result.set("version",value.path("version"));result.put("direction","IMPORT");
        for(String field:List.of("lastAttempt","lastSuccess","lastError","nextRefresh"))result.set(field,value.has(field)?value.get(field):com.fasterxml.jackson.databind.node.NullNode.instance);
        result.set("selectedLists",value.path("selected").deepCopy());var catalog=result.putArray("availableLists");
        for(var list:value.path("catalog")){var item=object();for(String field:List.of("id","name","kind","shared","owner"))item.set(field,list.path(field));catalog.add(item);}
        return result;
    }
    ObjectNode select(UUID id,List<String> selected,String match){
        var value=required(id);ApiReplies.match(visible(value),match);
        if(selected.size()>20||new HashSet<>(selected).size()!=selected.size())throw ApiFailure.validation();
        var chosen=new HashSet<>(selected);var known=new HashSet<String>();
        var connection=store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound);
        String provider=connection.path("providerId").asText();int position=0;
        for(var remote:value.path("catalog")){
            String key=remote.path("id").asText();known.add(key);if(!chosen.contains(key))continue;
            TaskList old=null;
            for(var list:store.list("TASK_LIST"))if(list.path("connectionId").asText().equals(id.toString())&&list.path("remoteListId").asText().equals(key)){old=(TaskList)decode("TASK_LIST",list);break;}
            var payload=object();payload.set("observation",remote.path("observation").deepCopy());
            if(old!=null&&old.providerData().isPresent()){
                var envelope=old.providerData().get();
                if(!envelope.namespace().equals(provider+".tasks")||envelope.version()!=1)throw ApiFailure.conflict();
                var previous=(ObjectNode)parse(envelope.json());previous.set("observation",remote.path("observation").deepCopy());payload=previous;
            }
            var envelope=object().put("namespace",provider+".tasks").put("version",1);envelope.set("payload",payload);
            var list=new TaskList(old==null?UUID.randomUUID():old.id(),id,key,remote.path("name").asText(),TaskListKind.valueOf(remote.path("kind").asText()),true,false,
                    remote.path("shared").asBoolean(),remote.path("owner").asBoolean(),true,position++,old==null?TaskListSharing.empty():old.sharing(),Optional.of(providerData(envelope)),old==null?0:old.version()+1);
            store.save("TASK_LIST",list);store.change("TASK_LIST",old==null?"CREATED":"UPDATED",list.id().toString());
        }
        if(!known.containsAll(chosen))throw ApiFailure.validation();
        var ids=value.putArray("selected");selected.forEach(ids::add);value.put("nextRefresh",store.now().toString()).putNull("lease").putNull("leaseUntil");
        return visible(save(AUTH,value));
    }
    void disconnect(UUID id,String match){
        var value=required(id);ApiReplies.match(visible(value),match);
        var before=store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound);var connection=(TaskConnection)decode("TASK_CONNECTION",before);
        store.save("TASK_CONNECTION",connection.update(connection.name(),false,false,false));store.change("TASK_CONNECTION","UPDATED",id.toString());remove(AUTH,value);
    }
    static String settingsHash(TaskProviderOAuth.Settings settings){return hash(settings.clientId()+"\n"+settings.redirect()+"\n"+hash(settings.clientSecret()));}
    private static UUID flowId(String state){return UUID.nameUUIDFromBytes(("dagsverk-task-flow:"+state).getBytes(StandardCharsets.UTF_8));}
    private static boolean equal(String left,String right){return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));}
    private static String binding(String browser){if(browser==null||browser.isBlank()||browser.length()>1024)throw new ApiFailure(403,"TASK_PROVIDER_BROWSER_REQUIRED");return hash(browser);}
    private ObjectNode insert(String kind,UUID id,ObjectNode value){value=value.deepCopy();value.put("id",id.toString()).put("ownerId",store.owner()).put("version",0);store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),kind,id,0,value.toString());return value;}
    ObjectNode save(String kind,ObjectNode value){long version=value.path("version").asLong();value=value.deepCopy().put("version",version+1);if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),kind,uuid(value.get("id")),version)!=1)throw new ApiFailure(412,"STALE_VERSION");return value;}
    private void remove(String kind,JsonNode value){if(store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=? AND version=?",store.owner(),kind,uuid(value.get("id")),value.path("version").asLong())!=1)throw new ApiFailure(412,"STALE_VERSION");}
}
