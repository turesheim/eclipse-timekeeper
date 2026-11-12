package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Redacted operator diagnostics; inspecting setup never starts account consent. */
@ApplicationScoped
class InternetAccountSetup {
    @jakarta.annotation.PostConstruct void diagnostics(){EnvironmentAliases.report();}
    @Inject BrowserOrigins origins;
    @Inject SharedConnectConfiguration shared;
    @Inject TaskProviderConfiguration providers;
    @Inject TaskProviderClients clients;

    private ObjectNode cached;
    private java.time.Instant expires=java.time.Instant.EPOCH;
    private String configuration;

    /** Public account status contains no infrastructure addresses or registration details. */
    synchronized ObjectNode readiness(boolean check){
        String current=JsonValues.hash(origins.currentOrigin()+"\n"+shared.enabled()+"\n"+shared.configured+"\n"+providers.googleId+"\n"+providers.googleSecret+"\n"+providers.microsoftId+"\n"+providers.microsoftSecret);
        if(!check&&cached!=null&&current.equals(configuration)&&expires.isAfter(java.time.Instant.now()))return cached.deepCopy();
        if(check)shared.recheck();
        var setup=inspect(true);var result=object();
        for(String provider:java.util.List.of("google","microsoft")){
            String status=setup.path("status").asText();String code=switch(status){
                case "PUBLIC_ORIGIN_REQUIRED" -> "TASK_PROVIDER_PUBLIC_ORIGIN_REQUIRED";
                case "CALLBACK_INVALID" -> "TASK_PROVIDER_CALLBACK_INVALID";
                case "UNAVAILABLE" -> "TASK_PROVIDER_CONNECTION_UNAVAILABLE";
                default -> setup.path(provider+"Configured").asBoolean()?"READY":"TASK_PROVIDER_NOT_CONFIGURED";
            };
            result.put(provider,code);
        }
        configuration=current;cached=result;expires=java.time.Instant.now().plusSeconds(15);return result.deepCopy();
    }
    void require(TaskCloudProvider.Provider provider){
        String status=readiness(true).path(provider.name().toLowerCase(java.util.Locale.ROOT)).asText();
        if(!status.equals("READY"))throw new ApiFailure(503,status);
    }
    ObjectNode inspect(boolean check){
        boolean broker=shared.enabled();String origin=origins.configured()?origins.currentOrigin():null;
        var result=object().put("mode",broker?"SHARED":"DIRECT").put("publicOrigin",origin)
                .put("callbackUrl",origin==null?null:origin+"/web/task-provider-callback.html")
                .put("discoveryUrl",broker?SharedConnectConfiguration.DISCOVERY:null).put("status","NOT_CHECKED");
        for(String field:java.util.List.of("serviceUrl","googleRedirectUrl","googleConfigured","microsoftConfigured"))result.putNull(field);
        if(origin!=null)try{var callback=java.net.URI.create(origin);if(!"https".equals(callback.getScheme())||callback.getHost()==null||callback.getPort()==0||callback.getPort()>65535||callback.getRawUserInfo()!=null||callback.getRawQuery()!=null||callback.getRawFragment()!=null||!callback.getRawPath().isEmpty())throw new IllegalArgumentException();}
        catch(RuntimeException invalid){return result.put("status","CALLBACK_INVALID");}
        if(!broker){
            result.put("googleRedirectUrl",result.path("callbackUrl").isNull()?null:result.path("callbackUrl").asText());
            result.put("googleConfigured",providers.available(TaskCloudProvider.Provider.GOOGLE));
            result.put("microsoftConfigured",providers.available(TaskCloudProvider.Provider.MICROSOFT));
            result.put("status",origin==null?"PUBLIC_ORIGIN_REQUIRED":!result.path("googleConfigured").asBoolean()&&!result.path("microsoftConfigured").asBoolean()?"REGISTRATION_REQUIRED":"READY");return result;
        }
        try{
            var configured=shared.configuredEndpoint();if(configured.isPresent())endpoint(result,configured.get());
            if(origin==null)return result.put("status","PUBLIC_ORIGIN_REQUIRED");
            if(check){
                shared.recheck();var endpoint=shared.endpoint();endpoint(result,endpoint);var registrations=clients.shared(endpoint).providers();
                result.put("googleConfigured",registrations.path("google").asBoolean());
                result.put("microsoftConfigured",registrations.path("microsoft").asBoolean());result.put("status",registrations.path("google").asBoolean()||registrations.path("microsoft").asBoolean()?"READY":"REGISTRATION_REQUIRED");
            }
        }catch(RuntimeException unavailable){result.put("status","UNAVAILABLE");}
        return result;
    }
    private static void endpoint(ObjectNode result,java.net.URI endpoint){
        result.put("serviceUrl",endpoint.toString()).put("googleRedirectUrl",endpoint+"/oauth/google/callback");
    }
}
