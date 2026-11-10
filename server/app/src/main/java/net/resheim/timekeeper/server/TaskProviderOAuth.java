package net.resheim.timekeeper.server;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Provider authorization-code/refresh transport. The coordinator owns one-use, owner-bound state. */
final class TaskProviderOAuth {
    record Settings(String clientId,String clientSecret,String redirect) {
        Settings {
            if(!bounded(clientId,1024)||!bounded(clientSecret,16384))throw new IllegalArgumentException("Invalid task-provider configuration");
            try{
                var uri=URI.create(redirect);
                boolean local=Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
                if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                        ||!(uri.getScheme().equals("https")||uri.getScheme().equals("http")&&local))throw new IllegalArgumentException();
            }catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid task-provider callback");}
        }
        @Override public String toString(){return "Task provider configuration";}
    }
    record Attempt(String state,String verifier,String address) {
        @Override public String toString(){return "Task provider authorization attempt";}
    }
    record Tokens(String access,String refresh,Instant expires,Set<String> scopes) {
        Tokens {scopes=Set.copyOf(scopes);}
        @Override public String toString(){return "Task provider credentials";}
    }
    private static final Base64.Encoder BASE64=Base64.getUrlEncoder().withoutPadding();
    private static final String GOOGLE_READ="https://www.googleapis.com/auth/tasks.readonly",GOOGLE_WRITE="https://www.googleapis.com/auth/tasks";
    private final TaskCloudProvider.Provider provider;
    private final TaskProviderHttp http;
    TaskProviderOAuth(TaskCloudProvider.Provider provider){this(provider,new TaskProviderHttp(Set.of("oauth2.googleapis.com","login.microsoftonline.com")));}
    TaskProviderOAuth(TaskCloudProvider.Provider provider,TaskProviderHttp http){this.provider=provider;this.http=http;}

    Attempt begin(Settings settings){return begin(settings,false);}
    Attempt begin(Settings settings,boolean writable){
        var random=new SecureRandom();byte[] state=new byte[32],verifier=new byte[32];random.nextBytes(state);random.nextBytes(verifier);
        String opaque=BASE64.encodeToString(state),proof=BASE64.encodeToString(verifier);
        var values=new LinkedHashMap<String,String>();values.put("client_id",settings.clientId());values.put("redirect_uri",settings.redirect());
        values.put("response_type","code");values.put("scope",requestedScopes(writable));values.put("state",opaque);values.put("code_challenge",challenge(proof));values.put("code_challenge_method","S256");
        if(provider==TaskCloudProvider.Provider.GOOGLE){values.put("access_type","offline");values.put("prompt","consent");}
        else values.put("response_mode","query");
        return new Attempt(opaque,proof,authorize()+"?"+form(values));
    }
    Tokens exchange(Settings settings,String code,String verifier,Instant now){return exchange(settings,code,verifier,now,false);}
    Tokens exchange(Settings settings,String code,String verifier,Instant now,boolean writable){
        if(!bounded(code,8192)||verifier==null||!verifier.matches("[A-Za-z0-9._~-]{43,128}"))throw failure("TASK_PROVIDER_AUTHORIZATION_EXPIRED");
        var values=client(settings);values.put("grant_type","authorization_code");values.put("code",code);values.put("code_verifier",verifier);values.put("redirect_uri",settings.redirect());
        return tokens(send(values),null,now,writable);
    }
    Tokens refresh(Settings settings,Tokens previous,Instant now){return refresh(settings,previous,now,false);}
    Tokens refresh(Settings settings,Tokens previous,Instant now,boolean writable){
        if(previous==null||!secret(previous.refresh()))throw failure("TASK_PROVIDER_RECONNECT_REQUIRED");
        if(writable&&!canWrite(previous.scopes()))throw failure("TASK_PROVIDER_PERMISSION_REQUIRED");
        var values=client(settings);values.put("grant_type","refresh_token");values.put("refresh_token",previous.refresh());
        if(provider==TaskCloudProvider.Provider.MICROSOFT)values.put("scope",requestedScopes(writable));
        return tokens(send(values),previous,now,writable);
    }
    private LinkedHashMap<String,String> client(Settings settings){
        var values=new LinkedHashMap<String,String>();values.put("client_id",settings.clientId());values.put("client_secret",settings.clientSecret());return values;
    }
    private JsonNode send(Map<String,String> values){
        var request=HttpRequest.newBuilder(http.address(token())).timeout(Duration.ofSeconds(25)).header("Accept","application/json")
                .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(values),StandardCharsets.UTF_8)).build();
        try{return http.send(request);}
        catch(TaskProviderHttp.Failure failure){
            // OAuth invalid_grant and invalid_client use 400; their bodies can contain credentials.
            if(Set.of(400,401,403).contains(failure.status))throw failure("TASK_PROVIDER_RECONNECT_REQUIRED");
            throw failure;
        }
    }
    private Tokens tokens(JsonNode value,Tokens previous,Instant now,boolean writable){
        if(!value.path("access_token").isTextual()||!value.path("token_type").isTextual()
                ||value.has("refresh_token")&&!value.get("refresh_token").isTextual())throw failure("TASK_PROVIDER_INVALID_RESPONSE");
        String access=value.path("access_token").asText("");
        String refresh=value.hasNonNull("refresh_token")?value.path("refresh_token").asText():previous==null?"":previous.refresh();
        if(!value.path("token_type").asText().equalsIgnoreCase("Bearer")||!secret(access))throw failure("TASK_PROVIDER_INVALID_RESPONSE");
        if(!secret(refresh))throw failure("TASK_PROVIDER_OFFLINE_ACCESS_REQUIRED");
        long seconds;try{String raw=value.path("expires_in").asText();if(!raw.matches("[0-9]{1,5}"))throw new IllegalArgumentException();seconds=Long.parseLong(raw);if(seconds<1||seconds>86400)throw new IllegalArgumentException();}
        catch(RuntimeException invalid){throw failure("TASK_PROVIDER_INVALID_RESPONSE");}
        Set<String> scopes;
        if(value.has("scope")){
            var supplied=value.get("scope");if(!supplied.isTextual()||supplied.asText().isBlank()||supplied.asText().length()>16384)throw failure("TASK_PROVIDER_INVALID_RESPONSE");
            scopes=new HashSet<>(Arrays.asList(supplied.asText().trim().split(" +")));
        }else if(previous!=null)scopes=previous.scopes();else throw failure("TASK_PROVIDER_PERMISSION_REQUIRED");
        boolean readable=provider==TaskCloudProvider.Provider.GOOGLE?scopes.contains(GOOGLE_READ)||scopes.contains(GOOGLE_WRITE)
                :hasGraphScope(scopes,"Tasks.Read")||hasGraphScope(scopes,"Tasks.ReadWrite");
        boolean identity=provider==TaskCloudProvider.Provider.GOOGLE?scopes.contains("openid"):hasGraphScope(scopes,"User.Read");
        if(!readable||!identity||writable&&!canWrite(scopes))throw failure("TASK_PROVIDER_PERMISSION_REQUIRED");
        return new Tokens(access,refresh,now.plusSeconds(seconds),scopes);
    }
    private static boolean hasGraphScope(Set<String> scopes,String name){return scopes.contains(name)||scopes.contains("https://graph.microsoft.com/"+name);}
    boolean canWrite(Set<String> scopes){return provider==TaskCloudProvider.Provider.GOOGLE?scopes.contains(GOOGLE_WRITE):hasGraphScope(scopes,"Tasks.ReadWrite");}
    private String requestedScopes(boolean writable){return provider==TaskCloudProvider.Provider.GOOGLE?"openid "+(writable?GOOGLE_WRITE:GOOGLE_READ):"openid offline_access https://graph.microsoft.com/User.Read https://graph.microsoft.com/Tasks."+(writable?"ReadWrite":"Read");}
    private String authorize(){return provider==TaskCloudProvider.Provider.GOOGLE?"https://accounts.google.com/o/oauth2/v2/auth":"https://login.microsoftonline.com/common/oauth2/v2.0/authorize";}
    private String token(){return provider==TaskCloudProvider.Provider.GOOGLE?"https://oauth2.googleapis.com/token":"https://login.microsoftonline.com/common/oauth2/v2.0/token";}
    static String challenge(String verifier){
        try{return BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static String form(Map<String,String> values){return values.entrySet().stream().map(entry->TaskCloudProvider.segment(entry.getKey())+"="+TaskCloudProvider.segment(entry.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    private static boolean bounded(String value,int limit){return value!=null&&!value.isBlank()&&value.length()<=limit&&value.codePoints().noneMatch(Character::isISOControl);}
    private static boolean secret(String value){return value!=null&&!value.isBlank()&&value.length()<=16384&&value.chars().allMatch(character->character>=33&&character<=126);}
    private static TaskProviderHttp.Failure failure(String code){return new TaskProviderHttp.Failure(code,0,0,false);}
}
