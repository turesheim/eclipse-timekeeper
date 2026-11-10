package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Calendar selection and durable scans share an account grant, not the task synchronizer's data. */
final class InternetCalendars {
    static final String SCAN="CAL_API_SCAN",CACHE="CAL_API_CACHE";
    private final ServerStore store;
    InternetCalendars(ServerStore store){this.store=store;}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    static UUID id(UUID connection,String remote){return UUID.nameUUIDFromBytes(("dagsverk-internet-calendar:"+connection+"/"+remote).getBytes(StandardCharsets.UTF_8));}
    static void visible(JsonNode auth,ObjectNode result){
        result.set("availableCalendars",auth.path("calendarCatalog").isArray()?auth.get("calendarCatalog").deepCopy():array());
        result.set("selectedCalendars",auth.path("calendarSelected").isArray()?auth.get("calendarSelected").deepCopy():array());
        result.set("calendarMappings",auth.path("calendarMappings").isObject()?auth.get("calendarMappings").deepCopy():object());
        result.put("calendarConfigurationVersion",auth.path("calendarConfigurationVersion").asLong());
        for(String key:List.of("calendarLastAttempt","calendarLastSuccess","calendarLastError","calendarRetryUntil"))result.set(key,auth.has(key)?auth.get(key):com.fasterxml.jackson.databind.node.NullNode.instance);
    }
    void catalog(UUID connection,List<CalendarCloudProvider.RemoteCalendar> remote){
        var auth=connections().required(connection);var catalog=auth.putArray("calendarCatalog");for(var item:remote)catalog.add(item.json());auth.putNull("calendarCatalogError");auth.set("calendarLastError",auth.path("calendarEventError").isTextual()?auth.get("calendarEventError"):com.fasterxml.jackson.databind.node.NullNode.instance);connections().save(TaskProviderConnections.AUTH,auth);
    }
    void error(UUID connection,String code){var auth=connections().required(connection);auth.put("calendarCatalogError",code).put("calendarLastError",auth.path("calendarEventError").asText(code));connections().save(TaskProviderConnections.AUTH,auth);}
    static String failure(RuntimeException error){return error instanceof TaskProviderHttp.Failure provider?provider.getMessage():error instanceof ApiFailure api?api.code:"TASK_PROVIDER_INVALID_RESPONSE";}
    ObjectNode select(UUID connection,JsonNode input,String match){
        var auth=connections().required(connection);ApiReplies.match(connections().visible(auth),match);
        if(!TaskProviderConnections.services(auth).path("calendars").asBoolean())throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
        var selected=new LinkedHashSet<String>();for(var item:input.path("selectedCalendars"))if(!selected.add(item.asText()))throw ApiFailure.validation();if(selected.size()>20)throw ApiFailure.validation();
        var mappings=input.path("calendarMappings");if(!mappings.isObject())throw ApiFailure.validation();
        for(var names=mappings.fieldNames();names.hasNext();)if(!selected.contains(names.next()))throw ApiFailure.validation();
        var known=new HashMap<String,JsonNode>();for(var item:auth.path("calendarCatalog"))known.put(item.path("id").asText(),item);var previousSelection=new HashSet<String>();auth.path("calendarSelected").forEach(item->previousSelection.add(item.asText()));
        var metadata=store.find("TASK_CONNECTION",connection).orElseThrow(ApiFailure::notFound);var provider=TaskProviderConfiguration.provider(metadata.path("providerId").asText());var adapter=new CalendarCloudProvider(provider);var calendars=new CalendarSubscriptions(store);
        for(String remote:selected){
            var item=known.get(remote);if((item==null||!item.path("readable").asBoolean())&&!previousSelection.contains(remote))throw new ApiFailure(409,"TASK_PROVIDER_LIST_MISSING");
            if(!mappings.hasNonNull(remote))throw ApiFailure.validation();
            UUID client=uuid(mappings.get(remote));store.find("CLIENT",client).orElseThrow(ApiFailure::notFound);
            var existing=store.find(CalendarSubscriptions.SUB,id(connection,remote));String name=item!=null?item.path("name").asText():existing.map(value->value.path("name").asText()).orElse(remote);
            calendars.managed(id(connection,remote),connection,remote,name,client,adapter.source(remote));
        }
        for(var item:auth.path("calendarSelected"))if(!selected.contains(item.asText())){
            UUID id=id(connection,item.asText());var sub=store.find(CalendarSubscriptions.SUB,id);if(sub.isPresent())calendars.remove(id,etag(calendars.visible(sub.get())));discard(SCAN,id);discard(CACHE,id);
        }
        var ids=auth.putArray("calendarSelected");selected.forEach(ids::add);auth.set("calendarMappings",mappings.deepCopy());
        auth.put("calendarConfigurationVersion",auth.path("calendarConfigurationVersion").asLong()+1).put("calendarPending",!selected.isEmpty()).putNull("calendarLastError").putNull("calendarLastSuccess").putNull("calendarRetryUntil").put("requested",!selected.isEmpty()||!auth.path("selected").isEmpty()).put("nextRefresh",store.now().toString());
        return connections().visible(connections().save(TaskProviderConnections.AUTH,auth));
    }
    void disconnect(UUID connection){
        var auth=connections().required(connection);var calendars=new CalendarSubscriptions(store);
        for(var remote:auth.path("calendarSelected")){UUID id=id(connection,remote.asText());var sub=store.find(CalendarSubscriptions.SUB,id);if(sub.isPresent())calendars.remove(id,etag(calendars.visible(sub.get())));discard(SCAN,id);discard(CACHE,id);}
    }
    void removeSubscription(UUID subscription,String match){
        var calendars=new CalendarSubscriptions(store);var sub=calendars.required(subscription);ApiReplies.match(calendars.visible(sub),match);UUID connection=uuid(sub.get("managedConnectionId"));var auth=connections().required(connection);String remote=sub.path("remoteCalendarId").asText();
        var ids=array();for(var item:auth.path("calendarSelected"))if(!item.asText().equals(remote))ids.add(item);auth.set("calendarSelected",ids);var mappings=auth.path("calendarMappings").isObject()?((ObjectNode)auth.get("calendarMappings")).deepCopy():object();mappings.remove(remote);auth.set("calendarMappings",mappings);auth.put("calendarConfigurationVersion",auth.path("calendarConfigurationVersion").asLong()+1);connections().save(TaskProviderConnections.AUTH,auth);
        calendars.remove(subscription,match);discard(SCAN,subscription);discard(CACHE,subscription);
    }
    /** A scan can resume after a server restart without publishing a partial calendar. */
    void synchronize(TaskProviderConnections.Claim claim,CalendarCloudProvider provider,String token){
        var initial=store.transaction(claim.owner(),()->connections().active(claim));if(initial==null||!claim.services().path("calendars").asBoolean())return;
        if(initial.hasNonNull("calendarRetryUntil")&&Instant.parse(initial.path("calendarRetryUntil").asText()).isAfter(store.now()))return;
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();boolean pending=false;String firstError=null;int retry=0;
        for(var item:initial.path("calendarSelected")){
            String remote=item.asText();UUID id=id(claim.id(),remote);
            if(System.nanoTime()>deadline){pending=true;break;}
            try{
                var scan=store.transaction(claim.owner(),()->begin(claim,remote,id));if(scan==null)continue;
                while(scan.path("state").asText().equals("FETCHING")){
                    if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted()){pending=true;break;}
                    CalendarCloudProvider.Page page;
                    try{page=provider.events(token,remote,optionalText(scan.get("cursor")).orElse(null),optionalText(scan.get("syncToken")).orElse(null));}
                    catch(TaskProviderHttp.Failure failure){
                        if(failure.status==410&&scan.hasNonNull("syncToken")){scan=store.transaction(claim.owner(),()->{discard(SCAN,id);return start(claim,remote,id,true);});continue;}throw failure;
                    }
                    var expected=scan;scan=store.transaction(claim.owner(),()->acknowledge(claim,remote,id,expected,page));if(scan==null)break;
                }
                if(scan==null||scan.path("state").asText().equals("FETCHING"))continue;
                while(scan.path("masterIndex").asInt()<scan.path("masters").size()){
                    if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted()){pending=true;break;}
                    int index=scan.path("masterIndex").asInt();String master=scan.path("masters").get(index).asText();var details=provider.series(token,remote,master);var expected=scan;
                    scan=store.transaction(claim.owner(),()->{var current=current(claim,remote,id,expected);if(current==null)return null;((ObjectNode)current.get("events")).set(master,details);bounded(current.path("events"));current.put("masterIndex",index+1);return connections().save(SCAN,current);});if(scan==null)break;
                }
                if(scan==null||scan.path("masterIndex").asInt()<scan.path("masters").size())continue;
                var records=new ArrayList<JsonNode>();scan.path("events").forEach(records::add);String body=InternetCalendarCodec.feed(claim.provider(),records);var completed=scan;
                store.transaction(claim.owner(),()->{finish(claim,remote,id,completed,body);return null;});
            }catch(TaskProviderHttp.Failure failure){
                if(firstError==null)firstError=failure.getMessage();retry=Math.max(retry,Math.max(60,failure.retryAfterSeconds));calendarFailure(claim,id);
            }catch(ApiFailure failure){if(firstError==null)firstError=failure.code;retry=Math.max(retry,60);calendarFailure(claim,id);}
        }
        boolean more=pending;String error=firstError;int after=retry;
        store.transaction(claim.owner(),()->{var auth=connections().active(claim);if(auth==null)return null;auth.put("calendarPending",more).put("calendarLastAttempt",store.now().toString());
            if(error==null){auth.putNull("calendarEventError").putNull("calendarRetryUntil");auth.set("calendarLastError",auth.path("calendarCatalogError").isTextual()?auth.get("calendarCatalogError"):com.fasterxml.jackson.databind.node.NullNode.instance);if(!more&&!auth.path("calendarSelected").isEmpty())auth.put("calendarLastSuccess",store.now().toString());}
            else auth.put("calendarEventError",error).put("calendarLastError",error).put("calendarRetryUntil",store.now().plusSeconds(after).toString());connections().save(TaskProviderConnections.AUTH,auth);return null;});
    }
    private ObjectNode begin(TaskProviderConnections.Claim claim,String remote,UUID id){
        if(!selected(claim,remote)||store.find(CalendarSubscriptions.SUB,id).isEmpty())return null;new CalendarSubscriptions(store).managedAttempt(id,claim.id());
        var saved=store.find(SCAN,id);if(saved.isPresent()&&saved.get().path("generation").asText().equals(claim.generation()))return ((ObjectNode)saved.get()).deepCopy();
        discard(SCAN,id);return start(claim,remote,id,false);
    }
    private ObjectNode start(TaskProviderConnections.Claim claim,String remote,UUID id,boolean forceFull){
        var cache=store.find(CACHE,id);boolean full=forceFull||claim.provider()!=TaskCloudProvider.Provider.GOOGLE||cache.isEmpty()||!cache.get().hasNonNull("syncToken")||!cache.get().hasNonNull("lastFull")||Instant.parse(cache.get().path("lastFull").asText()).plusSeconds(86400).isBefore(store.now());
        var value=object().put("generation",claim.generation()).put("remoteCalendarId",remote).put("state","FETCHING").put("full",full).putNull("cursor").putNull("nextSyncToken").put("masterIndex",0);value.putArray("masters");value.putArray("pages");
        value.set("events",full?object():cache.get().path("events").deepCopy());if(full)value.putNull("syncToken");else value.set("syncToken",cache.get().get("syncToken"));
        return connections().insert(SCAN,id,value);
    }
    private ObjectNode acknowledge(TaskProviderConnections.Claim claim,String remote,UUID id,ObjectNode expected,CalendarCloudProvider.Page page){
        var current=current(claim,remote,id,expected);if(current==null)return null;var events=(ObjectNode)current.get("events");var masters=(com.fasterxml.jackson.databind.node.ArrayNode)current.get("masters");
        for(var event:page.events()){
            String key=event.path("id").asText();if(claim.provider()==TaskCloudProvider.Provider.GOOGLE&&event.path("status").asText().equals("cancelled")&&!event.hasNonNull("recurringEventId"))events.remove(key);else events.set(key,event.deepCopy());
            if(claim.provider()==TaskCloudProvider.Provider.MICROSOFT&&event.path("type").asText().equals("seriesMaster")){boolean present=false;for(var item:masters)if(item.asText().equals(key))present=true;if(!present)masters.add(key);}
        }
        bounded(events);
        String next=page.next();if(next!=null){for(var previous:current.path("pages"))if(previous.asText().equals(next))throw new ApiFailure(409,"TASK_PROVIDER_SCAN_INCOMPLETE");((com.fasterxml.jackson.databind.node.ArrayNode)current.get("pages")).add(next);current.put("cursor",next);}
        else{if(claim.provider()==TaskCloudProvider.Provider.GOOGLE&&page.syncToken()==null)throw new ApiFailure(409,"TASK_PROVIDER_SCAN_INCOMPLETE");current.put("state","READY").putNull("cursor").put("nextSyncToken",page.syncToken());}
        return connections().save(SCAN,current);
    }
    private ObjectNode current(TaskProviderConnections.Claim claim,String remote,UUID id,ObjectNode expected){
        if(!selected(claim,remote))return null;var current=store.find(SCAN,id);if(current.isEmpty()||current.get().path("version").asLong()!=expected.path("version").asLong()||!current.get().path("generation").asText().equals(claim.generation()))return null;return ((ObjectNode)current.get()).deepCopy();
    }
    private void finish(TaskProviderConnections.Claim claim,String remote,UUID id,ObjectNode scan,String body){
        if(current(claim,remote,id,scan)==null)return;var previous=store.find(CACHE,id);var cache=object();cache.set("events",scan.get("events").deepCopy());cache.set("syncToken",scan.path("nextSyncToken"));
        cache.put("lastFull",scan.path("full").asBoolean()?store.now().toString():previous.orElseThrow().path("lastFull").asText());
        if(previous.isEmpty())connections().insert(CACHE,id,cache);else{var before=((ObjectNode)previous.get()).deepCopy();before.setAll(cache);connections().save(CACHE,before);}
        new CalendarSubscriptions(store).managedFinish(id,claim.id(),body,null);discard(SCAN,id);
    }
    private boolean selected(TaskProviderConnections.Claim claim,String remote){var auth=connections().active(claim);if(auth==null||!TaskProviderConnections.services(auth).path("calendars").asBoolean())return false;for(var item:auth.path("calendarSelected"))if(item.asText().equals(remote))return true;return false;}
    private static void bounded(JsonNode events){long definitions=events.size();for(var event:events)if(event.path("exceptionOccurrences").isArray())definitions+=event.path("exceptionOccurrences").size();if(definitions>5000||events.toString().getBytes(StandardCharsets.UTF_8).length>32*1024*1024)throw new ApiFailure(422,"TASK_PROVIDER_RESPONSE_TOO_LARGE");}
    private void calendarFailure(TaskProviderConnections.Claim claim,UUID id){store.transaction(claim.owner(),()->{if(connections().active(claim)!=null)new CalendarSubscriptions(store).managedFinish(id,claim.id(),null,"CALENDAR_PROVIDER_UNAVAILABLE");return null;});}
    private void discard(String kind,UUID id){store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),kind,id);}
}
