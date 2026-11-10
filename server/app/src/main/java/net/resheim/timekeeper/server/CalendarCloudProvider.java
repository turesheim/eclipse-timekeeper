package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Calendar API adapter. It never reads task endpoints or calendar subscription URLs. */
final class CalendarCloudProvider {
    record RemoteCalendar(String id,String name,boolean readable){ObjectNode json(){return object().put("id",id).put("name",name).put("readable",readable);}}
    record Page(List<ObjectNode> events,String next,String syncToken){}
    private final TaskCloudProvider.Provider provider;
    private final TaskProviderHttp http;
    CalendarCloudProvider(TaskCloudProvider.Provider provider){this(provider,new TaskProviderHttp(Set.of("www.googleapis.com","graph.microsoft.com")));}
    CalendarCloudProvider(TaskCloudProvider.Provider provider,TaskProviderHttp http){this.provider=provider;this.http=http;}
    private boolean google(){return provider==TaskCloudProvider.Provider.GOOGLE;}
    List<RemoteCalendar> catalog(String token){
        String base=google()?"https://www.googleapis.com/calendar/v3/users/me/calendarList":"https://graph.microsoft.com/v1.0/me/calendars";
        String address=base+(google()?"?maxResults=250&showDeleted=false":"?$top=200");var result=new ArrayList<RemoteCalendar>();var ids=new HashSet<String>();var pages=new HashSet<String>();long bytes=0;
        while(address!=null){
            if(!pages.add(address)||pages.size()>100)throw invalid();var page=request(address,token);bytes+=page.toString().length();
            if(bytes>4*1024*1024)throw limit();
            for(var item:items(page)){
                if(item.path("deleted").asBoolean())continue;String id=required(item,"id",1024);if(!ids.add(id))throw invalid();
                String name=item.path(google()?"summaryOverride":"name").asText(item.path("summary").asText(id));if(name.isBlank()||name.length()>1024)name=id;
                boolean readable=!google()||Set.of("reader","writer","owner").contains(item.path("accessRole").asText());result.add(new RemoteCalendar(id,name,readable));if(result.size()>1000)throw limit();
            }
            String next=next(page);address=next==null?null:google()?base+"?maxResults=250&showDeleted=false&pageToken="+segment(next):continuation(base,next);
        }
        return result;
    }
    Page events(String token,String calendar,String cursor,String syncToken){
        String base=base(calendar);String address;
        if(google())address=base+"?maxResults=2500&showDeleted=true&singleEvents=false"+(syncToken==null?"":"&syncToken="+segment(syncToken))+(cursor==null?"":"&pageToken="+segment(cursor));
        else address=cursor==null?base+"?$top=200&$select=id,subject,body,bodyPreview,start,end,isAllDay,isCancelled,type,recurrence,originalStartTimeZone,originalEndTimeZone,lastModifiedDateTime,location":continuation(base,cursor);
        var reply=request(address,token);var events=new ArrayList<ObjectNode>();for(var item:items(reply)){if(!item.isObject())throw invalid();required(item,"id",1024);events.add(((ObjectNode)item).deepCopy());}
        return new Page(events,next(reply),google()?optional(reply,"nextSyncToken"):null);
    }
    ObjectNode series(String token,String calendar,String id){
        if(google())throw invalid();String base=base(calendar)+"/"+segment(id);
        var value=(ObjectNode)request(base+"?$expand=exceptionOccurrences&$select=id,subject,body,bodyPreview,start,end,isAllDay,isCancelled,type,recurrence,originalStartTimeZone,originalEndTimeZone,lastModifiedDateTime,location,cancelledOccurrences,exceptionOccurrences",token);
        if(!required(value,"id",1024).equals(id))throw invalid();
        var exceptions=value.path("exceptionOccurrences");if(!exceptions.isArray())throw invalid();
        if(exceptions.size()>5000)throw limit();long bytes=value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String next=optional(value,"exceptionOccurrences@odata.nextLink");var seen=new HashSet<String>();
        while(next!=null){
            if(!seen.add(next)||seen.size()>100)throw invalid();var page=request(continuation(base+"/exceptionOccurrences",next),token);
            bytes+=page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes>TaskProviderHttp.MAX_BYTES)throw limit();
            for(var item:items(page)){if(!item.isObject())throw invalid();((com.fasterxml.jackson.databind.node.ArrayNode)exceptions).add(item.deepCopy());if(exceptions.size()>5000)throw limit();}
            next=next(page);
        }
        value.remove("exceptionOccurrences@odata.nextLink");return value;
    }
    String source(String calendar){return base(calendar);}
    private String base(String calendar){if(calendar==null||calendar.isBlank()||calendar.length()>1024)throw invalid();return google()?"https://www.googleapis.com/calendar/v3/calendars/"+segment(calendar)+"/events":"https://graph.microsoft.com/v1.0/me/calendars/"+segment(calendar)+"/events";}
    private JsonNode request(String address,String token){
        if(token==null||token.isBlank()||token.length()>16384||token.chars().anyMatch(c->c<33||c>126))throw invalid();
        var request=HttpRequest.newBuilder(http.address(address)).timeout(Duration.ofSeconds(25)).header("Accept","application/json").header("Authorization","Bearer "+token);
        if(!google())request.header("Prefer","outlook.body-content-type=\"text\", IdType=\"ImmutableId\"");return http.send(request.GET().build());
    }
    private Iterable<JsonNode> items(JsonNode page){var items=page.path(google()?"items":"value");if(!items.isArray())throw invalid();return items;}
    private String next(JsonNode page){return optional(page,google()?"nextPageToken":"@odata.nextLink");}
    private static String optional(JsonNode page,String field){if(!page.hasNonNull(field))return null;return required(page,field,8192);}
    private static String continuation(String base,String address){
        try{var expected=URI.create(base);var next=URI.create(address);if(!Objects.equals(expected.getScheme(),next.getScheme())||!Objects.equals(expected.getRawAuthority(),next.getRawAuthority())||!Objects.equals(expected.getRawPath(),next.getRawPath())||next.getRawFragment()!=null||next.getRawUserInfo()!=null||next.getRawQuery()==null||address.length()>8192)throw invalid();return next.toString();}
        catch(RuntimeException failure){throw invalid();}
    }
    private static String required(JsonNode value,String field,int max){var item=value.get(field);if(item==null||!item.isTextual()||item.asText().isBlank()||item.asText().length()>max||item.asText().codePoints().anyMatch(Character::isISOControl))throw invalid();return item.asText();}
    private static String segment(String value){return TaskCloudProvider.segment(value);}
    private static ApiFailure invalid(){return new ApiFailure(503,"TASK_PROVIDER_INVALID_RESPONSE");}
    private static ApiFailure limit(){return new ApiFailure(422,"TASK_PROVIDER_RESPONSE_TOO_LARGE");}
}
