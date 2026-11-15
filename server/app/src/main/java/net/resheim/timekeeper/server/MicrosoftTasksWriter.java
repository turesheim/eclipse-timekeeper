package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Microsoft Graph To Do v1.0. Only changed, representable fields are sent. */
final class MicrosoftTasksWriter implements TaskWriter {
    private final TaskProviderHttp http;
    private final TaskCloudProvider reader;
    MicrosoftTasksWriter(){this(new TaskProviderHttp(Set.of("graph.microsoft.com")));}
    MicrosoftTasksWriter(TaskProviderHttp http){this.http=http;reader=new TaskCloudProvider(TaskCloudProvider.Provider.MICROSOFT,http);}
    public TaskCloudProvider.RemoteTask get(String token,String list,String task){return reader.normalize(http.json("GET",address(list,task),token,null,null));}
    public TaskCloudProvider.RemoteTask create(String token,String list,JsonNode fields){
        var body=body(fields);if(!body.hasNonNull("title"))throw unsupported("summary","REQUIRED");
        return mutate("POST",address(list,null),token,body,null,null);
    }
    public TaskCloudProvider.RemoteTask update(String token,String list,String task,String revision,JsonNode fields){
        return mutate("PATCH",address(list,task),token,body(fields),revision(revision),task);
    }
    public void delete(String token,String list,String task,String revision){http.json("DELETE",address(list,task),token,null,revision(revision));}
    private TaskCloudProvider.RemoteTask mutate(String method,String address,String token,ObjectNode body,String revision,String expected){
        var value=http.json(method,address,token,body,revision);
        try{var remote=reader.normalize(value);if(remote.deleted()||expected!=null&&!expected.equals(remote.id())||!remote.source().hasNonNull("remoteEtag"))throw new IllegalArgumentException();return remote;}
        catch(RuntimeException invalid){throw new TaskProviderHttp.Failure("TASK_PROVIDER_INVALID_RESPONSE",200,0,true);}
    }
    private static String address(String list,String task){
        for(String value:task==null?List.of(list):List.of(list,task))if(value.isBlank()||value.length()>1024||Set.of(".","..").contains(value)||value.codePoints().anyMatch(Character::isISOControl))throw new TaskProviderHttp.Failure("TASK_PROVIDER_INVALID_REQUEST",0,0,false);
        return "https://graph.microsoft.com/v1.0/me/todo/lists/"+TaskCloudProvider.segment(list)+"/tasks"+(task==null?"":"/"+TaskCloudProvider.segment(task));
    }
    static String revision(String value){
        if(value!=null&&value.startsWith("W/")){GoogleTasksWriter.revision(value.substring(2));return value;}
        return GoogleTasksWriter.revision(value);
    }
    static ObjectNode body(JsonNode fields){
        var body=object();
        for(var names=fields.fieldNames();names.hasNext();){String field=names.next();var value=fields.get(field);
            switch(field){
                case "summary"->{if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>1024)throw unsupported(field,"FIELD_LIMIT");body.set("title",value);}
                case "body"->{
                    if(!value.isNull()&&!value.path("format").asText().equals("HTML")&&!value.path("content").asText().isEmpty())throw unsupported(field,"HTML_ONLY");
                    body.putObject("body").put("contentType","html").put("content",value.isNull()?"":value.path("content").asText());
                }
                case "status"->body.put("status",switch(value.asText()){
                    case "NOT_STARTED"->"notStarted";case "IN_PROGRESS"->"inProgress";case "COMPLETED"->"completed";case "WAITING"->"waitingOnOthers";case "DEFERRED"->"deferred";default->throw unsupported(field,"STATUS_UNSUPPORTED");});
                case "completedAt"->{
                    if(!fields.has("status"))throw unsupported(field,"COMPLETION_GROUP");
                    if(value.isNull())body.putNull("completedDateTime");
                    else try{var time=LocalDateTime.ofInstant(Instant.parse(value.asText()),ZoneOffset.UTC);body.putObject("completedDateTime").put("dateTime",time.toString()).put("timeZone","UTC");}catch(DateTimeException invalid){throw unsupported(field,"INVALID_TIME");}
                }
                case "start","due"->{
                    if(value.isNull())body.putNull(field+"DateTime");
                    else if(!value.path("kind").asText().equals("DATE_TIME"))throw unsupported(field,"TIMED_DATE_REQUIRED");
                    else try{var time=LocalDateTime.of(LocalDate.parse(value.path("date").asText()),LocalTime.parse(value.path("time").asText()));body.putObject(field+"DateTime").put("dateTime",time.toString()).put("timeZone",value.path("timeZone").asText());}catch(DateTimeException invalid){throw unsupported(field,"INVALID_TIME");}
                }
                case "priority"->body.put("importance",switch(value.asText()){case "LOW"->"low";case "NORMAL"->"normal";case "HIGH"->"high";default->throw unsupported(field,"PRIORITY_UNSUPPORTED");});
                case "metadata"->{if(value.has("categories"))body.set("categories",value.get("categories").deepCopy());for(var keys=value.fieldNames();keys.hasNext();){var key=keys.next();if(!key.equals("categories"))throw unsupported(field,"NOT_WRITABLE");}}
                default->throw unsupported(field,"NOT_WRITABLE");
            }
        }
        if(body.isEmpty())throw unsupported("","EMPTY_WRITE");return body;
    }
    private static GoogleTasksWriter.Unsupported unsupported(String field,String reason){return new GoogleTasksWriter.Unsupported(field,reason);}
}
