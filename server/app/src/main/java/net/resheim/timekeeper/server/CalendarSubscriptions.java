package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.CreateActivity;

/** Private calendar configuration, cached feeds and permanent conversion identity share owned JDBC storage. */
final class CalendarSubscriptions {
    static final String SUB="CAL_SUB",FEED="CAL_FEED",LINK="CAL_LINK";
    static final int REFRESH_MINUTES=15;
    private final ServerStore store;
    CalendarSubscriptions(ServerStore store){this.store=store;}
    private ObjectNode required(String kind,UUID id){return store.find(kind,id).map(value->((ObjectNode)value).deepCopy()).orElseThrow(ApiFailure::notFound);}
    ObjectNode required(UUID id){return required(SUB,id);}
    private ObjectNode insert(String kind,UUID id,ObjectNode value){
        value=value.deepCopy();value.put("id",id.toString()).put("ownerId",store.owner()).put("version",0);
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),kind,id,0,value.toString());return value;
    }
    private ObjectNode save(String kind,ObjectNode value){
        long previous=value.path("version").asLong();value=value.deepCopy();value.put("version",Math.addExact(previous,1));
        if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",previous+1,value.toString(),store.owner(),kind,uuid(value.get("id")),previous)!=1)throw new ApiFailure(412,"STALE_VERSION");return value;
    }
    ObjectNode visible(JsonNode value){
        var result=object();for(String field:List.of("id","ownerId","version","name","clientId","visible","createdAt","lastAttempt","lastSuccess","lastError","nextRefresh"))result.set(field,value.path(field));
        try{result.put("origin",new java.net.URI(value.get("url").asText()).getHost());}catch(Exception failure){throw ApiFailure.conflict();}return result;
    }
    ObjectNode list(){var result=object().put("refreshMinutes",REFRESH_MINUTES);var items=result.putArray("items");store.list(SUB).stream().sorted(Comparator.comparing(value->value.path("name").asText(),String.CASE_INSENSITIVE_ORDER)).forEach(value->items.add(visible(value)));return result;}
    ObjectNode create(JsonNode input,CalendarDownload policy){
        if(store.list(SUB).size()>=20)throw new ApiFailure(422,"CALENDAR_SUBSCRIPTION_LIMIT");
        var value=object().put("createdAt",store.now().toString()).putNull("lastAttempt").putNull("lastSuccess").putNull("lastError").put("nextRefresh",store.now().toString());
        configuration(value,input,policy,true);UUID id=UUID.randomUUID();value=insert(SUB,id,value);clientRelation(id,uuid(value.get("clientId")));return value;
    }
    private void configuration(ObjectNode value,JsonNode input,CalendarDownload policy,boolean create){
        store.find("CLIENT",uuid(input.get("clientId"))).orElseThrow(ApiFailure::notFound);
        String url=input.path("url").asText("");if(create||!url.isBlank()) {
            try{value.put("url",policy.address(url).toString());}catch(CalendarFailure failure){throw new ApiFailure(422,failure.code);}
        }
        for(var existing:store.list(SUB))if(!existing.path("id").asText().equals(value.path("id").asText())&&java.net.URI.create(existing.path("url").asText()).normalize().equals(java.net.URI.create(value.path("url").asText()).normalize()))throw new ApiFailure(409,"CALENDAR_DUPLICATE_SUBSCRIPTION");
        value.put("name",input.get("name").asText().strip()).set("clientId",input.get("clientId"));value.set("visible",input.get("visible"));
        if(value.path("name").asText().isEmpty())throw ApiFailure.validation();
    }
    private void clientRelation(UUID id,UUID client){store.update("INSERT INTO tk_relation(owner_id,from_type,from_id,kind,to_type,to_id) VALUES(?,'CAL_SUB',?,'client','CLIENT',?)",store.owner(),id,client);}
    ObjectNode edit(UUID id,String match,JsonNode input,CalendarDownload policy){
        var value=required(id);ApiReplies.match(visible(value),match);String previous=value.path("url").asText();configuration(value,input,policy,false);
        if(!previous.equals(value.path("url").asText())) {
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),FEED,id);
            value.putNull("lastAttempt").putNull("lastSuccess").putNull("lastError");
            value.put("nextRefresh",store.now().toString());
        }
        store.update("DELETE FROM tk_relation WHERE owner_id=? AND from_type=? AND from_id=?",store.owner(),SUB,id);clientRelation(id,uuid(value.get("clientId")));
        return save(SUB,value);
    }
    void remove(UUID id,String match){
        var value=required(id);ApiReplies.match(visible(value),match);store.delete(SUB,id);
        store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),FEED,id);
        // Conversion tombstones deliberately outlive subscriptions and activities.
    }
    private static String configuration(JsonNode value){return hash(value.path("url").asText());}
    record Claim(String owner,UUID id,String lease,String configuration,String url,String body,String etag,String modified){}
    private boolean enabled(){try(var query=store.statement("SELECT state FROM tk_account WHERE owner_id=?",store.owner());var rows=query.executeQuery()){return rows.next()&&!rows.getString(1).equals("DISABLED");}catch(SQLException failure){throw new ApiFailure(503,"UNAVAILABLE");}}
    Claim claim(UUID id,boolean force){
        var value=required(id);if(!enabled())return null;Instant now=store.now();
        var previous=store.find(FEED,id);var feed=previous.map(node->((ObjectNode)node).deepCopy()).orElseGet(JsonValues::object);
        if(feed.hasNonNull("leaseUntil")&&Instant.parse(feed.get("leaseUntil").asText()).isAfter(now))return null;
        if(!force&&value.hasNonNull("nextRefresh")&&Instant.parse(value.get("nextRefresh").asText()).isAfter(now))return null;
        if(force&&value.hasNonNull("lastAttempt")&&Instant.parse(value.get("lastAttempt").asText()).plusSeconds(30).isAfter(now))throw new ApiFailure(429,"CALENDAR_REFRESH_LIMIT");
        String lease=UUID.randomUUID().toString();feed.put("lease",lease).put("leaseUntil",now.plusSeconds(90).toString());
        if(previous.isEmpty())feed=insert(FEED,id,feed);else feed=save(FEED,feed);
        value.put("lastAttempt",now.toString()).put("nextRefresh",now.plusSeconds(REFRESH_MINUTES*60L).toString());value=save(SUB,value);
        return new Claim(store.owner(),id,lease,configuration(value),value.get("url").asText(),optionalText(feed.get("body")).orElse(null),optionalText(feed.get("etag")).orElse(null),optionalText(feed.get("modified")).orElse(null));
    }
    void finish(Claim claim,CalendarDownload.Result result,String error){
        if(!store.owner().equals(claim.owner()))throw ApiFailure.notFound();
        var metadata=store.find(SUB,claim.id());var cached=store.find(FEED,claim.id());if(metadata.isEmpty()||cached.isEmpty()||!enabled())return;
        var value=((ObjectNode)metadata.get()).deepCopy();var feed=((ObjectNode)cached.get()).deepCopy();
        if(!configuration(value).equals(claim.configuration())||!claim.lease().equals(feed.path("lease").asText()))return;
        feed.remove(List.of("lease","leaseUntil"));
        if(error==null){
            if(!result.unchanged())feed.put("body",result.body()).put("etag",result.etag()).put("modified",result.modified());
            value.put("lastSuccess",store.now().toString()).putNull("lastError");
        }else value.put("lastError",error);
        save(FEED,feed);save(SUB,value);
    }
    private static LocalDate[] window(String first,String last){
        try{var from=LocalDate.parse(first);var to=LocalDate.parse(last);if(!to.isAfter(from)||ChronoUnit.DAYS.between(from,to)>31)throw new IllegalArgumentException();return new LocalDate[]{from,to};}
        catch(Exception failure){throw ApiFailure.badRequest();}
    }
    private Map<String,ObjectNode> links(){var result=new HashMap<String,ObjectNode>();for(var link:store.list(LINK))result.put(link.path("subscriptionId").asText()+"/"+link.path("eventKey").asText(),(ObjectNode)link);return result;}
    private List<ObjectNode> occurrences(JsonNode subscription,LocalDate first,LocalDate last){
        var feed=store.find(FEED,uuid(subscription.get("id")));if(feed.isEmpty()||!feed.get().hasNonNull("body"))return List.of();
        var client=store.find("CLIENT",uuid(subscription.get("clientId"))).orElseThrow(ApiFailure::notFound);
        var values=new CalendarFeed(feed.get().get("body").asText()).occurrences(first,last,ZoneId.of(store.profile().path("zoneId").asText()));
        return values.stream().map(value->{var node=value.json();node.put("subscriptionId",subscription.path("id").asText()).put("calendarName",subscription.path("name").asText())
                .put("clientId",client.path("id").asText()).put("clientName",client.path("name").asText()).put("color",client.path("color").asText());
            node.remove("eventVersion");node.put("eventVersion",CalendarFeed.version(node));return node;}).toList();
    }
    ObjectNode noteEvent(UUID subscription,String key,String first,String last){
        var dates=window(first,last);
        try{return occurrences(required(subscription),dates[0],dates[1]).stream().filter(value->value.path("key").asText().equals(key)).findFirst().orElseThrow(ApiFailure::notFound);}
        catch(CalendarFailure failure){throw new ApiFailure(409,"CALENDAR_EVENT_CHANGED");}
    }
    ObjectNode events(String first,String last){
        var dates=window(first,last);var result=object();var items=result.putArray("items");var errors=result.putObject("errors");var links=links();var notes=new EventNotes(store).presentKeys();
        for(var subscription:store.list(SUB))if(subscription.path("visible").asBoolean())try {
            var values=occurrences(subscription,dates[0],dates[1]);if(items.size()+values.size()>5000)throw CalendarFailure.complex();
            for(var event:values) {
                var link=links.get(subscription.path("id").asText()+"/"+event.path("key").asText());
                event.set("activityIds",link==null?array():link.path("activityIds"));event.put("hasNote",notes.contains(subscription.path("id").asText()+"/"+event.path("key").asText()));items.add(event);
            }
        }catch(CalendarFailure failure){errors.put(subscription.path("id").asText(),failure.code);}
        var sources=result.putObject("activitySources");
        Instant from=dates[0].atStartOfDay(ZoneId.of(store.profile().path("zoneId").asText())).toInstant(),to=dates[1].atStartOfDay(ZoneId.of(store.profile().path("zoneId").asText())).toInstant();
        var activities=new HashSet<String>();for(var value:store.list("ACTIVITY"))if(Instant.parse(value.path("start").asText()).isBefore(to)&&(!value.hasNonNull("end")||!Instant.parse(value.path("end").asText()).isBefore(from)))activities.add(value.path("id").asText());
        for(var link:links.values())for(var id:link.path("activityIds"))if(activities.contains(id.asText())) {
            var source=object();for(String field:List.of("subscriptionId","calendarName","clientId","clientName","color","eventKey","uid","recurrenceId","title","description","location","start","end","allDay","firstDate","endDate"))source.set(field,link.path(field));sources.set(id.asText(),source);
        }
        return result;
    }
    ObjectNode convert(UUID id,String key,JsonNode input){
        var subscription=required(id);if(!key.matches("[0-9a-f]{64}"))throw ApiFailure.badRequest();
        UUID linkId=ImportLedger.identifier("calendar-activity",id.toString(),key);
        if(store.find(LINK,linkId).isPresent())throw new ApiFailure(409,"CALENDAR_ALREADY_CONVERTED");
        var dates=window(input.path("fromDate").asText(),input.path("toDate").asText());ObjectNode event;
        try{event=occurrences(subscription,dates[0],dates[1]).stream().filter(value->value.path("key").asText().equals(key)).findFirst().orElseThrow(()->new ApiFailure(409,"CALENDAR_EVENT_CHANGED"));}
        catch(CalendarFailure failure){throw new ApiFailure(409,"CALENDAR_EVENT_CHANGED");}
        if(!event.path("eventVersion").asText().equals(input.path("eventVersion").asText()))throw new ApiFailure(409,"CALENDAR_EVENT_CHANGED");
        if(event.path("componentType").asText().equals("VTODO"))throw new ApiFailure(422,"CALENDAR_TODO_READ_ONLY");
        var activity=input.get("activity");if(!activity.hasNonNull("end"))throw ApiFailure.validation();
        Instant start=Instant.parse(activity.path("start").asText()),end=Instant.parse(activity.path("end").asText());
        if(start.isAfter(store.now()))throw new ApiFailure(422,"CALENDAR_FUTURE_ACTIVITY");
        if(end.isBefore(start)||java.time.Duration.between(start,end).compareTo(java.time.Duration.ofDays(367))>0)throw ApiFailure.validation();
        Optional<ProjectId> project=optionalText(activity.get("projectId")).map(UUID::fromString).map(ProjectId::new);
        TaskId task=optionalText(activity.get("taskId")).map(UUID::fromString).map(TaskId::new).orElse(null);
        if(task!=null){var value=store.find("TASK",task.value()).orElseThrow(ApiFailure::notFound);if(value.hasNonNull("projectId"))project=Optional.of(new ProjectId(uuid(value.get("projectId"))));}
        if(project.isPresent()) {
            var value=store.find("PROJECT",project.get().value()).orElseThrow(ApiFailure::notFound);
            if(!value.path("clientId").asText().equals(subscription.path("clientId").asText()))throw new ApiFailure(422,"CALENDAR_CLIENT_MISMATCH");
        }
        var parts=ActivityDailySegments.split(start,Optional.of(end),ZoneId.of(store.profile().path("zoneId").asText()),store.now());
        var labels=new HashSet<LabelId>();activity.path("labelIds").forEach(value->labels.add(new LabelId(uuid(value))));
        var retained=store.service().createActivity(new CreateActivity(task,new OwnerId(store.owner()),start,Optional.of(end),activity.path("summary").asText(),labels,Optional.empty(),project));
        var ids=array();for(int index=0;index<parts.size();index++)ids.add((index==parts.size()-1?retained.id():ActivityDailySegments.identifier(retained.id(),parts.get(index).start())).value().toString());
        var link=event.deepCopy();link.remove("eventVersion");link.put("eventKey",key).set("activityIds",ids);link.put("convertedAt",store.now().toString());insert(LINK,linkId,link);
        return object().set("activityIds",ids);
    }
    void inherit(UUID original,UUID part){
        if(original.equals(part))return;
        for(var stored:store.list(LINK)){
            var ids=stored.path("activityIds");boolean found=false,exists=false;for(var id:ids){found|=id.asText().equals(original.toString());exists|=id.asText().equals(part.toString());}
            if(found&&!exists){var link=((ObjectNode)stored).deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)link.get("activityIds")).add(part.toString());save(LINK,link);}
        }
    }
}
