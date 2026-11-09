package net.resheim.timekeeper.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.databind.JsonNode;

/** Bounded provider transport. No redirects, cookies, response bodies or credentials in errors. */
final class TaskProviderHttp {
    static final int MAX_BYTES=4*1024*1024;
    record Reply(int status,Map<String,String> headers,byte[] body) {
        @Override public String toString(){return "Task provider HTTP response ("+status+")";}
    }
    @FunctionalInterface interface Transport {Reply send(HttpRequest request) throws IOException,InterruptedException;}
    static final class Failure extends RuntimeException {
        final int status,retryAfterSeconds;
        final boolean uncertain;
        Failure(String code,int status,int retryAfterSeconds,boolean uncertain){
            super(code);this.status=status;this.retryAfterSeconds=retryAfterSeconds;this.uncertain=uncertain;
        }
    }
    private final Set<String> hosts;
    private final Transport transport;
    TaskProviderHttp(Set<String> hosts){this(hosts,jdk());}
    TaskProviderHttp(Set<String> hosts,Transport transport){this.hosts=Set.copyOf(hosts);this.transport=transport;}
    private static final class Jdk {
        private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private static Transport jdk(){
        var client=Jdk.CLIENT;
        return request->{
            var pending=client.sendAsync(request,info->limitedBody(info.statusCode()));
            try{
                var reply=pending.get(request.timeout().orElseThrow().toMillis(),TimeUnit.MILLISECONDS);
                return new Reply(reply.statusCode(),Map.of("retry-after",reply.headers().firstValue("Retry-After").orElse("")),reply.body());
            }catch(TimeoutException failure){pending.cancel(true);throw new IOException("Task provider deadline exceeded");}
            catch(InterruptedException failure){pending.cancel(true);throw failure;}
            catch(ExecutionException failure){
                for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof TooLarge large)
                    throw new Failure("TASK_PROVIDER_RESPONSE_TOO_LARGE",large.status,0,!request.method().equals("GET"));
                throw new IOException("Task provider transport failed");
            }
        };
    }
    private static final class TooLarge extends IOException {
        final int status;
        TooLarge(int status){super("Task provider response limit exceeded");this.status=status;}
    }
    /** Cancel before accumulating an oversized body, including streamed/chunked responses. */
    static HttpResponse.BodySubscriber<byte[]> limitedBody(int status){
        var delegate=HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>(){
            private Flow.Subscription subscription;
            private int bytes;
            private boolean finished;
            @Override public CompletionStage<byte[]> getBody(){return delegate.getBody();}
            @Override public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
            @Override public void onNext(List<ByteBuffer> buffers){
                if(finished)return;
                for(var buffer:buffers){
                    if(buffer.remaining()>MAX_BYTES-bytes){finished=true;subscription.cancel();delegate.onError(new TooLarge(status));return;}
                    bytes+=buffer.remaining();
                }
                delegate.onNext(buffers);
            }
            @Override public void onError(Throwable error){if(!finished){finished=true;delegate.onError(error);}}
            @Override public void onComplete(){if(!finished){finished=true;delegate.onComplete();}}
        };
    }
    URI address(String value){
        try{
            var uri=URI.create(value);
            if(!"https".equals(uri.getScheme())||!hosts.contains(uri.getHost())||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1)
                throw new IllegalArgumentException();
            return uri;
        }catch(IllegalArgumentException failure){throw new Failure("TASK_PROVIDER_INVALID_ADDRESS",0,0,false);}
    }
    JsonNode json(String method,String address,String token,JsonNode input,String etag){
        var request=HttpRequest.newBuilder(address(address)).timeout(Duration.ofSeconds(25)).header("Accept","application/json");
        if(token==null||token.isBlank()||token.length()>16384||token.chars().anyMatch(value->value<33||value>126))
            throw new Failure("TASK_PROVIDER_RECONNECT_REQUIRED",401,0,false);
        request.header("Authorization","Bearer "+token);
        if(etag!=null)request.header("If-Match",etag);
        if(input==null)request.method(method,HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(input.toString(),StandardCharsets.UTF_8));
        return send(request.build());
    }
    JsonNode send(HttpRequest request){
        address(request.uri().toString());boolean write=!request.method().equals("GET");
        if(request.timeout().isEmpty()||request.timeout().get().toMillis()<1||request.timeout().get().compareTo(Duration.ofSeconds(30))>0
                ||request.headers().firstValue("Cookie").isPresent())throw new Failure("TASK_PROVIDER_INVALID_REQUEST",0,0,false);
        try{
            var reply=transport.send(request);int status=reply.status();
            if(status<200||status>=300){
                int after=0;try{after=Math.max(0,Math.min(900,Integer.parseInt(reply.headers().getOrDefault("retry-after","0"))));}catch(NumberFormatException ignored){}
                String code=switch(status){case 401,403->"TASK_PROVIDER_RECONNECT_REQUIRED";case 404->"TASK_PROVIDER_NOT_FOUND";case 409,412->"TASK_PROVIDER_CHANGED";case 429->"TASK_PROVIDER_RATE_LIMITED";default->"TASK_PROVIDER_UNAVAILABLE";};
                throw new Failure(code,status,after,write&&status>=500);
            }
            if(reply.body().length>MAX_BYTES)throw new Failure("TASK_PROVIDER_RESPONSE_TOO_LARGE",status,0,write);
            if(status==204)return JsonValues.object();
            var body=JsonValues.MAPPER.readTree(reply.body());
            if(body==null||!body.isObject())throw new Failure("TASK_PROVIDER_INVALID_RESPONSE",status,0,write);
            return body;
        }catch(Failure failure){
            if(write&&failure.status>=200&&failure.status<300&&!failure.uncertain)
                throw new Failure(failure.getMessage(),failure.status,failure.retryAfterSeconds,true);
            throw failure;
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new Failure("TASK_PROVIDER_CANCELLED",0,0,write);}
        catch(IOException|IllegalArgumentException failure){throw new Failure("TASK_PROVIDER_UNAVAILABLE",0,0,write);}
    }
}
