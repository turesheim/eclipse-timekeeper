package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.UUID;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/task-providers/{connection}/writes") @Blocking @Consumes("application/json") @Produces("application/json")
public class TaskProviderWritesResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Inject TaskProviderRefresh refresh;
    @Context UriInfo uri;
    private TaskProviderWrites writes(){return new TaskProviderWrites(store,receipts,inputs);}
    private Response tx(Supplier<ApiReplies> work){ContractInputs.queries(uri.getQueryParameters());return store.authenticated(identity,()->work.get().response());}
    @GET public Response list(@PathParam("connection")String connection){UUID key=ContractInputs.pathId(connection);return tx(()->new ApiReplies(200,writes().list(key),null,null));}
    @POST public Response prepare(@PathParam("connection")String connection,JsonNode body){
        UUID key=ContractInputs.pathId(connection);var input=inputs.validate("TaskProviderWriteInput",body);
        return tx(()->ApiReplies.resource(201,writes().prepare(key,uuid(input.get("listId")),uuid(input.get("taskId")),TaskProviderWrites.Action.valueOf(input.path("action").asText())),null));
    }
    @GET @Path("/{id}") public Response get(@PathParam("connection")String connection,@PathParam("id")String id){UUID key=ContractInputs.pathId(connection),write=ContractInputs.pathId(id);return tx(()->ApiReplies.resource(200,writes().get(key,write),null));}
    @POST @Path("/{id}/confirm") @Consumes("*/*") public Response confirm(@PathParam("connection")String connection,@PathParam("id")String id,@HeaderParam("If-Match")String match){
        UUID key=ContractInputs.pathId(connection),write=ContractInputs.pathId(id);var response=tx(()->ApiReplies.resource(202,writes().confirm(key,write,match),null));refresh.wake();return response;
    }
    @DELETE @Path("/{id}") public Response cancel(@PathParam("connection")String connection,@PathParam("id")String id,@HeaderParam("If-Match")String match){UUID key=ContractInputs.pathId(connection),write=ContractInputs.pathId(id);return tx(()->{writes().cancel(key,write,match);return new ApiReplies(204,null,null,null);});}
    @POST @Path("/{id}/check") @Consumes("*/*") public Response check(@PathParam("connection")String connection,@PathParam("id")String id,@HeaderParam("If-Match")String match){
        UUID key=ContractInputs.pathId(connection),write=ContractInputs.pathId(id);var response=tx(()->ApiReplies.resource(202,writes().check(key,write,match),null));refresh.wake();return response;
    }
    @POST @Path("/{id}/resolve") public Response resolve(@PathParam("connection")String connection,@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        UUID key=ContractInputs.pathId(connection),write=ContractInputs.pathId(id);var input=inputs.validate("TaskProviderWriteResolutionInput",body);
        return tx(()->ApiReplies.resource(200,writes().resolve(key,write,match,input.path("taskEtag").asText(),input.path("decision").asText().equals("REMOTE")),null));
    }
    @GET @Path("/{id}/candidates") public Response candidates(@PathParam("connection")String connection,@PathParam("id")String id,@QueryParam("q")String query){
        UUID key=ContractInputs.pathId(connection),write=ContractInputs.pathId(id);ContractInputs.queries(uri.getQueryParameters(),"q");
        return store.authenticated(identity,()->new ApiReplies(200,writes().candidates(key,write,query),null,null).response());
    }
    @POST @Path("/{id}/identity") public Response identify(@PathParam("connection")String connection,@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        UUID key=ContractInputs.pathId(connection),write=ContractInputs.pathId(id);var input=inputs.validate("TaskProviderCreationIdentityInput",body);
        return tx(()->ApiReplies.resource(200,writes().identify(key,write,match,input),null));
    }
    @POST @Path("/{id}/leave-unresolved") public Response leaveUnresolved(@PathParam("connection")String connection,@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        UUID key=ContractInputs.pathId(connection),write=ContractInputs.pathId(id);var input=inputs.validate("TaskProviderLeaveUnresolvedInput",body);
        return tx(()->writes().leaveUnresolved(key,write,match,input));
    }

}
