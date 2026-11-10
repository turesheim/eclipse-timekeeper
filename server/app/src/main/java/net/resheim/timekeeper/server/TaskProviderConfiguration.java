package net.resheim.timekeeper.server;

import java.net.URI;
import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Operator app registrations; account credentials are separate and never exposed by availability. */
@ApplicationScoped
class TaskProviderConfiguration {
    @Inject BrowserOrigins origins;
    @Inject SharedConnectConfiguration shared;
    @ConfigProperty(name="timekeeper.tasks.google.client-id") Optional<String> googleId;
    @ConfigProperty(name="timekeeper.tasks.google.client-secret") Optional<String> googleSecret;
    @ConfigProperty(name="timekeeper.tasks.microsoft.client-id") Optional<String> microsoftId;
    @ConfigProperty(name="timekeeper.tasks.microsoft.client-secret") Optional<String> microsoftSecret;
    TaskProviderOAuth.Settings settings(TaskCloudProvider.Provider provider){
        try{
            var id=provider==TaskCloudProvider.Provider.GOOGLE?googleId:microsoftId;
            var secret=provider==TaskCloudProvider.Provider.GOOGLE?googleSecret:microsoftSecret;
            String origin=origins.currentOrigin();var uri=URI.create(origin);
            if(uri.getRawAuthority()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                    ||uri.getRawPath()!=null&&!uri.getRawPath().isEmpty())throw new IllegalArgumentException();
            return new TaskProviderOAuth.Settings(id.orElseThrow(),secret.orElseThrow(),origin+"/web/task-provider-callback.html");
        }catch(RuntimeException invalid){throw new ApiFailure(503,"TASK_PROVIDER_NOT_CONFIGURED");}
    }
    boolean available(TaskCloudProvider.Provider provider){if(shared!=null&&shared.enabled())return true;try{settings(provider);return true;}catch(ApiFailure unavailable){return false;}}
    static TaskCloudProvider.Provider provider(String id){return switch(id){case "google"->TaskCloudProvider.Provider.GOOGLE;case "microsoft"->TaskCloudProvider.Provider.MICROSOFT;default->throw ApiFailure.notFound();};}
}
