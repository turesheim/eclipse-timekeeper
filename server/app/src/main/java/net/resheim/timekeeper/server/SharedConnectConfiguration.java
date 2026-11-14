package net.resheim.timekeeper.server;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Project-owned discovery lets installations follow the shared service without OAuth setup. */
@ApplicationScoped
class SharedConnectConfiguration {
    static final String DISCOVERY="https://turesheim.github.io/eclipse-timekeeper/connect/service.json";
    @ConfigProperty(name="timekeeper.connect.shared",defaultValue="true") boolean enabled;
    @ConfigProperty(name="timekeeper.connect.service-url") Optional<String> configured;
    @Inject BrowserOrigins origins;
    @Inject TaskProviderConfiguration providers;
    private URI cached;
    private Instant expires=Instant.EPOCH;
    private Instant failedUntil=Instant.EPOCH;
    private String route;
    boolean enabled(){var state=providers==null?null:providers.current();return state==null?enabled:state.shared();}
    private Optional<String> address(){var state=providers==null?null:providers.current();return state==null?configured:Optional.ofNullable(state.serviceUrl());}
    String configurationKey(){return enabled()+"\n"+address();}
    Optional<URI> configuredEndpoint(){return address().map(SharedConnectConfiguration::validate);}
    String callback(){return origins.currentOrigin()+"/web/task-provider-callback.html";}
    synchronized void recheck(){failedUntil=Instant.EPOCH;expires=Instant.EPOCH;}
    synchronized URI endpoint(){
        var address=address();String current=address.orElse("");
        if(!current.equals(route)){cached=null;expires=Instant.EPOCH;failedUntil=Instant.EPOCH;route=current;}
        if(address.isPresent())return validate(address.get());
        if(cached!=null&&expires.isAfter(Instant.now()))return cached;
        if(failedUntil.isAfter(Instant.now()))throw new ApiFailure(503,"TASK_PROVIDER_UNAVAILABLE");
        try{
            var http=new TaskProviderHttp(Set.of("turesheim.github.io"));var metadata=http.send(HttpRequest.newBuilder(URI.create(DISCOVERY)).timeout(Duration.ofSeconds(5)).header("Accept","application/json").GET().build());
            if(metadata.path("protocol").asInt()!=1||!metadata.path("serviceUrl").isTextual())throw new ApiFailure(503,"TASK_PROVIDER_UNAVAILABLE");
            cached=validate(metadata.path("serviceUrl").asText());expires=Instant.now().plusSeconds(600);return cached;
        }catch(RuntimeException unavailable){failedUntil=Instant.now().plusSeconds(30);throw new ApiFailure(503,"TASK_PROVIDER_UNAVAILABLE");}
    }
    static URI validate(String address){
        try{var uri=URI.create(address);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()!=-1||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getPath().isEmpty()&&!uri.getPath().equals("/"))throw new IllegalArgumentException();return URI.create("https://"+uri.getHost());}
        catch(RuntimeException invalid){throw new ApiFailure(503,"TASK_PROVIDER_UNAVAILABLE");}
    }
}
