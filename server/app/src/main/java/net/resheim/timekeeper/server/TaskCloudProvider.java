package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Read adapters for the public Google Tasks v1 and Microsoft Graph To Do v1.0 APIs. */
final class TaskCloudProvider {
    enum Provider { GOOGLE, MICROSOFT }
    record Account(String id,String name) {}
    record RemoteList(String id,String name,String kind,boolean shared,boolean owner,JsonNode original) {}
    record RemoteTask(String id,ObjectNode fields,String parent,String position,boolean deleted,ObjectNode source,JsonNode original) {}
    record Page<T>(List<T> items,String next,String delta) {
        Page {items=List.copyOf(items);}
    }
    private static final String GOOGLE="https://tasks.googleapis.com/tasks/v1/";
    private static final String GRAPH="https://graph.microsoft.com/v1.0/me/";
    private final Provider provider;
    private final TaskProviderHttp http;
    TaskCloudProvider(Provider provider){this(provider,new TaskProviderHttp(Set.of("tasks.googleapis.com","openidconnect.googleapis.com","graph.microsoft.com")));}
    TaskCloudProvider(Provider provider,TaskProviderHttp http){this.provider=provider;this.http=http;}
    String id(){return provider==Provider.GOOGLE?"google":"microsoft";}
    Account account(String token){
        var value=get(provider==Provider.GOOGLE?"https://openidconnect.googleapis.com/v1/userinfo":GRAPH.substring(0,GRAPH.length()-1)+"?$select=id,displayName",token);
        String key=required(value,provider==Provider.GOOGLE?"sub":"id",1024);
        return new Account(key,text(value,provider==Provider.GOOGLE?"name":"displayName",1024).orElse(key));
    }
    Page<RemoteList> lists(String token,String next){
        String collection=provider==Provider.GOOGLE?GOOGLE+"users/@me/lists":GRAPH+"todo/lists";
        var value=get(address(collection,next,provider==Provider.GOOGLE?"maxResults=100":"$top=100"),token);
        var result=new ArrayList<RemoteList>();
        for(var list:items(value)){
            if(list.has("@removed"))continue;
            String name=required(list,provider==Provider.GOOGLE?"title":"displayName",1024);
            String wellKnown=list.path("wellknownListName").asText("none");
            result.add(new RemoteList(required(list,"id",1024),name,wellKnown.equals("none")?"STANDARD":"BUILT_IN",
                    booleanValue(list,"isShared",false),booleanValue(list,"isOwner",provider==Provider.GOOGLE),list.deepCopy()));
        }
        return new Page<>(result,next(value,collection),delta(value,collection));
    }
    List<RemoteList> catalog(String token){
        var result=new ArrayList<RemoteList>();var ids=new HashSet<String>();var pages=new HashSet<String>();String next=null;int bytes=0;
        long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        do{
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new TaskProviderHttp.Failure("TASK_PROVIDER_SCAN_INCOMPLETE",0,0,false);
            var page=lists(token,next);
            for(var list:page.items()){
                if(!ids.add(list.id()))throw invalid();bytes+=list.original().toString().getBytes(StandardCharsets.UTF_8).length;
                if(result.size()>=1000||bytes>4*1024*1024)throw new TaskProviderHttp.Failure("TASK_PROVIDER_LIST_LIMIT",0,0,false);result.add(list);
            }
            next=page.next();if(next!=null&&(!pages.add(next)||pages.size()>100))throw new TaskProviderHttp.Failure("TASK_PROVIDER_SCAN_INCOMPLETE",0,0,false);
        }while(next!=null);return List.copyOf(result);
    }
    Page<RemoteTask> tasks(String token,String listId,String next){return tasks(token,listId,next,null);}
    Page<RemoteTask> tasks(String token,String listId,String next,Instant updatedMin){
        if(listId==null||listId.isBlank()||listId.length()>1024)throw invalid();
        String collection=provider==Provider.GOOGLE?GOOGLE+"lists/"+segment(listId)+"/tasks":GRAPH+"todo/lists/"+segment(listId)+"/tasks";
        var value=get(address(collection,next,provider==Provider.GOOGLE
                ?"maxResults=100&showCompleted=true&showHidden=true&showDeleted=true&showAssigned=true"+(updatedMin==null?"":"&updatedMin="+segment(updatedMin.toString())):"$top=100"),token);
        var result=new ArrayList<RemoteTask>();
        for(var task:items(value))result.add(normalize(task));
        return new Page<>(result,next(value,collection),delta(value,collection));
    }
    ObjectNode capabilities(){return capabilities(false);}
    ObjectNode capabilities(boolean writable){
        var result=object().put("version",1).put("providerApiVersion",provider==Provider.GOOGLE?"tasks-v1/dagsverk-1":"graph-v1.0/dagsverk-read-1");
        var fields=result.putObject("fields");
        var names=new ArrayList<>(List.of("summary","body","status","completedAt"));
        if(provider==Provider.GOOGLE)names.addAll(List.of("scheduledDate","parentTaskId","siblingOrder","assignment","metadata.links"));
        else names.addAll(List.of("start","due","priority","metadata.categories"));
        for(String name:names)fields.putObject(name).put("readable",true).put("writable",false).put("deletable",false).putNull("note");
        if(writable&&provider==Provider.GOOGLE)for(String name:List.of("summary","body","status","completedAt","scheduledDate")){
            ((ObjectNode)fields.get(name)).put("writable",true).put("deletable",!name.equals("summary")&&!name.equals("status"));
        }
        return result;
    }
    RemoteTask normalize(JsonNode value){
        try{
            if(!value.isObject())throw invalid();
            String key=required(value,"id",1024);boolean deleted=booleanValue(value,"deleted",false)||value.has("@removed");
            var fields=object();var source=object();
            if(provider==Provider.GOOGLE){
                sourceTime(source,"modifiedAt",value,"updated");sourceString(source,"remoteEtag",value,"etag");
                if(!deleted){
                    GoogleTaskContext.validate(value);var assigned=GoogleTaskContext.assigned(value);if(!assigned.isMissingNode())fields.set("assignment",assigned);
                    fields.put("summary",title(value).trim());
                    // Assigned tasks may not expose notes; omission is not an observed clear.
                    if(value.has("notes")||!value.hasNonNull("assignmentInfo"))body(fields,"TEXT",text(value,"notes",65536).orElse(""));
                    String status=required(value,"status",32);
                    fields.put("status",switch(status){case "needsAction"->"NOT_STARTED";case "completed"->"COMPLETED";default->throw invalid();});
                    var completed=text(value,"completed",128);
                    if(completed.isPresent())fields.put("completedAt",Instant.parse(completed.get()).toString());else fields.putNull("completedAt");
                    var due=text(value,"due",128);
                    if(due.isPresent())fields.put("scheduledDate",OffsetDateTime.parse(due.get()).toLocalDate().toString());else fields.putNull("scheduledDate");
                }
            }else{
                sourceTime(source,"createdAt",value,"createdDateTime");sourceTime(source,"modifiedAt",value,"lastModifiedDateTime");
                sourceString(source,"remoteEtag",value,"@odata.etag");
                if(!deleted){
                    fields.put("summary",required(value,"title",1024).trim());
                    if(value.has("body")){
                        if(value.get("body").isNull())body(fields,"TEXT","");
                        else{
                            String type=required(value.get("body"),"contentType",32).toLowerCase(Locale.ROOT);
                            body(fields,switch(type){case "text"->"TEXT";case "html"->"HTML";default->throw invalid();},requiredText(value.get("body"),"content",65536));
                        }
                    }
                    String status=required(value,"status",32);
                    fields.put("status",switch(status){case "notStarted"->"NOT_STARTED";case "inProgress"->"IN_PROGRESS";case "completed"->"COMPLETED";case "waitingOnOthers"->"WAITING";case "deferred"->"DEFERRED";default->throw invalid();});
                    if(value.hasNonNull("completedDateTime"))fields.put("completedAt",instant(value.get("completedDateTime")).toString());else fields.putNull("completedAt");
                    for(String field:List.of("start","due"))if(value.has(field+"DateTime"))fields.set(field,moment(value.get(field+"DateTime")));
                    if(value.has("importance"))fields.put("priority",switch(required(value,"importance",32)){case "low"->"LOW";case "normal"->"NORMAL";case "high"->"HIGH";default->throw invalid();});
                    if(value.has("categories")){
                        var categories=value.get("categories");if(!categories.isArray()||categories.size()>100)throw invalid();
                        for(var category:categories)if(!category.isTextual()||category.asText().isBlank()||category.asText().length()>200||category.asText().codePoints().anyMatch(Character::isISOControl))throw invalid();
                        fields.putObject("metadata").set("categories",categories.deepCopy());
                    }
                }
            }
            return new RemoteTask(key,fields,text(value,"parent",1024).orElse(null),text(value,"position",1024).orElse(null),deleted,source,value.deepCopy());
        }catch(TaskProviderHttp.Failure failure){throw failure;}
        catch(RuntimeException failure){throw invalid();}
    }
    private JsonNode get(String address,String token){return http.json("GET",address,token,null,null);}
    private JsonNode items(JsonNode value){
        var items=value.get(provider==Provider.GOOGLE?"items":"value");
        if(items==null&&provider==Provider.GOOGLE)return array();
        if(items==null||!items.isArray()||items.size()>100)throw invalid();
        for(var item:items)if(!item.isObject())throw invalid();
        return items;
    }
    private String address(String collection,String next,String query){
        if(next==null)return collection+"?"+query;
        if(provider==Provider.GOOGLE){if(next.isBlank()||next.length()>32768)throw invalid();return collection+"?"+query+"&pageToken="+segment(next);}
        return checkedGraphLink(collection,next);
    }
    private String next(JsonNode value,String collection){
        var next=text(value,provider==Provider.GOOGLE?"nextPageToken":"@odata.nextLink",32768);
        return next.map(link->provider==Provider.GOOGLE?link:checkedGraphLink(collection,link)).orElse(null);
    }
    private String delta(JsonNode value,String collection){
        return provider==Provider.GOOGLE?null:text(value,"@odata.deltaLink",32768).map(link->checkedGraphLink(collection,link)).orElse(null);
    }
    private String checkedGraphLink(String collection,String link){
        var uri=http.address(link);var expected=URI.create(collection);
        if(!uri.getHost().equals(expected.getHost())||!Objects.equals(uri.getRawPath(),expected.getRawPath()))throw invalid();
        return uri.toString();
    }
    static String segment(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private static void body(ObjectNode fields,String format,String content){fields.putObject("body").put("format",format).put("content",content);}
    private static JsonNode moment(JsonNode value){
        if(value.isNull())return com.fasterxml.jackson.databind.node.NullNode.instance;
        var time=LocalDateTime.parse(required(value,"dateTime",128));String zone=required(value,"timeZone",128);
        return object().put("kind","DATE_TIME").put("date",time.toLocalDate().toString()).put("time",time.toLocalTime().toString()).put("timeZone",zone);
    }
    private static Instant instant(JsonNode value){
        var time=LocalDateTime.parse(required(value,"dateTime",128));var zone=ZoneId.of(required(value,"timeZone",128));
        var offsets=zone.getRules().getValidOffsets(time);
        // An ambiguous/nonexistent local completion time cannot establish an Instant baseline.
        if(offsets.size()!=1)throw invalid();return time.atOffset(offsets.getFirst()).toInstant();
    }
    private static void sourceTime(ObjectNode target,String name,JsonNode value,String field){
        text(value,field,128).ifPresent(time->target.put(name,Instant.parse(time).toString()));
    }
    private static void sourceString(ObjectNode target,String name,JsonNode value,String field){text(value,field,2048).ifPresent(text->target.put(name,text));}
    private static Optional<String> text(JsonNode value,String name,int max){
        var field=value.get(name);if(field==null||field.isNull())return Optional.empty();
        if(!field.isTextual()||field.asText().length()>max)throw invalid();return Optional.of(field.asText());
    }
    private static String requiredText(JsonNode value,String field,int max){return text(value,field,max).orElseThrow(TaskCloudProvider::invalid);}
    private static String title(JsonNode value){
        var node=value.get("title");if(node==null||!node.isTextual()||node.asText().isBlank()||node.asText().codePointCount(0,node.asText().length())>1024)throw invalid();return node.asText();
    }
    private static String required(JsonNode value,String field,int max){var text=requiredText(value,field,max);if(text.isBlank())throw invalid();return text;}
    private static boolean booleanValue(JsonNode value,String field,boolean fallback){
        if(!value.has(field))return fallback;if(!value.get(field).isBoolean())throw invalid();return value.get(field).asBoolean();
    }
    private static TaskProviderHttp.Failure invalid(){return new TaskProviderHttp.Failure("TASK_PROVIDER_INVALID_RESPONSE",0,0,false);}
}
