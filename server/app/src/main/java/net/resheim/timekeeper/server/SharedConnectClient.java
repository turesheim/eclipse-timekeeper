package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

/** Installation-side backchannel. The shared service's provider secrets never reach this server. */
final class SharedConnectClient {
    private final URI endpoint;
    private final TaskProviderHttp http;
    SharedConnectClient(URI endpoint){this(endpoint,new TaskProviderHttp(Set.of(endpoint.getHost())));}
    SharedConnectClient(URI endpoint,TaskProviderHttp http){this.endpoint=endpoint;this.http=http;}
    JsonNode providers(){
        var reply=http.send(HttpRequest.newBuilder(http.address(endpoint+"/v1/providers")).timeout(Duration.ofSeconds(10)).header("Accept","application/json").GET().build());
        if(reply.path("protocol").asInt()!=1||!reply.path("google").isBoolean()||!reply.path("microsoft").isBoolean())throw new ApiFailure(503,"TASK_PROVIDER_INVALID_RESPONSE");
        return reply;
    }
    JsonNode begin(String provider,String callback,String state,String challenge,JsonNode services,String language){
        var input=object().put("provider",provider).put("returnUri",callback).put("state",state).put("challenge",challenge).put("language",language);input.set("services",services);
        var reply=post("/v1/requests",input);String target=reply.path("authorizationUrl").asText();
        try{var uri=URI.create(target);if(!uri.getScheme().equals("https")||!origin(uri).equals(endpoint.toString())||!uri.getPath().matches("/connect/[A-Za-z0-9_-]{43}")||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawUserInfo()!=null)throw new IllegalArgumentException();
            Instant expires=Instant.parse(reply.path("expiresAt").asText());if(!expires.isAfter(Instant.now())||expires.isAfter(Instant.now().plusSeconds(660)))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new ApiFailure(503,"TASK_PROVIDER_INVALID_RESPONSE");}
        return object().put("authorizationUrl",target).put("authorizationOrigin",endpoint.toString()).put("expiresAt",reply.path("expiresAt").asText());
    }
    JsonNode redeem(String code,String verifier){return validated(post("/v1/exchanges",object().put("code",code).put("verifier",verifier)),true);}
    JsonNode refresh(String credential){
        try{return validated(http.json("POST",endpoint+"/v1/grants/refresh",credential,null,null),false);}
        catch(TaskProviderHttp.Failure failure){throw failure.status==409?new TaskProviderHttp.Failure("TASK_PROVIDER_RECONNECT_REQUIRED",409,0,false):failure;}
    }
    void disconnect(String credential){http.json("DELETE",endpoint+"/v1/grants/current",credential,null,null);}
    private JsonNode post(String path,JsonNode input){
        try{return http.send(HttpRequest.newBuilder(http.address(endpoint+path)).timeout(Duration.ofSeconds(25)).header("Accept","application/json").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input.toString(),StandardCharsets.UTF_8)).build());}
        catch(TaskProviderHttp.Failure failure){if(failure.status==400||failure.status==403||failure.status==404||failure.status==409||failure.status==410)throw new ApiFailure(409,"TASK_PROVIDER_AUTHORIZATION_EXPIRED");throw failure;}
    }
    private JsonNode validated(JsonNode reply,boolean initial){
        try{
            TaskProviderConfiguration.provider(required(reply,"provider",20));required(reply,"accessToken",16384);required(reply,"remoteAccountId",1024);required(reply,"name",1024);
            Instant expires=Instant.parse(required(reply,"expiresAt",50));if(!expires.isAfter(Instant.now())||expires.isAfter(Instant.now().plusSeconds(86460)))throw new IllegalArgumentException();
            if(initial&&!required(reply,"grantCredential",43).matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException();
            if(!reply.path("scopes").isArray()||reply.path("scopes").size()>100)throw new IllegalArgumentException();for(var scope:reply.path("scopes"))if(!scope.isTextual()||scope.asText().isBlank()||scope.asText().length()>2048)throw new IllegalArgumentException();
            services(reply.path("services"));return reply;
        }catch(RuntimeException invalid){throw new ApiFailure(503,"TASK_PROVIDER_INVALID_RESPONSE");}
    }
    static com.fasterxml.jackson.databind.node.ObjectNode services(JsonNode input){
        if(!input.isObject()||!input.path("tasks").isBoolean()||!input.path("calendars").isBoolean()||!input.path("writableTasks").isBoolean())throw ApiFailure.validation();
        boolean tasks=input.path("tasks").asBoolean(),calendars=input.path("calendars").asBoolean(),writes=input.path("writableTasks").asBoolean();
        if(!tasks&&!calendars||writes&&!tasks)throw ApiFailure.validation();return object().put("tasks",tasks).put("calendars",calendars).put("writableTasks",writes);
    }
    static TaskProviderOAuth.Tokens tokens(JsonNode reply,String credential){
        var scopes=new HashSet<String>();reply.path("scopes").forEach(value->scopes.add(value.asText()));return new TaskProviderOAuth.Tokens(reply.path("accessToken").asText(),credential,Instant.parse(reply.path("expiresAt").asText()),scopes);
    }
    private static String required(JsonNode value,String field,int max){var node=value.get(field);if(node==null||!node.isTextual()||node.asText().isBlank()||node.asText().length()>max||node.asText().codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();return node.asText();}
    private static String origin(URI value){return value.getScheme()+"://"+value.getRawAuthority();}
}
