package net.resheim.timekeeper.connect;

import static net.resheim.timekeeper.connect.ConnectProtocol.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import net.resheim.timekeeper.server.TaskProviderHttp;

/** Fixed provider endpoints and bounded transport; no provider credentials enter a browser. */
@ApplicationScoped
class ConnectProvider {
    private static final String GOOGLE_TASK_READ="https://www.googleapis.com/auth/tasks.readonly",GOOGLE_TASK_WRITE="https://www.googleapis.com/auth/tasks",GOOGLE_CALENDAR="https://www.googleapis.com/auth/calendar.readonly";
    private final TaskProviderHttp http;
    ConnectProvider(){this(new TaskProviderHttp(Set.of("oauth2.googleapis.com","openidconnect.googleapis.com","login.microsoftonline.com","graph.microsoft.com")));}
    ConnectProvider(TaskProviderHttp http){this.http=http;}
    String authorize(String provider,ConnectSettings.App app,Services services,String state,String verifier){
        var values=new LinkedHashMap<String,String>();values.put("client_id",app.id());values.put("redirect_uri",app.redirect());values.put("response_type","code");values.put("scope",scopes(provider,services));values.put("state",state);values.put("code_challenge",hash(verifier));values.put("code_challenge_method","S256");
        if(provider.equals("google")){values.put("access_type","offline");values.put("prompt","consent select_account");}else{values.put("response_mode","query");values.put("prompt","select_account");}
        return (provider.equals("google")?"https://accounts.google.com/o/oauth2/v2/auth":"https://login.microsoftonline.com/common/oauth2/v2.0/authorize")+"?"+form(values);
    }
    ObjectNode exchange(String provider,ConnectSettings.App app,Services services,String code,String verifier){
        var values=client(app);values.put("grant_type","authorization_code");values.put("redirect_uri",app.redirect());values.put("code",code);values.put("code_verifier",verifier);
        var result=tokens(provider,services,send(provider,values),null);var account=account(provider,result.path("accessToken").asText());result.setAll(account);return result;
    }
    ObjectNode refresh(String provider,ConnectSettings.App app,Services services,JsonNode previous){
        var values=client(app);values.put("grant_type","refresh_token");values.put("refresh_token",text(previous,"refreshToken",16384));
        if(provider.equals("microsoft"))values.put("scope",scopes(provider,services));
        var result=tokens(provider,services,send(provider,values),previous);result.put("remoteAccountId",previous.path("remoteAccountId").asText()).put("name",previous.path("name").asText());return result;
    }
    ObjectNode account(String provider,String token){
        var response=http.json("GET",provider.equals("google")?"https://openidconnect.googleapis.com/v1/userinfo":"https://graph.microsoft.com/v1.0/me?$select=id,displayName,mail,userPrincipalName",token,null,null);
        String id=text(response,provider.equals("google")?"sub":"id",1024),name=response.path(provider.equals("google")?"name":"displayName").asText(id);
        String address=response.path(provider.equals("google")?"email":"mail").asText("");if(address.isBlank()&&!provider.equals("google"))address=response.path("userPrincipalName").asText("");if(!address.isBlank()&&address.length()<=1024)name=address;
        if(name.isBlank()||name.length()>1024)name=id;return object().put("remoteAccountId",id).put("name",name);
    }
    static String scopes(String provider,Services services){
        provider(provider);if(services.writable()&&!provider.equals("google"))throw new ConnectFailure(400,"TASK_WRITES_UNSUPPORTED");
        var scopes=new ArrayList<String>();scopes.add("openid");
        if(provider.equals("google")){scopes.add("email");scopes.add("profile");}
        if(provider.equals("google")){if(services.tasks())scopes.add(services.writable()?GOOGLE_TASK_WRITE:GOOGLE_TASK_READ);if(services.calendars())scopes.add(GOOGLE_CALENDAR);}
        else{scopes.add("offline_access");scopes.add("https://graph.microsoft.com/User.Read");if(services.tasks())scopes.add("https://graph.microsoft.com/Tasks.Read");if(services.calendars())scopes.add("https://graph.microsoft.com/Calendars.Read");}
        return String.join(" ",scopes);
    }
    private LinkedHashMap<String,String> client(ConnectSettings.App app){var values=new LinkedHashMap<String,String>();values.put("client_id",app.id());values.put("client_secret",app.secret());return values;}
    private JsonNode send(String provider,Map<String,String> values){
        String endpoint=provider.equals("google")?"https://oauth2.googleapis.com/token":"https://login.microsoftonline.com/common/oauth2/v2.0/token";
        try{return http.send(HttpRequest.newBuilder(http.address(endpoint)).timeout(Duration.ofSeconds(25)).header("Accept","application/json").header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(values),StandardCharsets.UTF_8)).build());}
        catch(TaskProviderHttp.Failure failure){if(failure.status==400||failure.status==401||failure.status==403)throw new ConnectFailure(409,"RECONNECT_REQUIRED");throw failure;}
    }
    private ObjectNode tokens(String provider,Services services,JsonNode value,JsonNode previous){
        String access=text(value,"access_token",16384),refresh=value.has("refresh_token")?text(value,"refresh_token",16384):previous==null?null:text(previous,"refreshToken",16384);
        if(!secret(access)||refresh!=null&&!secret(refresh))throw new ConnectFailure(503,"INVALID_PROVIDER_RESPONSE");
        if(refresh==null||!value.path("token_type").asText().equalsIgnoreCase("Bearer"))throw new ConnectFailure(409,"OFFLINE_ACCESS_REQUIRED");
        String expiry=value.path("expires_in").asText();if(!expiry.matches("[0-9]{1,5}"))throw new ConnectFailure(503,"INVALID_PROVIDER_RESPONSE");long seconds=Long.parseLong(expiry);if(seconds<1||seconds>86400)throw new ConnectFailure(503,"INVALID_PROVIDER_RESPONSE");
        Set<String> granted=new HashSet<>();
        if(value.has("scope"))granted.addAll(Arrays.asList(text(value,"scope",16384).split(" +")));else if(previous!=null)previous.path("scopes").forEach(item->granted.add(item.asText()));else throw new ConnectFailure(409,"PERMISSION_REQUIRED");
        boolean identity=provider.equals("google")?granted.contains("openid"):has(granted,"User.Read");
        boolean tasks=!services.tasks()||(provider.equals("google")?granted.contains(GOOGLE_TASK_WRITE)||!services.writable()&&granted.contains(GOOGLE_TASK_READ):has(granted,"Tasks.Read")||has(granted,"Tasks.ReadWrite"));
        boolean calendars=!services.calendars()||(provider.equals("google")?granted.contains(GOOGLE_CALENDAR):has(granted,"Calendars.Read")||has(granted,"Calendars.ReadWrite"));
        if(!identity||!tasks||!calendars)throw new ConnectFailure(409,"PERMISSION_REQUIRED");
        var result=object().put("accessToken",access).put("refreshToken",refresh).put("expiresAt",Instant.now().plusSeconds(seconds).toString());var scopes=result.putArray("scopes");granted.stream().sorted().forEach(scopes::add);return result;
    }
    private static boolean has(Set<String> scopes,String name){return scopes.contains(name)||scopes.contains("https://graph.microsoft.com/"+name);}
    private static boolean secret(String value){return !value.isBlank()&&value.chars().allMatch(character->character>=33&&character<=126);}
}
