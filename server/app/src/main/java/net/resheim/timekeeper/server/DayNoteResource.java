package net.resheim.timekeeper.server;

import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/day-notes") @Blocking @Consumes("application/json") @Produces("application/json")
public class DayNoteResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Context UriInfo uri;
    private Response tx(Supplier<ApiReplies> operation){return store.authenticated(identity,()->Response.fromResponse(operation.get().response()).header("Cache-Control",null).header("Cache-Control","private, no-store").build());}
    @GET public Response list(@QueryParam("fromDate")String from,@QueryParam("toDate")String to){
        ContractInputs.queries(uri.getQueryParameters(),"fromDate","toDate");var first=DayNotes.date(from);var last=DayNotes.date(to);
        return tx(()->new ApiReplies(200,new DayNotes(store).list(first,last),null,null));
    }
    @GET @Path("/{date}") public Response get(@PathParam("date")String date){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);
        return tx(()->ApiReplies.resource(200,new DayNotes(store).get(day),null));
    }
    @PUT @Path("/{date}") public Response save(@PathParam("date")String date,@HeaderParam("If-Match")String match,JsonNode body){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);var input=inputs.validate("DayNoteInput",body);
        return tx(()->ApiReplies.resource(200,new DayNotes(store).save(day,match,input),null));
    }
    @DELETE @Path("/{date}") public Response delete(@PathParam("date")String date,@HeaderParam("If-Match")String match){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);
        return tx(()->{var value=new DayNotes(store).delete(day,match);return new ApiReplies(204,null,JsonValues.etag(value),null);});
    }
}
