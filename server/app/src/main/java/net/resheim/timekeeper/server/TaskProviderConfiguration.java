package net.resheim.timekeeper.server;

import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Operator app registrations; account credentials are separate and never exposed by availability. */
@ApplicationScoped
class TaskProviderConfiguration {
    @Inject BrowserOrigins origins;
    @ConfigProperty(name="timekeeper.tasks.google.client-id") volatile Optional<String> googleId;
    @ConfigProperty(name="timekeeper.tasks.google.client-secret") volatile Optional<String> googleSecret;
    @ConfigProperty(name="timekeeper.tasks.microsoft.client-id") volatile Optional<String> microsoftId;
    @ConfigProperty(name="timekeeper.tasks.microsoft.client-secret") volatile Optional<String> microsoftSecret;
    record State(boolean shared,String serviceUrl,String googleId,String googleSecret,String microsoftId,String microsoftSecret) {
        @Override public String toString(){return "Private Internet account configuration";}
    }
    private volatile State active;
    State current(){return active;}
    void configure(JsonNode value){
        String googleId=value.path("googleClientId").asText(),googleSecret=value.path("googleClientSecret").asText();
        String microsoftId=value.path("microsoftClientId").asText(),microsoftSecret=value.path("microsoftClientSecret").asText();
        active=new State(value.path("mode").asText().equals("SHARED"),value.path("serviceUrl").isNull()?null:value.path("serviceUrl").asText(),googleId,googleSecret,microsoftId,microsoftSecret);
    }
    String fingerprint(){
        var state=active;
        return JsonValues.hash(state==null?googleId+"\n"+googleSecret+"\n"+microsoftId+"\n"+microsoftSecret:
            state.shared()+"\n"+state.serviceUrl()+"\n"+state.googleId()+"\n"+state.googleSecret()+"\n"+state.microsoftId()+"\n"+state.microsoftSecret());
    }
    TaskProviderOAuth.Settings settings(TaskCloudProvider.Provider provider){
        try{
            var state=active;
            var id=state==null?(provider==TaskCloudProvider.Provider.GOOGLE?googleId:microsoftId):
                Optional.of(provider==TaskCloudProvider.Provider.GOOGLE?state.googleId():state.microsoftId()).filter(value->!value.isBlank());
            var secret=state==null?(provider==TaskCloudProvider.Provider.GOOGLE?googleSecret:microsoftSecret):
                Optional.of(provider==TaskCloudProvider.Provider.GOOGLE?state.googleSecret():state.microsoftSecret()).filter(value->!value.isBlank());
            String origin=origins.currentOrigin();var uri=URI.create(origin);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawAuthority()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                    ||uri.getRawPath()!=null&&!uri.getRawPath().isEmpty())throw new IllegalArgumentException();
            return new TaskProviderOAuth.Settings(id.orElseThrow(),secret.orElseThrow(),origin+"/web/task-provider-callback.html");
        }catch(RuntimeException invalid){throw new ApiFailure(503,"TASK_PROVIDER_NOT_CONFIGURED");}
    }
    boolean available(TaskCloudProvider.Provider provider){try{settings(provider);return true;}catch(ApiFailure unavailable){return false;}}
    static TaskCloudProvider.Provider provider(String id){return switch(id){case "google"->TaskCloudProvider.Provider.GOOGLE;case "microsoft"->TaskCloudProvider.Provider.MICROSOFT;default->throw ApiFailure.notFound();};}
}
