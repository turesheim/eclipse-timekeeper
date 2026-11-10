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
    record Grant(String owner,TaskCloudProvider.Provider provider,String verifier,String settingsHash,boolean writable,String broker,JsonNode services) {
        @Override public String toString(){return "Consumed task-provider authorization grant";}
    }
    record Claim(String owner,UUID id,String lease,TaskCloudProvider.Provider provider,String account,String remoteList,UUID list,boolean discover,boolean catalogOnly,
            String settingsHash,TaskProviderOAuth.Tokens tokens,boolean writable,String generation,boolean forced,String broker,JsonNode services) {
        @Override public String toString(){return "Claimed task-provider refresh";}
    }
    private final ServerStore store;
    TaskProviderConnections(ServerStore store){this.store=store;}
    record SharedAttempt(String state,String challenge){@Override public String toString(){return "Shared sign-in attempt";}}
    SharedAttempt beginShared(TaskCloudProvider.Provider provider,java.net.URI broker,String browser,JsonNode services){
        String binding=binding(browser);Instant now=store.now();
        for(var flow:store.list(FLOW))if(!Instant.parse(flow.path("expires").asText()).isAfter(now))remove(FLOW,flow);
        if(store.list(FLOW).size()>=5)throw new ApiFailure(429,"TASK_PROVIDER_AUTHORIZATION_LIMIT");
        byte[] bytes=new byte[32];var random=new java.security.SecureRandom();random.nextBytes(bytes);String state=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);random.nextBytes(bytes);String verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var value=object().put("provider",provider.name()).put("stateHash",hash(state)).put("browserHash",binding).put("verifier",verifier)
                .put("brokerEndpoint",broker.toString()).put("settingsHash",sharedHash(broker.toString())).put("writable",services.path("writableTasks").asBoolean()).put("expires",now.plusSeconds(600).toString());value.set("services",services.deepCopy());
        insert(FLOW,flowId(state),value);return new SharedAttempt(state,TaskProviderOAuth.challenge(verifier));
    }
    static String sharedHash(String broker){return hash("shared-sign-in\n"+broker);}

    ObjectNode begin(TaskCloudProvider.Provider provider,TaskProviderOAuth.Settings settings,String browser){return begin(provider,settings,browser,false);}
    ObjectNode begin(TaskCloudProvider.Provider provider,TaskProviderOAuth.Settings settings,String browser,boolean writable){
        if(writable&&provider!=TaskCloudProvider.Provider.GOOGLE)throw new ApiFailure(422,"TASK_PROVIDER_DIRECTION_UNSUPPORTED");
        String binding=binding(browser);Instant now=store.now();
        for(var flow:store.list(FLOW))if(!Instant.parse(flow.path("expires").asText()).isAfter(now))remove(FLOW,flow);
        if(store.list(FLOW).size()>=5)throw new ApiFailure(429,"TASK_PROVIDER_AUTHORIZATION_LIMIT");
        var attempt=new TaskProviderOAuth(provider).begin(settings,writable);
        var value=object().put("provider",provider.name()).put("stateHash",hash(attempt.state())).put("browserHash",binding)
                .put("verifier",attempt.verifier()).put("settingsHash",settingsHash(settings)).put("writable",writable).put("expires",now.plusSeconds(600).toString());
        insert(FLOW,flowId(attempt.state()),value);
        return object().put("authorizationUrl",attempt.address()).put("expiresAt",value.path("expires").asText());
    }
    Grant consume(String state,String browser){
        if(state==null||!state.matches("[A-Za-z0-9_-]{43}"))throw new ApiFailure(409,"TASK_PROVIDER_AUTHORIZATION_EXPIRED");
        var value=store.find(FLOW,flowId(state)).orElseThrow(()->new ApiFailure(409,"TASK_PROVIDER_AUTHORIZATION_EXPIRED"));
        if(!equal(value.path("stateHash").asText(),hash(state))||!equal(value.path("browserHash").asText(),binding(browser)))throw new ApiFailure(403,"TASK_PROVIDER_BROWSER_CHANGED");
        if(!Instant.parse(value.path("expires").asText()).isAfter(store.now()))throw new ApiFailure(409,"TASK_PROVIDER_AUTHORIZATION_EXPIRED");
        remove(FLOW,value);
        return new Grant(store.owner(),TaskCloudProvider.Provider.valueOf(value.path("provider").asText()),value.path("verifier").asText(),value.path("settingsHash").asText(),value.path("writable").asBoolean(),optionalText(value.get("brokerEndpoint")).orElse(null),value.has("services")?value.get("services").deepCopy():object().put("tasks",true).put("calendars",false).put("writableTasks",value.path("writable").asBoolean()));
    }
    ObjectNode connect(Grant grant,TaskProviderOAuth.Settings settings,TaskCloudProvider.Account account,TaskProviderOAuth.Tokens tokens,List<TaskCloudProvider.RemoteList> catalog){
        return connect(grant,settingsHash(settings),account,tokens,catalog);
    }
    ObjectNode connectShared(Grant grant,TaskCloudProvider.Account account,TaskProviderOAuth.Tokens tokens,List<TaskCloudProvider.RemoteList> catalog){
        if(grant.broker()==null)throw ApiFailure.conflict();return connect(grant,sharedHash(grant.broker()),account,tokens,catalog);
    }
    private ObjectNode connect(Grant grant,String expectedHash,TaskCloudProvider.Account account,TaskProviderOAuth.Tokens tokens,List<TaskCloudProvider.RemoteList> catalog){
        if(!grant.owner().equals(store.owner()))throw ApiFailure.notFound();
        if(!equal(grant.settingsHash(),expectedHash))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
        if(grant.writable()&&(grant.provider()!=TaskCloudProvider.Provider.GOOGLE||!new TaskProviderOAuth(grant.provider()).canWrite(tokens.scopes())))throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
        var provider=new TaskCloudProvider(grant.provider());var seen=new HashSet<String>();var lists=array();
        if(catalog!=null&&catalog.size()>1000)throw new ApiFailure(422,"TASK_PROVIDER_LIST_LIMIT");
        for(var list:catalog==null?List.<TaskCloudProvider.RemoteList>of():catalog){
            if(!seen.add(list.id()))throw new ApiFailure(409,"TASK_PROVIDER_SCAN_INCOMPLETE");
            var envelope=object().put("namespace",provider.id()+".tasks").put("version",1);envelope.set("payload",list.original());providerData(envelope);
            var item=object().put("id",list.id()).put("name",list.name()).put("kind",list.kind()).put("shared",list.shared()).put("owner",list.owner());item.set("observation",list.original().deepCopy());lists.add(item);
        }
        TaskConnection old=null;
        for(var connection:store.list("TASK_CONNECTION"))if(connection.path("providerId").asText().equals(provider.id())&&connection.path("remoteAccountId").asText().equals(account.id())){old=(TaskConnection)decode("TASK_CONNECTION",connection);break;}
        UUID id=old==null?UUID.randomUUID():old.id();
        var connection=new TaskConnection(id,provider.id(),account.id(),old==null?account.name():old.name(),true,grant.writable(),true,capabilities(provider.capabilities(grant.writable())),old==null?0:old.version()+1);
        store.save("TASK_CONNECTION",connection);store.change("TASK_CONNECTION",old==null?"CREATED":"UPDATED",id.toString());
        for(var list:ownedLists(id).values())if(list.writable()!=grant.writable()){
            var updated=new TaskList(list.id(),list.connectionId(),list.remoteListId(),list.name(),list.kind(),list.readable(),grant.writable(),list.shared(),list.owner(),list.present(),list.position(),list.sharing(),list.providerData(),list.version()+1);
            store.save("TASK_LIST",updated);store.change("TASK_LIST","UPDATED",list.id().toString());
        }
        var previous=store.find(AUTH,id);if(previous.isPresent()&&previous.get().hasNonNull("brokerEndpoint")&&(!Objects.equals(previous.get().path("brokerEndpoint").asText(),grant.broker())||!previous.get().path("refreshToken").asText().equals(tokens.refresh())))new SharedConnectRevocations(store).enqueue(((ObjectNode)previous.get()).deepCopy());
        var value=previous.map(node->((ObjectNode)node).deepCopy()).orElseGet(JsonValues::object);
        value.put("accessToken",tokens.access()).put("refreshToken",tokens.refresh()).put("tokenExpires",tokens.expires().toString()).put("settingsHash",expectedHash);
        if(grant.broker()!=null)value.put("brokerEndpoint",grant.broker());else value.remove("brokerEndpoint");value.set("services",grant.services().deepCopy());
        if(!value.has("calendarSelected"))value.putArray("calendarSelected");if(!value.has("calendarCatalog"))value.putArray("calendarCatalog");if(!value.has("calendarMappings"))value.putObject("calendarMappings");
        if(!grant.services().path("calendars").asBoolean())value.put("calendarPending",false).putNull("calendarRetryUntil");
        value.put("calendarConfigurationVersion",value.path("calendarConfigurationVersion").asLong()+1);
        value.put("direction",grant.writable()?"TWO_WAY":"IMPORT").put("generation",UUID.randomUUID().toString());
        value.put("configurationVersion",value.path("configurationVersion").asLong()+1);
        var scopes=value.putArray("scopes");tokens.scopes().stream().sorted().forEach(scopes::add);if(catalog!=null||!value.has("catalog"))value.set("catalog",lists);
        if(!value.has("selected"))value.putArray("selected");
        if(!value.has("reviewAdditions"))value.put("reviewAdditions",grant.provider()==TaskCloudProvider.Provider.GOOGLE);
        value.putNull("lastError").putNull("lease").putNull("leaseUntil").put("nextRefresh",store.now().toString());
        value.put("roundActive",false).put("roundIndex",0).put("blocked",false).put("requested",!value.path("selected").isEmpty()).put("pendingCount",0);value.putArray("pending");value.putObject("listErrors");
        value=previous.isEmpty()?insert(AUTH,id,value):save(AUTH,value);
        TaskProviderWrites.retire(store,id,value.path("generation").asText());
        return visible(value);
    }
    ObjectNode list(){
        var result=object();var items=result.putArray("items");var present=new HashSet<String>();
        for(var value:store.list(AUTH))if(store.find("TASK_CONNECTION",uuid(value.get("id"))).isPresent()){items.add(visible(value));present.add(value.path("id").asText());}
        for(var value:store.list("TASK_CONNECTION"))if(!present.contains(value.path("id").asText())&&TaskProviderWrites.pending(store,uuid(value.get("id")))>0)items.add(disconnected(value));
        return result;
    }
    ObjectNode view(UUID id){
        var auth=store.find(AUTH,id);if(auth.isPresent())return visible(auth.get());
        var metadata=store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound);if(TaskProviderWrites.pending(store,id)==0)throw ApiFailure.notFound();return disconnected(metadata);
    }
    /** No credential placeholder is persisted: disconnected decisions remain visible using owned public metadata only. */
    private ObjectNode disconnected(JsonNode metadata){
        var result=object();for(String field:List.of("id","ownerId","providerId","remoteAccountId","name","version"))result.set(field,metadata.path(field));
        result.put("configurationVersion",metadata.path("version").asLong()).put("enabled",false).put("paused",true).put("direction","IMPORT").put("syncing",false).put("pendingCount",0).put("pendingWrites",TaskProviderWrites.pending(store,uuid(metadata.get("id"))));
        result.putNull("lastAttempt").putNull("lastSuccess").putNull("nextRefresh").put("lastError","TASK_PROVIDER_RECONNECT_REQUIRED");result.putArray("selectedLists");result.putArray("availableLists");result.putArray("imports");result.putArray("reviews");result.putObject("projectMappings");result.putObject("listErrors");return result;
    }
    ObjectNode required(UUID id){return store.find(AUTH,id).map(node->((ObjectNode)node).deepCopy()).orElseThrow(ApiFailure::notFound);}
    ObjectNode visible(JsonNode value){
        var connection=store.find("TASK_CONNECTION",uuid(value.get("id"))).orElseThrow(ApiFailure::notFound);
        var result=object();for(String field:List.of("id","ownerId","providerId","remoteAccountId","name","enabled"))result.set(field,connection.path(field));
        result.set("version",value.path("version"));result.put("reviewAdditions",connection.path("providerId").asText().equals("google")&&value.path("reviewAdditions").asBoolean(true));
        result.set("services",services(value));
        InternetCalendars.visible(value,result);
        result.put("configurationVersion",value.path("configurationVersion").asLong()).put("direction",value.path("direction").asText("IMPORT")).put("paused",value.path("paused").asBoolean());
        boolean waiting=value.hasNonNull("retryUntil")&&Instant.parse(value.path("retryUntil").asText()).isAfter(store.now());
        result.put("syncing",!value.path("paused").asBoolean()&&!waiting&&(value.hasNonNull("lease")||value.path("roundActive").asBoolean()||value.path("requested").asBoolean())).put("pendingCount",value.path("pendingCount").asInt());
        result.set("listErrors",value.has("listErrors")?value.get("listErrors").deepCopy():object());
        for(String field:List.of("lastAttempt","lastSuccess","lastError","nextRefresh"))result.set(field,value.has(field)?value.get(field):com.fasterxml.jackson.databind.node.NullNode.instance);
        if(value.hasNonNull("nextRefresh")&&value.hasNonNull("retryUntil")&&Instant.parse(value.path("retryUntil").asText()).isAfter(Instant.parse(value.path("nextRefresh").asText())))result.set("nextRefresh",value.get("retryUntil"));
        result.set("selectedLists",value.path("selected").deepCopy());var catalog=result.putArray("availableLists");
        result.set("projectMappings",value.path("projects").isObject()?value.path("projects").deepCopy():object());result.set("imports",TaskProviderScans.previews(store,value));
        var reviews=result.putArray("reviews");for(var scan:store.list(TaskProviderScans.SCAN))if(scan.path("connectionId").equals(value.path("id"))&&scan.path("generation").equals(value.path("generation"))&&scan.path("state").asText().equals("READY")&&TaskProviderScans.approved(value,scan.path("remoteListId").asText()))reviews.add(object().put("listId",scan.path("id").asText()).put("remoteListId",scan.path("remoteListId").asText()));
        result.set("identityReviews",TaskProviderIdentities.descriptors(store,value));
        result.put("pendingWrites",TaskProviderWrites.pending(store,uuid(value.get("id"))));
        for(var list:value.path("catalog")){var item=object();for(String field:List.of("id","name","kind","shared","owner"))item.set(field,list.path(field));catalog.add(item);}
        return result;
    }
    ObjectNode select(UUID id,List<String> selected,String match){return select(id,selected,null,match);}
    ObjectNode select(UUID id,List<String> selected,JsonNode projects,String match){return select(id,selected,projects,match,null);}
    ObjectNode select(UUID id,List<String> selected,JsonNode projects,String match,Boolean reviewAdditions){
        var value=required(id);ApiReplies.match(visible(value),match);
        if(reviewAdditions!=null){if(!store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound).path("providerId").asText().equals("google"))throw ApiFailure.validation();value.put("reviewAdditions",reviewAdditions);}
        if(selected.size()>20||new HashSet<>(selected).size()!=selected.size())throw ApiFailure.validation();
        var chosen=new HashSet<>(selected);var known=new HashSet<String>();
        var mappings=projects==null?(value.path("projects").isObject()?((ObjectNode)value.get("projects")).deepCopy():object()):((ObjectNode)projects).deepCopy();
        if(projects!=null)for(var names=projects.fieldNames();names.hasNext();)if(!chosen.contains(names.next()))throw ApiFailure.validation();
        mappings.retain(selected);for(var project:mappings)if(!project.isNull())store.find("PROJECT",uuid(project)).orElseThrow(ApiFailure::notFound);
        value.set("projects",mappings);var approved=array();for(var remote:value.path("approved"))if(chosen.contains(remote.asText()))approved.add(remote);value.set("approved",approved);
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
            var list=new TaskList(old==null?UUID.randomUUID():old.id(),id,key,remote.path("name").asText(),TaskListKind.valueOf(remote.path("kind").asText()),true,writable(value,connection),
                    remote.path("shared").asBoolean(),remote.path("owner").asBoolean(),true,position++,old==null?TaskListSharing.empty():old.sharing(),Optional.of(providerData(envelope)),old==null?0:old.version()+1);
            store.save("TASK_LIST",list);store.change("TASK_LIST",old==null?"CREATED":"UPDATED",list.id().toString());
        }
        if(!known.containsAll(chosen))throw ApiFailure.validation();
        TaskProviderScans.retain(store,id,chosen);
        var ids=value.putArray("selected");selected.forEach(ids::add);value.put("configurationVersion",value.path("configurationVersion").asLong()+1).put("generation",UUID.randomUUID().toString()).put("nextRefresh",store.now().toString()).putNull("lease").putNull("leaseUntil");
        value.put("roundActive",false).put("roundIndex",0).put("requested",!selected.isEmpty()&&!value.path("blocked").asBoolean()).put("pendingCount",0).putNull("lastSuccess");
        if(!value.path("blocked").asBoolean()&&(!value.hasNonNull("retryUntil")||!Instant.parse(value.path("retryUntil").asText()).isAfter(store.now())))value.putNull("lastError");value.putArray("pending");value.putObject("listErrors");
        var saved=save(AUTH,value);TaskProviderWrites.retire(store,id,saved.path("generation").asText());return visible(saved);
    }
    /** One list per lease bounds work and lets other accounts make progress between large lists. */
    Claim claim(UUID id,boolean force){
        var value=required(id);if(!enabled())return null;
        if(value.path("paused").asBoolean()){if(force)throw new ApiFailure(409,"TASK_PROVIDER_PAUSED");return null;}
        var connection=store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound);
        if(!connection.path("enabled").asBoolean()||!connection.path("readable").asBoolean())throw new ApiFailure(409,"TASK_PROVIDER_DISABLED");
        boolean catalogOnly=!services(value).path("tasks").asBoolean()||value.path("selected").isEmpty();if(catalogOnly&&!force&&!value.path("requested").asBoolean()&&value.path("calendarSelected").isEmpty())return null;
        Instant now=store.now();
        if(value.hasNonNull("retryUntil")&&Instant.parse(value.path("retryUntil").asText()).isAfter(now)){if(force)throw new ApiFailure(429,"TASK_PROVIDER_RATE_LIMITED");return null;}
        if(value.hasNonNull("leaseUntil")&&Instant.parse(value.path("leaseUntil").asText()).isAfter(now))return null;
        if(value.path("blocked").asBoolean()){if(force)throw new ApiFailure(409,value.path("lastError").asText("TASK_PROVIDER_RECONNECT_REQUIRED"));return null;}
        if(force){
            if(value.hasNonNull("lastAttempt")&&Instant.parse(value.path("lastAttempt").asText()).plusSeconds(30).isAfter(now))throw new ApiFailure(429,"TASK_PROVIDER_REFRESH_LIMIT");
            value.put("roundActive",false).put("requested",true);
        }else if(value.hasNonNull("nextRefresh")&&Instant.parse(value.path("nextRefresh").asText()).isAfter(now))return null;
        if(!value.path("roundActive").asBoolean()){
            value.put("roundActive",true).put("forceRound",force||value.path("forceRequested").asBoolean()).put("forceRequested",false).put("roundPreview",false).put("roundIndex",0).put("pendingCount",0).putNull("lastError");value.putArray("pending");value.putObject("listErrors");
        }
        int index=value.path("roundIndex").asInt();if(index<0||!catalogOnly&&index>=value.path("selected").size())throw ApiFailure.conflict();
        String remote=catalogOnly?null:value.path("selected").get(index).asText();UUID listId=null;
        if(!catalogOnly)for(var list:store.list("TASK_LIST"))if(list.path("connectionId").asText().equals(id.toString())&&list.path("remoteListId").asText().equals(remote)){listId=uuid(list.get("id"));break;}
        String lease=UUID.randomUUID().toString();value.put("lease",lease).put("leaseUntil",now.plusSeconds(600).toString()).put("lastAttempt",now.toString()).put("nextRefresh",now.plusSeconds(600).toString());
        save(AUTH,value);var scopes=new HashSet<String>();value.path("scopes").forEach(scope->scopes.add(scope.asText()));
        return new Claim(store.owner(),id,lease,TaskProviderConfiguration.provider(connection.path("providerId").asText()),connection.path("remoteAccountId").asText(),remote,listId,index==0||catalogOnly,catalogOnly,
                value.path("settingsHash").asText(),new TaskProviderOAuth.Tokens(value.path("accessToken").asText(),value.path("refreshToken").asText(),Instant.parse(value.path("tokenExpires").asText()),scopes),writable(value,connection),value.path("generation").asText(),value.path("forceRound").asBoolean(),optionalText(value.get("brokerEndpoint")).orElse(null),services(value));
    }
    ObjectNode active(Claim claim){
        if(!store.owner().equals(claim.owner())||!enabled())return null;
        var value=store.find(AUTH,claim.id());var connection=store.find("TASK_CONNECTION",claim.id());
        return value.isPresent()&&connection.isPresent()&&!value.get().path("paused").asBoolean()&&connection.get().path("enabled").asBoolean()&&connection.get().path("readable").asBoolean()
                &&value.get().path("lease").asText().equals(claim.lease())&&value.get().path("generation").asText().equals(claim.generation())?((ObjectNode)value.get()).deepCopy():null;
    }
    boolean credentials(Claim claim,TaskProviderOAuth.Tokens tokens){
        var value=active(claim);if(value==null)return false;
        if(claim.writable()&&!new TaskProviderOAuth(claim.provider()).canWrite(tokens.scopes()))throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
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
        value.set("catalog",result);if(value.path("listErrors").isObject())((ObjectNode)value.get("listErrors")).remove("");save(AUTH,value);return true;
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
        int next=value.path("roundIndex").asInt()+1;boolean more=!blocked&&!claim.catalogOnly()&&next<value.path("selected").size();
        value.put("roundIndex",more?next:0).put("roundActive",more).put("blocked",blocked).putNull("lease").putNull("leaseUntil");
        boolean queued=TaskProviderWrites.queued(store,claim.id())||services(value).path("calendars").asBoolean()&&value.path("calendarPending").asBoolean();if(!more)value.put("requested",!blocked&&queued);
        if(outcome!=null&&outcome.path("awaitingImport").asBoolean())value.put("roundPreview",true);
        if(!more&&errors.isEmpty()&&!value.path("roundPreview").asBoolean())value.put("lastSuccess",store.now().toString());
        String first=errors.isEmpty()?null:errors.elements().next().asText();if(first==null)value.putNull("lastError");else value.put("lastError",first);
        if(retryAfter>0)value.put("retryUntil",store.now().plusSeconds(retryAfter).toString());else if(errors.isEmpty())value.remove("retryUntil");
        if(blocked)value.putNull("nextRefresh");
        else value.put("nextRefresh",store.now().plusSeconds(Math.max(Math.max(0,retryAfter),more?0:!errors.isEmpty()?60:queued?0:900)).toString());
        if(!blocked&&!more&&services(value).path("calendars").asBoolean()&&value.hasNonNull("calendarRetryUntil")&&Instant.parse(value.path("calendarRetryUntil").asText()).isBefore(Instant.parse(value.path("nextRefresh").asText())))value.set("nextRefresh",value.get("calendarRetryUntil"));
        save(AUTH,value);return more;
    }
    private boolean enabled(){
        try(var query=store.statement("SELECT state FROM tk_account WHERE owner_id=?",store.owner());var rows=query.executeQuery()) {return rows.next()&&!rows.getString(1).equals("DISABLED");}
        catch(java.sql.SQLException failure){throw new ApiFailure(503,"UNAVAILABLE");}
    }
    ObjectNode pause(UUID id,boolean paused,String match){
        var value=required(id);ApiReplies.match(visible(value),match);
        value.put("paused",paused).putNull("lease").putNull("leaseUntil").put("roundActive",false).put("roundIndex",0).put("requested",!paused&&!value.path("blocked").asBoolean()&&(!value.path("selected").isEmpty()||!value.path("calendarSelected").isEmpty()));
        if(paused||value.path("blocked").asBoolean())value.putNull("nextRefresh");else value.put("nextRefresh",store.now().toString());
        return visible(save(AUTH,value));
    }
    void disconnect(UUID id,String match){
        var value=required(id);ApiReplies.match(visible(value),match);
        new SharedConnectRevocations(store).enqueue(value);
        new InternetCalendars(store).disconnect(id);
        var before=store.find("TASK_CONNECTION",id).orElseThrow(ApiFailure::notFound);var connection=(TaskConnection)decode("TASK_CONNECTION",before);
        store.save("TASK_CONNECTION",connection.update(connection.name(),false,false,false));store.change("TASK_CONNECTION","UPDATED",id.toString());TaskProviderScans.retain(store,id,Set.of());remove(AUTH,value);
    }
    static String settingsHash(TaskProviderOAuth.Settings settings){return hash(settings.clientId()+"\n"+settings.redirect()+"\n"+hash(settings.clientSecret()));}
    static boolean writable(JsonNode credentials,JsonNode connection){
        return services(credentials).path("tasks").asBoolean()&&credentials.path("direction").asText().equals("TWO_WAY")&&connection.path("providerId").asText().equals("google")&&connection.path("writable").asBoolean()
                &&java.util.stream.StreamSupport.stream(credentials.path("scopes").spliterator(),false).anyMatch(scope->scope.asText().equals("https://www.googleapis.com/auth/tasks"));
    }
    static ObjectNode services(JsonNode value){return value.path("services").isObject()?((ObjectNode)value.get("services")).deepCopy():object().put("tasks",true).put("calendars",false).put("writableTasks",value.path("direction").asText().equals("TWO_WAY"));}
    private static UUID flowId(String state){return UUID.nameUUIDFromBytes(("dagsverk-task-flow:"+state).getBytes(StandardCharsets.UTF_8));}
    private static boolean equal(String left,String right){return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));}
    private static String binding(String browser){if(browser==null||browser.isBlank()||browser.length()>1024)throw new ApiFailure(403,"TASK_PROVIDER_BROWSER_REQUIRED");return hash(browser);}
    ObjectNode insert(String kind,UUID id,ObjectNode value){value=value.deepCopy();value.put("id",id.toString()).put("ownerId",store.owner()).put("version",0);store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),kind,id,0,value.toString());return value;}
    ObjectNode save(String kind,ObjectNode value){long version=value.path("version").asLong();value=value.deepCopy().put("version",version+1);if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),kind,uuid(value.get("id")),version)!=1)throw new ApiFailure(412,"STALE_VERSION");return value;}
    private void remove(String kind,JsonNode value){if(store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=? AND version=?",store.owner(),kind,uuid(value.get("id")),value.path("version").asLong())!=1)throw new ApiFailure(412,"STALE_VERSION");}
}
