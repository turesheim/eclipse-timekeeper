package net.resheim.timekeeper.server;

import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/event-notes") @Blocking @Consumes("application/json") @Produces("application/json")
public class EventNoteResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Context UriInfo uri;
    private EventNotes notes(){return new EventNotes(store);}
    private Response tx(Supplier<ApiReplies> operation){ContractInputs.queries(uri.getQueryParameters());return store.authenticated(identity,()->Response.fromResponse(operation.get().response()).header("Cache-Control","private, no-store").build());}
    private EventNotes.Key key(String subscription,String event){return new EventNotes.Key(ContractInputs.pathId(subscription),event);}
    @GET public Response list(){return tx(()->new ApiReplies(200,notes().list(),null,null));}
    @GET @Path("/{subscriptionId}/{key}") public Response get(@PathParam("subscriptionId")String subscription,@PathParam("key")String event){var key=key(subscription,event);return tx(()->ApiReplies.resource(200,notes().get(key),null));}
    @PUT @Path("/{subscriptionId}/{key}") public Response save(@PathParam("subscriptionId")String subscription,@PathParam("key")String event,@HeaderParam("If-Match")String match,JsonNode body){var key=key(subscription,event);var input=inputs.validate("EventNoteInput",body);return tx(()->ApiReplies.resource(200,notes().save(key,match,input),null));}
    @DELETE @Path("/{subscriptionId}/{key}") public Response delete(@PathParam("subscriptionId")String subscription,@PathParam("key")String event,@HeaderParam("If-Match")String match){var key=key(subscription,event);return tx(()->{var value=notes().delete(key,match);return new ApiReplies(204,null,JsonValues.etag(value),null);});}
    @GET @Path("/{subscriptionId}/{key}/markdown") @Produces("text/markdown") public Response download(@PathParam("subscriptionId")String subscription,@PathParam("key")String event){
        var key=key(subscription,event);ContractInputs.queries(uri.getQueryParameters());return store.authenticated(identity,()->{var bytes=notes().document(key);if(bytes==null)throw ApiFailure.notFound();return Response.ok(bytes).type("text/markdown; charset=utf-8").header("ETag",JsonValues.etag(notes().get(key))).header("Cache-Control","private, no-store").header("Content-Disposition","attachment; filename=\""+key.filename()+"\"").build();});
    }
}
