package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Narrow Google mutations; the owned coordinator supplies consent, preview and durable write intent. */
final class GoogleTasksWriter {
    private static final String ROOT="https://tasks.googleapis.com/tasks/v1/lists/";
    private static final Set<String> FIELDS=Set.of("summary","body","status","completedAt","scheduledDate");
    static final class Unsupported extends RuntimeException {
        final String field,reason;
        Unsupported(String field,String reason){super("Google task field cannot be represented");this.field=field;this.reason=reason;}
    }
    private final TaskProviderHttp http;
    private final TaskCloudProvider reader;
    GoogleTasksWriter(){this(new TaskProviderHttp(Set.of("tasks.googleapis.com")));}
    GoogleTasksWriter(TaskProviderHttp http){this.http=http;reader=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE,http);}
    TaskCloudProvider.RemoteTask get(String token,String list,String task){return reader.normalize(http.json("GET",address(list,task),token,null,null));}
    TaskCloudProvider.RemoteTask create(String token,String list,JsonNode fields){
        var body=body(fields);if(!body.has("title"))throw new Unsupported("summary","REQUIRED");
        return mutate("POST",address(list,null),token,body,null,null);
    }
    TaskCloudProvider.RemoteTask update(String token,String list,String task,String revision,JsonNode fields){
        var body=body(fields);if(body.isEmpty())throw new Unsupported("","EMPTY_WRITE");
        return mutate("PATCH",address(list,task),token,body,revision(revision),task);
    }
    void delete(String token,String list,String task,String revision){http.json("DELETE",address(list,task),token,null,revision(revision));}
    private TaskCloudProvider.RemoteTask mutate(String method,String address,String token,ObjectNode body,String revision,String expected){
        var value=http.json(method,address,token,body,revision);
        try{
            var task=reader.normalize(value);
            if(task.deleted()||expected!=null&&!expected.equals(task.id())||!task.source().hasNonNull("remoteEtag"))throw new TaskProviderHttp.Failure("TASK_PROVIDER_INVALID_RESPONSE",200,0,true);
            return task;
        }catch(TaskProviderHttp.Failure invalid){throw new TaskProviderHttp.Failure(invalid.getMessage(),200,invalid.retryAfterSeconds,true);}
    }
    private static String address(String list,String task){
        identifier(list);if(task!=null)identifier(task);
        return ROOT+TaskCloudProvider.segment(list)+"/tasks"+(task==null?"":"/"+TaskCloudProvider.segment(task));
    }
    private static void identifier(String value){if(value==null||value.isBlank()||value.length()>1024||Set.of(".","..").contains(value)||value.codePoints().anyMatch(Character::isISOControl))throw new TaskProviderHttp.Failure("TASK_PROVIDER_INVALID_REQUEST",0,0,false);}
    static String revision(String value){
        if(value==null||value.isBlank()||value.length()>4096||value.chars().anyMatch(ch->ch<32||ch>126))throw new TaskProviderHttp.Failure("TASK_PROVIDER_REVISION_REQUIRED",0,0,false);
        boolean quoted=value.length()>=2&&value.startsWith("\"")&&value.endsWith("\"")&&value.substring(1,value.length()-1).indexOf('"')<0;
        if(!quoted&&!value.matches("[A-Za-z0-9._:/+=-]+"))throw new TaskProviderHttp.Failure("TASK_PROVIDER_REVISION_REQUIRED",0,0,false);return value;
    }
    /** No whole-object PUT: fields absent from this delta stay untouched in Google. */
    static ObjectNode body(JsonNode fields){
        if(fields==null||!fields.isObject())throw new Unsupported("","INVALID_FIELDS");
        for(var names=fields.fieldNames();names.hasNext();){String name=names.next();if(!FIELDS.contains(name))throw new Unsupported(name,"NOT_WRITABLE");}
        var body=object();
        if(fields.has("summary")){String title=text(fields.get("summary"),"summary",1024);if(title.isBlank())throw new Unsupported("summary","REQUIRED");body.put("title",title);}
        if(fields.has("body")){
            var value=fields.get("body");if(value.isNull())body.put("notes","");
            else{if(!value.isObject()||!value.path("format").asText().equals("TEXT"))throw new Unsupported("body","TEXT_ONLY");body.put("notes",text(value.get("content"),"body",8192));}
        }
        if(fields.has("status")){
            String status=fields.path("status").asText();
            switch(status){
                case "NOT_STARTED"->{if(fields.hasNonNull("completedAt"))throw new Unsupported("completedAt","COMPLETION_GROUP");body.put("status","needsAction").putNull("completed");}
                case "COMPLETED"->{body.put("status","completed");if(fields.hasNonNull("completedAt"))try{body.put("completed",Instant.parse(text(fields.get("completedAt"),"completedAt",128)).toString());}catch(DateTimeException invalid){throw new Unsupported("completedAt","INVALID_TIME");}}
                default->throw new Unsupported("status","STATUS_UNSUPPORTED");
            }
        }else if(fields.has("completedAt"))throw new Unsupported("completedAt","COMPLETION_GROUP");
        if(fields.has("scheduledDate")){
            var value=fields.get("scheduledDate");if(value.isNull())body.putNull("due");else try{var day=LocalDate.parse(text(value,"scheduledDate",10));if(day.getYear()<1||day.getYear()>9999)throw new DateTimeException("Unsupported year");body.put("due",day+"T00:00:00Z");}catch(DateTimeException invalid){throw new Unsupported("scheduledDate","INVALID_DATE");}
        }
        return body;
    }
    private static String text(JsonNode value,String field,int maximum){if(value==null||!value.isTextual()||value.asText().codePointCount(0,value.asText().length())>maximum)throw new Unsupported(field,"FIELD_LIMIT");return value.asText();}
}
