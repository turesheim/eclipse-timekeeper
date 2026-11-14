package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Explicit administrator consent checks use temporary, owner/browser-bound grants. */
@ApplicationScoped
class InternetAccountVerification {
    @Inject ServerStore store;
    @Inject BackendClock clock;
    @Inject InstanceAdministrator administrators;
    @Inject TaskProviderConfiguration configuration;
    @Inject SharedConnectConfiguration shared;
    @Inject TaskProviderClients clients;
    @Inject TaskProviderRefresh refresh;

    ObjectNode complete(RequestIdentity identity,TaskProviderConnections.Grant grant,String code){
        administrators.require(identity.principal());
        var checks=object();
        for(String name:List.of("configuration","authorization","offlineAccess"))check(checks,name,"NOT_CHECKED",null);
        for(String name:List.of("calendars","tasks"))check(checks,name,grant.services().path(name).asBoolean()?"NOT_CHECKED":"NOT_REQUESTED",null);
        check(checks,"taskWrites",grant.writable()?"NOT_CHECKED":"NOT_REQUESTED",null);
        ObjectNode cleanup=null;
        try{
            var settings=current(grant);check(checks,"configuration","PASSED",null);
            TaskProviderOAuth.Tokens tokens;String expectedAccount=null;
            URI broker=grant.broker()==null?null:SharedConnectConfiguration.validate(grant.broker());
            if(broker==null){
                tokens=clients.oauth(grant.provider()).exchange(settings,code,grant.verifier(),clock.now(),grant.services());
            }else{
                var reply=clients.shared(broker).redeem(code,grant.verifier());
                tokens=SharedConnectClient.tokens(reply,reply.path("grantCredential").asText());
                String credential=tokens.refresh();
                cleanup=store.transaction(grant.owner(),()->new SharedConnectRevocations(store).temporary(broker,credential));
                validateShared(reply,grant,null);
                expectedAccount=reply.path("remoteAccountId").asText();
            }
            granted(grant,tokens);
            var cloud=clients.cloud(grant.provider());var account=cloud.account(tokens.access());
            if(expectedAccount!=null&&!expectedAccount.equals(account.id()))throw new ApiFailure(409,"TASK_PROVIDER_ACCOUNT_CHANGED");
            check(checks,"authorization","PASSED",null);
            if(grant.writable())check(checks,"taskWrites","PASSED",null);
            try{
                TaskProviderOAuth.Tokens renewed;
                if(broker==null)renewed=clients.oauth(grant.provider()).refresh(settings,tokens,clock.now(),grant.services());
                else{
                    var reply=clients.shared(broker).refresh(tokens.refresh());validateShared(reply,grant,account.id());
                    renewed=SharedConnectClient.tokens(reply,tokens.refresh());
                }
                granted(grant,renewed);
                if(!cloud.account(renewed.access()).id().equals(account.id()))throw new ApiFailure(409,"TASK_PROVIDER_ACCOUNT_CHANGED");
                tokens=renewed;
                check(checks,"offlineAccess","PASSED",null);
            }catch(RuntimeException failure){check(checks,"offlineAccess","FAILED",failure(failure));}
            String access=tokens.access();
            if(grant.services().path("calendars").asBoolean())
                attempt(checks,"calendars",()->clients.calendars(grant.provider()).verifyAccess(access));
            if(grant.services().path("tasks").asBoolean())attempt(checks,"tasks",()->cloud.lists(access,null));
        }catch(RuntimeException failure){
            String name=checks.path("configuration").path("status").asText().equals("PASSED")?"authorization":"configuration";
            check(checks,name,"FAILED",failure(failure));
        }finally{
            if(cleanup!=null){
                var queued=cleanup;
                try{store.transaction(grant.owner(),()->{new SharedConnectRevocations(store).release(queued);return null;});refresh.wake();}
                catch(RuntimeException unavailable){/* The durable delayed queue still owns this temporary grant. */}
            }
        }
        // Recheck live authority and settings after network I/O before publishing a result.
        administrators.require(identity.principal());
        try{current(grant);}catch(RuntimeException changed){check(checks,"configuration","FAILED",failure(changed));}
        boolean passed=true;
        for(var value:checks)if(!Set.of("PASSED","NOT_REQUESTED").contains(value.path("status").asText()))passed=false;
        var result=object().put("verification",true).put("provider",grant.provider().name().toLowerCase(Locale.ROOT))
            .put("checkedAt",clock.now().toString()).put("status",passed?"PASSED":"FAILED");
        result.set("checks",checks);return result;
    }
    private TaskProviderOAuth.Settings current(TaskProviderConnections.Grant grant){
        if(shared.enabled()!=(grant.broker()!=null))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
        if(grant.broker()!=null){
            if(!shared.endpoint().toString().equals(grant.broker()))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
            return null;
        }
        var settings=configuration.settings(grant.provider());
        if(!TaskProviderConnections.settingsHash(settings).equals(grant.settingsHash()))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
        return settings;
    }
    private static void granted(TaskProviderConnections.Grant grant,TaskProviderOAuth.Tokens tokens){
        if(!ProviderScopes.granted(grant.provider().name().toLowerCase(Locale.ROOT),tokens.scopes(),
            grant.services().path("tasks").asBoolean(),grant.services().path("calendars").asBoolean(),grant.writable()))
            throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
    }
    private static void validateShared(JsonNode reply,TaskProviderConnections.Grant grant,String account){
        if(!reply.path("provider").asText().equals(grant.provider().name().toLowerCase(Locale.ROOT))||!reply.path("services").equals(grant.services()))
            throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
        if(account!=null&&!reply.path("remoteAccountId").asText().equals(account))throw new ApiFailure(409,"TASK_PROVIDER_ACCOUNT_CHANGED");
    }
    private static void attempt(ObjectNode checks,String name,Runnable work){
        try{work.run();check(checks,name,"PASSED",null);}catch(RuntimeException failure){check(checks,name,"FAILED",failure(failure));}
    }
    private static String failure(RuntimeException failure){
        String code=InternetCalendars.failure(failure);
        return code.matches("TASK_PROVIDER_[A-Z_]{1,64}")?code:"TASK_PROVIDER_UNAVAILABLE";
    }
    private static void check(ObjectNode checks,String name,String status,String code){
        checks.set(name,object().put("status",status).put("code",code));
    }
}
