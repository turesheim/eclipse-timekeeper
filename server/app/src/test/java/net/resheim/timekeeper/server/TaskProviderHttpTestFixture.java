package net.resheim.timekeeper.server;

import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Flow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** Test-only transport replacement; production keeps its fixed HTTPS provider hosts. */
@Alternative @ApplicationScoped
public class TaskProviderHttpTestFixture extends TaskProviderClients {
    static final class State {volatile String title="Cloud task",listName="Work";volatile int failure;final AtomicInteger reads=new AtomicInteger();final AtomicInteger refreshes=new AtomicInteger();}
    static final Map<String,State> STATES=new ConcurrentHashMap<>();
    @Override TaskProviderOAuth oauth(TaskCloudProvider.Provider provider){
        return new TaskProviderOAuth(provider,new TaskProviderHttp(Set.of("oauth2.googleapis.com","login.microsoftonline.com"),request->{
            var form=form(request);String grant=form.get("grant_type"),key;
            if(grant.equals("authorization_code")){
                String code=form.get("code");if(code.startsWith("denied-"))return reply(400,"{\"error\":\"invalid_grant\",\"error_description\":\"private-provider-detail\"}");
                key=JsonValues.hash(code).substring(0,24);STATES.putIfAbsent(key,new State());
            }else{key=form.get("refresh_token").substring("fixture-refresh-".length());STATES.get(key).refreshes.incrementAndGet();}
            String scopes=provider==TaskCloudProvider.Provider.GOOGLE?"openid https://www.googleapis.com/auth/tasks.readonly":"Tasks.Read User.Read";
            return reply(200,JsonValues.object().put("access_token","fixture-access-"+key).put("refresh_token","fixture-refresh-"+key).put("token_type","Bearer").put("expires_in",3600).put("scope",scopes).toString());
        }));
    }
    @Override TaskCloudProvider cloud(TaskCloudProvider.Provider provider){
        return new TaskCloudProvider(provider,new TaskProviderHttp(Set.of("tasks.googleapis.com","openidconnect.googleapis.com","graph.microsoft.com"),request->{
            if(!request.method().equals("GET"))throw new AssertionError("Inbound sync cannot write to the provider");
            String token=request.headers().firstValue("Authorization").orElseThrow().substring("Bearer fixture-access-".length());
            var state=STATES.get(token);String path=request.uri().getPath();
            if(path.equals("/v1/userinfo"))return reply(200,JsonValues.object().put("sub",token).put("name","Google fixture").toString());
            if(path.equals("/v1.0/me"))return reply(200,JsonValues.object().put("id",token).put("displayName","Microsoft fixture").toString());
            boolean google=provider==TaskCloudProvider.Provider.GOOGLE;
            if(path.endsWith("/tasks")){
                state.reads.incrementAndGet();if(state.failure!=0)return new TaskProviderHttp.Reply(state.failure,Map.of("retry-after","60"),"{\"private\":\"provider detail\"}".getBytes(StandardCharsets.UTF_8));
                String list=path.contains("list-2/")?"list-2":"list-1";
                var task=JsonValues.object().put("id","task-"+list).put("title",state.title+" "+list).put("status",google?"needsAction":"notStarted").put("position","001");
                var result=JsonValues.object();result.putArray(google?"items":"value").add(task);return reply(200,result.toString());
            }
            if(path.endsWith("/lists")){
                var result=JsonValues.object();var items=result.putArray(google?"items":"value");
                for(int i=1;i<=2;i++)items.add(JsonValues.object().put("id","list-"+i).put(google?"title":"displayName",i==1?state.listName:"Home").put("isOwner",true).put("isShared",false));
                return reply(200,result.toString());
            }
            throw new AssertionError("Unexpected provider endpoint: "+path);
        }));
    }
    private static TaskProviderHttp.Reply reply(int status,String value){return new TaskProviderHttp.Reply(status,Map.of(),value.getBytes(StandardCharsets.UTF_8));}
    private static Map<String,String> form(HttpRequest request){
        var bytes=new java.io.ByteArrayOutputStream();var finished=new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){byte[] data=new byte[buffer.remaining()];buffer.get(data);bytes.writeBytes(data);}
            public void onComplete(){finished.complete(null);}public void onError(Throwable error){finished.completeExceptionally(error);}
        });finished.join();var result=new HashMap<String,String>();
        for(String pair:bytes.toString(StandardCharsets.UTF_8).split("&")){var parts=pair.split("=",2);result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}return result;
    }
}
