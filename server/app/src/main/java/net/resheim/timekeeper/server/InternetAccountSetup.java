package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Redacted operator diagnostics; inspecting setup never starts account consent. */
@ApplicationScoped
class InternetAccountSetup {
    @Inject BrowserOrigins origins;
    @Inject SharedConnectConfiguration shared;
    @Inject TaskProviderConfiguration providers;
    @Inject TaskProviderClients clients;

    ObjectNode inspect(boolean check){
        boolean broker=shared.enabled();String origin=origins.configured()?origins.currentOrigin():null;
        var result=object().put("mode",broker?"SHARED":"DIRECT").put("publicOrigin",origin)
                .put("callbackUrl",origin==null?null:origin+"/web/task-provider-callback.html")
                .put("discoveryUrl",broker?SharedConnectConfiguration.DISCOVERY:null).put("status","NOT_CHECKED");
        for(String field:java.util.List.of("serviceUrl","googleRedirectUrl","googleConfigured","microsoftConfigured"))result.putNull(field);
        if(!broker){
            result.put("googleRedirectUrl",result.path("callbackUrl").isNull()?null:result.path("callbackUrl").asText());
            result.put("googleConfigured",providers.available(TaskCloudProvider.Provider.GOOGLE));
            result.put("microsoftConfigured",providers.available(TaskCloudProvider.Provider.MICROSOFT));
            result.put("status",origin==null?"PUBLIC_ORIGIN_REQUIRED":"READY");return result;
        }
        try{
            var configured=shared.configuredEndpoint();if(configured.isPresent())endpoint(result,configured.get());
            if(origin==null)return result.put("status","PUBLIC_ORIGIN_REQUIRED");
            if(check){
                var endpoint=shared.endpoint();endpoint(result,endpoint);var registrations=clients.shared(endpoint).providers();
                result.put("googleConfigured",registrations.path("google").asBoolean());
                result.put("microsoftConfigured",registrations.path("microsoft").asBoolean());result.put("status","READY");
            }
        }catch(RuntimeException unavailable){result.put("status","UNAVAILABLE");}
        return result;
    }
    private static void endpoint(ObjectNode result,java.net.URI endpoint){
        result.put("serviceUrl",endpoint.toString()).put("googleRedirectUrl",endpoint+"/oauth/google/callback");
    }
}
