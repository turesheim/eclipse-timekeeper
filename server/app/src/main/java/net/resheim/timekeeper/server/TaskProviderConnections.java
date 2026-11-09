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
    record Claim(String owner,UUID id,String lease,TaskCloudProvider.Provider provider,String account,String remoteList,UUID list,boolean discover,boolean catalogOnly,
            String settingsHash,TaskProviderOAuth.Tokens tokens) {
        @Override public String toString(){return "Claimed task-provider refresh";}
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
        value.put("roundActive",false).put("roundIndex",0).put("blocked",false).put("requested",!value.path("selected").isEmpty()).put("pendingCount",0);value.putArray("pending");value.putObject("listErrors");
        value=previous.isEmpty()?insert(AUTH,id,value):save(AUTH,value);
        return visible(value);
    }
    ObjectNode list(){var result=object();var items=result.putArray("items");for(var value:store.list(AUTH))if(store.find("TASK_CONNECTION",uuid(value.get("id"))).isPresent())items.add(visible(value));return result;}
    ObjectNode required(UUID id){return store.find(AUTH,id).map(node->((ObjectNode)node).deepCopy()).orElseThrow(ApiFailure::notFound);}
    ObjectNode visible(JsonNode value){
        var connection=store.find("TASK_CONNECTION",uuid(value.get("id"))).orElseThrow(ApiFailure::notFound);
        var result=object();for(String field:List.of("id","ownerId","providerId","remoteAccountId","name","enabled"))result.set(field,connection.path(field));
        result.set("version",value.path("version"));result.put("direction","IMPORT");
        result.put("syncing",value.hasNonNull("lease")||value.path("roundActive").asBoolean()||value.path("requested").asBoolean()).put("pendingCount",value.path("pendingCount").asInt());
        result.set("listErrors",value.has("listErrors")?value.get("listErrors").deepCopy():object());
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
        if(!connection.path("enabled").asBoolean()||!connection.path("readable").asBoolean())throw new ApiFailure(409,"TASK_PROVIDER_DISABLED");
        String provider=connection.path("providerId").asText();int position=0;var existingLists=ownedLists(id);
        for(var remote:value.path("catalog")){
            String key=remote.path("id").asText();known.add(key);if(!chosen.contains(key))continue;
            TaskList old=existingLists.get(key);
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
        value.put("roundActive",false).put("roundIndex",0).put("requested",!selected.isEmpty()&&!value.path("blocked").asBoolean()).put("pendingCount",0).putNull("lastSuccess");
        if(!value.path("blocked").asBoolean())value.putNull("lastError");value.putArray("pending");value.putObject("listErrors");
        return visible(save(AUTH,value));
    }
    /** One list per lease bounds work and lets other accounts make progress between large lists. */
    Claim claim(UUID id,boolean force){
        var value=required(id);if(!enabled())return null;
        var connection=store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound);
        if(!connection.path("enabled").asBoolean()||!connection.path("readable").asBoolean())throw new ApiFailure(409,"TASK_PROVIDER_DISABLED");
        boolean catalogOnly=value.path("selected").isEmpty();if(catalogOnly&&!force&&!value.path("requested").asBoolean())return null;
        Instant now=store.now();
        if(value.hasNonNull("leaseUntil")&&Instant.parse(value.path("leaseUntil").asText()).isAfter(now))return null;
        if(value.path("blocked").asBoolean()){if(force)throw new ApiFailure(409,value.path("lastError").asText("TASK_PROVIDER_RECONNECT_REQUIRED"));return null;}
        if(force){
            if(value.hasNonNull("lastAttempt")&&Instant.parse(value.path("lastAttempt").asText()).plusSeconds(30).isAfter(now))throw new ApiFailure(429,"TASK_PROVIDER_REFRESH_LIMIT");
            value.put("roundActive",false).put("requested",true);
        }else if(value.hasNonNull("nextRefresh")&&Instant.parse(value.path("nextRefresh").asText()).isAfter(now))return null;
        if(!value.path("roundActive").asBoolean()){
            value.put("roundActive",true).put("roundIndex",0).put("pendingCount",0).putNull("lastError");value.putArray("pending");value.putObject("listErrors");
        }
        int index=value.path("roundIndex").asInt();if(index<0||!catalogOnly&&index>=value.path("selected").size())throw ApiFailure.conflict();
        String remote=catalogOnly?null:value.path("selected").get(index).asText();UUID listId=null;
        if(!catalogOnly)for(var list:store.list("TASK_LIST"))if(list.path("connectionId").asText().equals(id.toString())&&list.path("remoteListId").asText().equals(remote)){listId=uuid(list.get("id"));break;}
        String lease=UUID.randomUUID().toString();value.put("lease",lease).put("leaseUntil",now.plusSeconds(600).toString()).put("lastAttempt",now.toString()).put("nextRefresh",now.plusSeconds(600).toString());
        save(AUTH,value);var scopes=new HashSet<String>();value.path("scopes").forEach(scope->scopes.add(scope.asText()));
        return new Claim(store.owner(),id,lease,TaskProviderConfiguration.provider(connection.path("providerId").asText()),connection.path("remoteAccountId").asText(),remote,listId,index==0||catalogOnly,catalogOnly,
                value.path("settingsHash").asText(),new TaskProviderOAuth.Tokens(value.path("accessToken").asText(),value.path("refreshToken").asText(),Instant.parse(value.path("tokenExpires").asText()),scopes));
    }
    ObjectNode active(Claim claim){
        if(!store.owner().equals(claim.owner())||!enabled())return null;
        var value=store.find(AUTH,claim.id());var connection=store.find("TASK_CONNECTION",claim.id());
        return value.isPresent()&&connection.isPresent()&&connection.get().path("enabled").asBoolean()&&connection.get().path("readable").asBoolean()
                &&value.get().path("lease").asText().equals(claim.lease())?((ObjectNode)value.get()).deepCopy():null;
    }
    boolean credentials(Claim claim,TaskProviderOAuth.Tokens tokens){
        var value=active(claim);if(value==null)return false;
        value.put("accessToken",tokens.access()).put("refreshToken",tokens.refresh()).put("tokenExpires",tokens.expires().toString());
        var scopes=value.putArray("scopes");tokens.scopes().stream().sorted().forEach(scopes::add);save(AUTH,value);return true;
    }
    boolean catalog(Claim claim,List<TaskCloudProvider.RemoteList> catalog){
        var value=active(claim);if(value==null)return false;var result=array();var existingLists=ownedLists(claim.id());
        for(var remote:catalog){
            var item=object().put("id",remote.id()).put("name",remote.name()).put("kind",remote.kind()).put("shared",remote.shared()).put("owner",remote.owner());item.set("observation",remote.original().deepCopy());result.add(item);
            var old=existingLists.get(remote.id());if(old!=null){
                var payload=object();
                if(old.providerData().isPresent()){
                    var previous=old.providerData().get();if(!previous.namespace().equals(claim.provider().name().toLowerCase(Locale.ROOT)+".tasks")||previous.version()!=1)throw ApiFailure.conflict();payload=(ObjectNode)parse(previous.json());
                }
                payload.set("observation",remote.original().deepCopy());var envelope=object().put("namespace",claim.provider().name().toLowerCase(Locale.ROOT)+".tasks").put("version",1);envelope.set("payload",payload);var data=providerData(envelope);
                boolean same=old.name().equals(remote.name().trim())&&old.kind().name().equals(remote.kind())&&old.shared()==remote.shared()&&old.owner()==remote.owner()&&old.providerData().isPresent()&&parse(old.providerData().get().json()).equals(payload);
                if(!same){var updated=new TaskList(old.id(),old.connectionId(),old.remoteListId(),remote.name(),TaskListKind.valueOf(remote.kind()),old.readable(),old.writable(),remote.shared(),remote.owner(),old.present(),old.position(),old.sharing(),Optional.of(data),old.version()+1);store.save("TASK_LIST",updated);store.change("TASK_LIST","UPDATED",updated.id().toString());}
            }
        }
        value.set("catalog",result);save(AUTH,value);return true;
    }
    private Map<String,TaskList> ownedLists(UUID connection){
        var result=new HashMap<String,TaskList>();for(var list:store.list("TASK_LIST"))if(list.path("connectionId").asText().equals(connection.toString()))result.put(list.path("remoteListId").asText(),(TaskList)decode("TASK_LIST",list));return result;
    }
    boolean finish(Claim claim,ObjectNode outcome,String error,int retryAfter){
        var value=active(claim);if(value==null)return false;
        var errors=value.path("listErrors").isObject()?((ObjectNode)value.get("listErrors")).deepCopy():object();
        int pending=outcome==null?0:outcome.path("pending").size();
        if(pending>0)error="TASK_PROVIDER_CONFLICTS";
        String errorKey=claim.remoteList()==null?"":claim.remoteList();if(error!=null)errors.put(errorKey,error);else errors.remove(errorKey);value.set("listErrors",errors);
        value.put("pendingCount",value.path("pendingCount").asInt()+pending);
        if(outcome!=null){var saved=(com.fasterxml.jackson.databind.node.ArrayNode)value.withArray("pending");for(var item:outcome.path("pending"))if(saved.size()<100){var row=((ObjectNode)item).deepCopy();row.remove("observation");row.put("remoteListId",claim.remoteList());saved.add(row);}}
        boolean blocked=error!=null&&Set.of("TASK_PROVIDER_RECONNECT_REQUIRED","TASK_PROVIDER_OFFLINE_ACCESS_REQUIRED","TASK_PROVIDER_PERMISSION_REQUIRED","TASK_PROVIDER_ACCOUNT_CHANGED","TASK_PROVIDER_CONFIGURATION_CHANGED").contains(error);
        int next=value.path("roundIndex").asInt()+1;boolean more=!blocked&&next<value.path("selected").size();
        value.put("roundIndex",more?next:0).put("roundActive",more).put("blocked",blocked).putNull("lease").putNull("leaseUntil");
        if(!more)value.put("requested",false);
        if(!more&&errors.isEmpty())value.put("lastSuccess",store.now().toString());
        String first=errors.isEmpty()?null:errors.elements().next().asText();if(first==null)value.putNull("lastError");else value.put("lastError",first);
        if(blocked)value.putNull("nextRefresh");
        else value.put("nextRefresh",store.now().plusSeconds(Math.max(Math.max(0,Math.min(900,retryAfter)),more?0:errors.isEmpty()?900:60)).toString());
        save(AUTH,value);return more;
    }
    private boolean enabled(){
        try(var query=store.statement("SELECT state FROM tk_account WHERE owner_id=?",store.owner());var rows=query.executeQuery()) {return rows.next()&&!rows.getString(1).equals("DISABLED");}
        catch(java.sql.SQLException failure){throw new ApiFailure(503,"UNAVAILABLE");}
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
