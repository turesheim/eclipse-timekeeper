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
    @Inject InternetAccountSetup setup;
    @Inject SharedConnectConfiguration shared;
    @Inject TaskProviderClients clients;
    @Inject TaskProviderRefresh refresh;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    private Response tx(Supplier<ApiReplies> work){return store.authenticated(identity,()->work.get().response());}
    private void queries(){ContractInputs.queries(uri.getQueryParameters());}
    private String browser(){return BrowserCookies.read(headers.getRequestHeader("Cookie")).requireBrowser();}
    @GET public Response list(@QueryParam("check") @DefaultValue("false") String check){
        ContractInputs.queries(uri.getQueryParameters(),"check");if(!Set.of("true","false").contains(check))throw ApiFailure.validation();
        var result=store.authenticated(identity,()->connections().list());
        var readiness=setup.readiness(check.equals("true"));result.put("refreshMinutes",15).put("calendarSynchronization",true);result.set("readiness",readiness);
        var providers=result.putObject("providers");for(String provider:List.of("google","microsoft"))providers.put(provider,readiness.path(provider).asText().equals("READY"));
        return new ApiReplies(200,result,null,null).response();
    }
    @GET @Path("/{id}") public Response get(@PathParam("id")String id){
        UUID key=ContractInputs.pathId(id);queries();return tx(()->ApiReplies.resource(200,connections().view(key),null));
    }
    @POST @Path("/{provider}/authorize") public Response authorize(@PathParam("provider")String provider,JsonNode body){
        var input=inputs.validate("TaskProviderAuthorizeInput",body);queries();var kind=TaskProviderConfiguration.provider(provider);String browser=browser();
        boolean writable=input.path("direction").asText("IMPORT").equals("TWO_WAY");
        if(writable&&kind!=TaskCloudProvider.Provider.GOOGLE)throw new ApiFailure(422,"TASK_PROVIDER_DIRECTION_UNSUPPORTED");
        var services=input.path("services").isObject()?((com.fasterxml.jackson.databind.node.ObjectNode)input.get("services")).deepCopy():object().put("tasks",true).put("calendars",false);services.put("writableTasks",writable);SharedConnectClient.services(services);
        store.authenticated(identity,()->null);setup.require(kind);
        if(shared.enabled()){
            var endpoint=shared.endpoint();var pending=store.authenticated(identity,()->connections().beginShared(kind,endpoint,browser,services));
            try{return new ApiReplies(200,clients.shared(endpoint).begin(provider,shared.callback(),pending.state(),pending.challenge(),services,input.path("language").asText("en")),null,null).response();}
            catch(RuntimeException failure){try{store.authenticated(identity,()->connections().consume(pending.state(),browser));}catch(RuntimeException ignored){}throw failure;}
        }
        var settings=configuration.settings(kind);
        return tx(()->new ApiReplies(200,connections().begin(kind,settings,browser,services),null,null));
    }
    @POST @Path("/cancel") public Response cancel(JsonNode body){
        var input=inputs.validate("TaskProviderCancelInput",body);queries();String browser=browser();
        return tx(()->{connections().consume(input.path("state").asText(),browser);return new ApiReplies(204,null,null,null);});
    }
    @POST @Path("/complete") public Response complete(JsonNode body){
        var input=inputs.validate("TaskProviderCompleteInput",body);queries();String browser=browser();
        var grant=store.authenticated(identity,()->connections().consume(input.path("state").asText(),browser));
        try{
            TaskProviderOAuth.Settings settings=null;TaskProviderOAuth.Tokens tokens;JsonNode reply=null;
            if(grant.broker()!=null){
                reply=clients.shared(SharedConnectConfiguration.validate(grant.broker())).redeem(input.path("code").asText(),grant.verifier());
                if(!reply.path("provider").asText().equals(new TaskCloudProvider(grant.provider()).id())||!reply.path("services").equals(grant.services()))throw new ApiFailure(409,"TASK_PROVIDER_PERMISSION_REQUIRED");
                tokens=SharedConnectClient.tokens(reply,reply.path("grantCredential").asText());
            }else{
                settings=configuration.settings(grant.provider());
                if(!TaskProviderConnections.settingsHash(settings).equals(grant.settingsHash()))throw new ApiFailure(409,"TASK_PROVIDER_CONFIGURATION_CHANGED");
                tokens=clients.oauth(grant.provider()).exchange(settings,input.path("code").asText(),grant.verifier(),clock.now(),grant.services());
            }
            var provider=clients.cloud(grant.provider());var account=provider.account(tokens.access());
            if(reply!=null&&!account.id().equals(reply.path("remoteAccountId").asText()))throw new ApiFailure(409,"TASK_PROVIDER_ACCOUNT_CHANGED");
            List<TaskCloudProvider.RemoteList> observed=List.of();RuntimeException taskCatalogFailure=null;
            if(grant.services().path("tasks").asBoolean())try{observed=provider.catalog(tokens.access());}catch(RuntimeException failure){observed=null;taskCatalogFailure=failure;}
            var catalog=observed;var direct=settings;var connected=store.authenticated(identity,()->grant.broker()!=null?connections().connectShared(grant,account,tokens,catalog):connections().connect(grant,direct,account,tokens,catalog));
            if(taskCatalogFailure!=null){String code=InternetCalendars.failure(taskCatalogFailure);store.authenticated(identity,()->{var auth=connections().required(uuid(connected.get("id")));auth.put("lastError",code);auth.withObject("listErrors").put("",code);connections().save(TaskProviderConnections.AUTH,auth);return null;});}
            if(grant.services().path("calendars").asBoolean()){
                try{var calendars=clients.calendars(grant.provider()).catalog(tokens.access());store.authenticated(identity,()->{new InternetCalendars(store).catalog(uuid(connected.get("id")),calendars);return null;});}
                catch(RuntimeException failure){store.authenticated(identity,()->{new InternetCalendars(store).error(uuid(connected.get("id")),InternetCalendars.failure(failure));return null;});}
            }
            refresh.wake();return tx(()->ApiReplies.resource(200,connections().view(uuid(connected.get("id"))),null));
        }catch(TaskProviderHttp.Failure failure){throw providerFailure(failure);}
    }
    @PUT @Path("/{id}/lists") public Response select(@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        UUID key=ContractInputs.pathId(id);var input=inputs.validate("TaskProviderSelectionInput",body);queries();
        var selected=new ArrayList<String>();input.path("selectedLists").forEach(value->selected.add(value.asText()));
        var response=tx(()->ApiReplies.resource(200,connections().select(key,selected,input.get("projectMappings"),match,input.has("reviewAdditions")?input.path("reviewAdditions").asBoolean():null),null));refresh.wake();return response;
    }
    @PUT @Path("/{id}/calendars") public Response calendars(@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        UUID key=ContractInputs.pathId(id);var input=inputs.validate("InternetCalendarSelectionInput",body);queries();
        var response=tx(()->ApiReplies.resource(200,new InternetCalendars(store).select(key,input,match),null));refresh.wake();return response;
    }
    @GET @Path("/{id}/imports/{list}") public Response importPreview(@PathParam("id")String id,@PathParam("list")String list){
        UUID key=ContractInputs.pathId(id),listKey=ContractInputs.pathId(list);queries();return tx(()->ApiReplies.resource(200,new TaskProviderScans(store,receipts,inputs).review(key,listKey),null));
    }
    @POST @Path("/{id}/imports/{list}") @Consumes("*/*") public Response importApprove(@PathParam("id")String id,@PathParam("list")String list,@HeaderParam("If-Match")String match){
        UUID key=ContractInputs.pathId(id),listKey=ContractInputs.pathId(list);queries();
        var response=tx(()->{new TaskProviderScans(store,receipts,inputs).approve(key,listKey,match);return ApiReplies.resource(200,connections().visible(connections().required(key)),null);});refresh.wake();return response;
    }
    @DELETE @Path("/{id}") public Response disconnect(@PathParam("id")String id,@HeaderParam("If-Match")String match){
        UUID key=ContractInputs.pathId(id);queries();var response=tx(()->{connections().disconnect(key,match);return new ApiReplies(204,null,null,null);});refresh.wake();return response;
    }
    @PUT @Path("/{id}/state") public Response state(@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        UUID key=ContractInputs.pathId(id);var input=inputs.validate("TaskProviderStateInput",body);queries();
        var response=tx(()->ApiReplies.resource(200,connections().pause(key,input.path("paused").asBoolean(),match),null));refresh.wake();return response;
    }
    @POST @Path("/{id}/refresh") @Consumes("*/*") public Response refresh(@PathParam("id")String id){
        UUID key=ContractInputs.pathId(id);queries();return refresh.refresh(identity,key).response();
    }
    private static ApiFailure providerFailure(TaskProviderHttp.Failure failure){
        int status=failure.status==429?429:Set.of("TASK_PROVIDER_RECONNECT_REQUIRED","TASK_PROVIDER_OFFLINE_ACCESS_REQUIRED","TASK_PROVIDER_PERMISSION_REQUIRED","TASK_PROVIDER_ACCOUNT_CHANGED").contains(failure.getMessage())?409:503;
        return new ApiFailure(status,failure.getMessage());
    }
}
