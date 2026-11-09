package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** Owner-authenticated browser connections. OAuth/network calls never hold an owner transaction. */
@Path("/api/v1/task-providers") @Blocking @Consumes("application/json") @Produces("application/json")
public class TaskProviderResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject BackendClock clock;
    @Inject TaskProviderConfiguration configuration;
    @Inject TaskProviderClients clients;
    @Inject TaskProviderRefresh refresh;
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    private Response tx(Supplier<ApiReplies> work){return store.authenticated(identity,()->work.get().response());}
    private void queries(){ContractInputs.queries(uri.getQueryParameters());}
    private String browser(){return BrowserCookies.read(headers.getRequestHeader("Cookie")).requireBrowser();}
    @GET public Response list(){
        queries();return tx(()->{
            var result=connections().list().put("refreshMinutes",15);var providers=result.putObject("providers");
            providers.put("google",configuration.available(TaskCloudProvider.Provider.GOOGLE)).put("microsoft",configuration.available(TaskCloudProvider.Provider.MICROSOFT));
            return new ApiReplies(200,result,null,null);
        });
    }
    @GET @Path("/{id}") public Response get(@PathParam("id")String id){
        UUID key=ContractInputs.pathId(id);queries();return tx(()->ApiReplies.resource(200,connections().visible(connections().required(key)),null));
    }
    @POST @Path("/{provider}/authorize") public Response authorize(@PathParam("provider")String provider,JsonNode body){
        inputs.validate("TaskProviderAuthorizeInput",body);queries();var kind=TaskProviderConfiguration.provider(provider);var settings=configuration.settings(kind);String browser=browser();
        return tx(()->new ApiReplies(200,connections().begin(kind,settings,browser),null,null));
    }
    @POST @Path("/cancel") public Response cancel(JsonNode body){
        var input=inputs.validate("TaskProviderCancelInput",body);queries();String browser=browser();
        return tx(()->{connections().consume(input.path("state").asText(),browser);return new ApiReplies(204,null,null,null);});
    }
    @POST @Path("/complete") public Response complete(JsonNode body){
        var input=inputs.validate("TaskProviderCompleteInput",body);queries();String browser=browser();
        var grant=store.authenticated(identity,()->connections().consume(input.path("state").asText(),browser));
        var settings=configuration.settings(grant.provider());
        if(!TaskProviderConnections.settingsHash(settings).equals(grant.settingsHash()))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
        try{
            var tokens=clients.oauth(grant.provider()).exchange(settings,input.path("code").asText(),grant.verifier(),clock.now());
            var provider=clients.cloud(grant.provider());var account=provider.account(tokens.access());
            var catalog=provider.catalog(tokens.access());
            var response=tx(()->ApiReplies.resource(200,connections().connect(grant,settings,account,tokens,catalog),null));refresh.wake();return response;
        }catch(TaskProviderHttp.Failure failure){throw providerFailure(failure);}
    }
    @PUT @Path("/{id}/lists") public Response select(@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        UUID key=ContractInputs.pathId(id);var input=inputs.validate("TaskProviderSelectionInput",body);queries();
        var selected=new ArrayList<String>();input.path("selectedLists").forEach(value->selected.add(value.asText()));
        var response=tx(()->ApiReplies.resource(200,connections().select(key,selected,match),null));refresh.wake();return response;
    }
    @DELETE @Path("/{id}") public Response disconnect(@PathParam("id")String id,@HeaderParam("If-Match")String match){
        UUID key=ContractInputs.pathId(id);queries();return tx(()->{connections().disconnect(key,match);return new ApiReplies(204,null,null,null);});
    }
    @POST @Path("/{id}/refresh") @Consumes("*/*") public Response refresh(@PathParam("id")String id){
        UUID key=ContractInputs.pathId(id);queries();return refresh.refresh(identity,key).response();
    }
    private static ApiFailure providerFailure(TaskProviderHttp.Failure failure){
        int status=failure.status==429?429:Set.of("TASK_PROVIDER_RECONNECT_REQUIRED","TASK_PROVIDER_OFFLINE_ACCESS_REQUIRED","TASK_PROVIDER_PERMISSION_REQUIRED","TASK_PROVIDER_ACCOUNT_CHANGED").contains(failure.getMessage())?409:503;
        return new ApiFailure(status,failure.getMessage());
    }
}
