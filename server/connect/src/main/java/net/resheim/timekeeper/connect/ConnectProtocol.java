package net.resheim.timekeeper.connect;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Values shared by the service's OAuth and installation-handoff protocols. */
final class ConnectProtocol {
    static final ObjectMapper JSON=new ObjectMapper();
    private static final SecureRandom RANDOM=new SecureRandom();
    static ObjectNode object(){return JSON.createObjectNode();}
    static String random(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String hash(String value){
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    static boolean equal(String left,String right){return left!=null&&right!=null&&MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));}
    static String text(JsonNode value,String key,int max){
        var item=value.get(key);if(item==null||!item.isTextual())throw new ConnectFailure(400,"INVALID_REQUEST");
        String result=item.asText();if(result.isBlank()||result.length()>max||result.codePoints().anyMatch(Character::isISOControl))throw new ConnectFailure(400,"INVALID_REQUEST");return result;
    }
    static void fields(JsonNode value,String... allowed){
        if(value==null||!value.isObject())throw new ConnectFailure(400,"INVALID_REQUEST");
        var names=Set.of(allowed);value.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw new ConnectFailure(400,"INVALID_REQUEST");});
    }
    static String opaque(JsonNode value,String key){String result=text(value,key,43);if(!result.matches("[A-Za-z0-9_-]{43}"))throw new ConnectFailure(400,"INVALID_REQUEST");return result;}
    static String provider(String value){if(!Set.of("google","microsoft").contains(value))throw new ConnectFailure(404,"NOT_FOUND");return value;}
    static URI origin(String value){
        URI uri=address(value);
        if(uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()&&!uri.getRawPath().equals("/"))throw new ConnectFailure(400,"INVALID_ORIGIN");
        return URI.create(uri.getScheme()+"://"+uri.getRawAuthority());
    }
    static URI callback(String value){
        URI uri=address(value);if(!uri.getRawPath().equals("/web/task-provider-callback.html"))throw new ConnectFailure(400,"INVALID_RETURN_ADDRESS");return uri;
    }
    private static URI address(String value){
        try{
            var uri=URI.create(value);boolean local=Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost());
            if(value.length()>8192||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                    ||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&local))throw new IllegalArgumentException();return uri;
        }catch(RuntimeException failure){throw new ConnectFailure(400,"INVALID_RETURN_ADDRESS");}
    }
    static String form(Map<String,String> values){return values.entrySet().stream().map(entry->encode(entry.getKey())+"="+encode(entry.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    static String html(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    record Services(boolean tasks,boolean calendars,boolean writable){
        Services{if(!tasks&&!calendars||writable&&!tasks)throw new ConnectFailure(400,"INVALID_SERVICES");}
        static Services read(JsonNode value){
            fields(value,"tasks","calendars","writableTasks");for(String key:List.of("tasks","calendars","writableTasks"))if(value.has(key)&&!value.get(key).isBoolean())throw new ConnectFailure(400,"INVALID_SERVICES");
            return new Services(value.path("tasks").asBoolean(),value.path("calendars").asBoolean(),value.path("writableTasks").asBoolean());
        }
        ObjectNode json(){return object().put("tasks",tasks).put("calendars",calendars).put("writableTasks",writable);}
    }
}
