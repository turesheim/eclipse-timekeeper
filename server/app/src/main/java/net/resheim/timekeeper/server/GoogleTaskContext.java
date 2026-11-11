package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import net.resheim.eclipse.timekeeper.domain.*;

/** Output-only Google context; never substitutes source URLs for the user's own task URL. */
final class GoogleTaskContext {
    static Optional<String> taskUrl(JsonNode value){return text(value,"webViewLink",4096).filter(url->!url.isBlank()).map(GoogleTaskContext::https);}
    static JsonNode assigned(JsonNode value){
        var info=value.get("assignmentInfo");if(info==null||info.isNull())return NullNode.instance;
        if(!info.isObject()||info.hasNonNull("driveResourceInfo")&&info.hasNonNull("spaceInfo"))throw new IllegalArgumentException("Invalid Google assignment");
        var url=text(info,"linkToTask",4096).filter(link->!link.isBlank()).map(GoogleTaskContext::https);Optional<String> id=Optional.empty();
        if(info.hasNonNull("driveResourceInfo")){
            if(!info.get("driveResourceInfo").isObject())throw new IllegalArgumentException("Invalid Google assignment");
            id=text(info.get("driveResourceInfo"),"driveFileId",990).filter(key->!key.isBlank()).map(key->"drive:"+key);
        }else if(info.hasNonNull("spaceInfo")){
            if(!info.get("spaceInfo").isObject())throw new IllegalArgumentException("Invalid Google assignment");
            id=text(info.get("spaceInfo"),"space",990).filter(key->!key.isBlank()).map(key->"chat:"+key);
        }
        if(id.isEmpty()&&url.isEmpty())return MissingNode.getInstance();
        return assignment(new TaskAssignment(Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),id,url,false));
    }
    static void validate(JsonNode value){taskUrl(value);assigned(value);links(value,"validation","validation");}
    static ObjectNode fields(TaskCloudProvider.RemoteTask task,String repository){
        var fields=task.fields().deepCopy();if(task.deleted())return fields;
        var links=links(task.original(),repository,task.id());if(links!=null)fields.withObject("metadata").set("links",metadata(new TaskMetadata(Set.of(),Set.of(),links)).get("links"));return fields;
    }
    private static List<TaskLink> links(JsonNode value,String repository,String task){
        if(!value.has("links"))return null;var supplied=value.get("links");if(!supplied.isArray()||supplied.size()>100)throw new IllegalArgumentException("Invalid Google links");
        var links=new LinkedHashMap<UUID,TaskLink>();
        for(var item:supplied){
            if(!item.isObject())throw new IllegalArgumentException("Invalid Google link");
            String url=text(item,"link",4096).filter(link->!link.isBlank()).orElseThrow(()->new IllegalArgumentException("Missing Google link"));
            String type=text(item,"type",200).orElse("");var title=text(item,"description",65536).map(GoogleTaskContext::title).filter(text->!text.isBlank());
            String identity=canonical(object().put("repository",repository).put("task",task).put("type",type).put("url",url)).toString();
            UUID id=UUID.nameUUIDFromBytes(("dagsverk-google-source:"+identity).getBytes(StandardCharsets.UTF_8));
            var link=new TaskLink(id,TaskLinkKind.PROVIDER_SOURCE,url,title,Optional.of("google"),Optional.empty());
            links.merge(id,link,(before,after)->before.title().orElse("").compareTo(after.title().orElse(""))<=0?before:after);
        }
        return links.values().stream().sorted(Comparator.comparing(link->link.id().toString())).toList();
    }
    private static String title(String value){
        var clean=new StringBuilder();value.codePoints().forEach(cp->clean.appendCodePoint(Character.isISOControl(cp)?' ':cp));String text=clean.toString().strip();
        int end=Math.min(200,text.length());if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end);
    }
    private static Optional<String> text(JsonNode value,String field,int limit){
        var node=value.get(field);if(node==null||node.isNull())return Optional.empty();if(!node.isTextual()||node.asText().length()>limit)throw new IllegalArgumentException("Invalid Google context");return Optional.of(node.asText());
    }
    private static String https(String value){
        var uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid Google source URL");return value;
    }
}
