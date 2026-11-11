package net.resheim.timekeeper.server;

import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/task-providers/{connection}/identities/{list}") @Blocking @Consumes("application/json") @Produces("application/json")
public class TaskProviderIdentitiesResource {
    @Inject ServerStore store;@Inject RequestIdentity identity;@Inject ContractInputs inputs;@Inject CommandReceipts receipts;@Inject TaskProviderRefresh refresh;
    @Context UriInfo uri;
    private TaskProviderIdentities identities(){return new TaskProviderIdentities(store,receipts,inputs);}
    @GET public Response list(@PathParam("connection")String connection,@PathParam("list")String list,@QueryParam("q")String query){UUID key=ContractInputs.pathId(connection),listKey=ContractInputs.pathId(list);ContractInputs.queries(uri.getQueryParameters(),"q");return store.authenticated(identity,()->new ApiReplies(200,identities().list(key,listKey,query),null,null).response());}
    @POST public Response decide(@PathParam("connection")String connection,@PathParam("list")String list,@HeaderParam("If-Match")String match,JsonNode body){UUID key=ContractInputs.pathId(connection),listKey=ContractInputs.pathId(list);ContractInputs.queries(uri.getQueryParameters());var input=inputs.validate("TaskProviderIdentityDecisionInput",body);var result=store.authenticated(identity,()->identities().decide(key,listKey,match,input).response());refresh.wake();return result;}
}
