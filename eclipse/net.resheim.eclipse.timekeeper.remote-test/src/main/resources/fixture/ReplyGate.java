import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.*;

/** Disposable source-launched proxy. Never logs credentials, request or response bodies. */
public class ReplyGate {
    record Gate(String method,String path, CountDownLatch committed, CountDownLatch release, AtomicReference<Integer> status) { }
    static final AtomicReference<Gate> gate=new AtomicReference<>();
    static final AtomicReference<Gate> armed=new AtomicReference<>();
    public static void main(String[] args) throws Exception {
        URI upstream=URI.create(args[0]);int port=Integer.parseInt(args[1]);
        if(!"127.0.0.1".equals(upstream.getHost())) throw new IllegalArgumentException("Fixture upstream must be loopback");
        var executor=Executors.newCachedThreadPool();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);server.setExecutor(executor);
        var http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
        server.createContext("/",exchange->{
            try {
                String path=exchange.getRequestURI().getPath();
                if(path.startsWith("/__fixture/")) {
                    switch(path) {
                        case "/__fixture/health" -> reply(exchange,200,"ready");
                        case "/__fixture/hold" -> {
                            String target=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                            String method="PUT";
                            if(target.startsWith("POST ")) {method="POST";target=target.substring(5);}
                            else if(target.startsWith("DELETE ")) {method="DELETE";target=target.substring(7);}
                            boolean valid=switch(method) {
                                case "PUT" -> target.matches("/api/v1/tasks/[0-9a-f-]{36}");
                                case "DELETE" -> target.matches("/api/v1/(projects|tasks|activities|labels)/[0-9a-f-]{36}");
                                case "POST" -> target.matches("/api/v1/(projects|tasks|activities|labels)")
                                    || target.equals("/api/v1/data/import") || target.matches("/api/v1/(activities|work-sessions)/start") || target.matches("/api/v1/(activities|work-sessions)/[0-9a-f-]{36}/stop");
                                default -> false;
                            };
                            if(!valid) throw new IllegalArgumentException("Invalid fixture route");
                            var next=new Gate(method,target,new CountDownLatch(1),new CountDownLatch(1),new AtomicReference<>(0));
                            var previous=gate.get();if(previous!=null && previous.release().getCount()!=0) throw new IllegalStateException("Previous gate not released");
                            gate.set(next);armed.set(next);reply(exchange,200,"armed");
                        }
                        case "/__fixture/state" -> {
                            var current=gate.get();reply(exchange,200,current!=null && current.committed().getCount()==0 ? "committed="+current.status().get() : "waiting");
                        }
                        case "/__fixture/release" -> {var current=gate.get();if(current!=null) current.release().countDown();reply(exchange,200,"released");}
                        default -> reply(exchange,404,"unknown fixture control");
                    }
                    return;
                }
                var request=HttpRequest.newBuilder(URI.create(upstream+exchange.getRequestURI().toASCIIString())).timeout(Duration.ofSeconds(30));
                for(String header:Set.of("Authorization","Accept","Content-Type","If-Match","Idempotency-Key","X-Timekeeper-Owner","Cookie","X-CSRF-Token","Origin")) {
                    String value=exchange.getRequestHeaders().getFirst(header);if(value!=null) request.header(header,value);
                }
                byte[] body=exchange.getRequestBody().readAllBytes();
                request.method(exchange.getRequestMethod(),body.length==0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                var response=http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
                var selected=armed.get();
                if(selected!=null && selected.method().equals(exchange.getRequestMethod()) && selected.path().equals(path) && armed.compareAndSet(selected,null)) {
                    selected.status().set(response.statusCode());selected.committed().countDown();
                    if(!selected.release().await(60,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture gate not released");
                }
                for(String header:Set.of("Content-Type","ETag","X-Timekeeper-Owner","Set-Cookie","Cache-Control","Content-Security-Policy","X-Content-Type-Options","Referrer-Policy","WWW-Authenticate")) {
                    var values=response.headers().allValues(header);if(!values.isEmpty()) exchange.getResponseHeaders().put(header,values);
                }
                exchange.sendResponseHeaders(response.statusCode(),response.body().length==0 ? -1 : response.body().length);
                if(response.body().length>0) exchange.getResponseBody().write(response.body());
            } catch(Exception failure) {
                // Disconnects after cancellation/timeout are expected; no private data is printed.
                try {reply(exchange,502,"{\"code\":\"UNAVAILABLE\"}");}catch(Exception disconnected) { }
            } finally {exchange.close();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);executor.shutdownNow();}));
        server.start();new CountDownLatch(1).await();
    }
    static void reply(HttpExchange exchange,int status,String text) throws java.io.IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
}
