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
    private URI cached;
    private Instant expires=Instant.EPOCH;
    private Instant failedUntil=Instant.EPOCH;
    boolean enabled(){return enabled;}
    Optional<URI> configuredEndpoint(){return configured.map(SharedConnectConfiguration::validate);}
    synchronized void configure(boolean shared,String address){enabled=shared;configured=Optional.ofNullable(address);cached=null;expires=Instant.EPOCH;failedUntil=Instant.EPOCH;}
    String callback(){return origins.currentOrigin()+"/web/task-provider-callback.html";}
    synchronized URI endpoint(){
        if(configured.isPresent())return validate(configured.get());
        if(cached!=null&&expires.isAfter(Instant.now()))return cached;
        if(failedUntil.isAfter(Instant.now()))throw new ApiFailure(503,"TASK_PROVIDER_UNAVAILABLE");
        try{
            var http=new TaskProviderHttp(Set.of("turesheim.github.io"));var metadata=http.send(HttpRequest.newBuilder(URI.create(DISCOVERY)).timeout(Duration.ofSeconds(25)).header("Accept","application/json").GET().build());
            if(metadata.path("protocol").asInt()!=1||!metadata.path("serviceUrl").isTextual())throw new ApiFailure(503,"TASK_PROVIDER_UNAVAILABLE");
            cached=validate(metadata.path("serviceUrl").asText());expires=Instant.now().plusSeconds(600);return cached;
        }catch(RuntimeException unavailable){failedUntil=Instant.now().plusSeconds(30);throw new ApiFailure(503,"TASK_PROVIDER_UNAVAILABLE");}
    }
    static URI validate(String address){
        try{var uri=URI.create(address);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()!=-1||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getPath().isEmpty()&&!uri.getPath().equals("/"))throw new IllegalArgumentException();return URI.create("https://"+uri.getHost());}
        catch(RuntimeException invalid){throw new ApiFailure(503,"TASK_PROVIDER_UNAVAILABLE");}
    }
}
