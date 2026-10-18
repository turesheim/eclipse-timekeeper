package net.resheim.timekeeper.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/undo") @Blocking @Consumes("application/json") @Produces("application/json")
public class UndoResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;
    @POST @Path("/{id}") public Response undo(@PathParam("id") String id,@HeaderParam("Idempotency-Key") String key,JsonNode body) {
        var action=ContractInputs.pathId(id);var input=inputs.validate("UndoInput",body);
        ContractInputs.queries(uri.getQueryParameters());
        return store.authenticated(identity,()->receipts.execute(key,"POST","/undo/"+id,null,input,()->new WorkSessionUndo(store).apply(action)).response());
    }
}
