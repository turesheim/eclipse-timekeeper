package net.resheim.timekeeper.server;

import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/day-labels") @Blocking @Consumes("application/json") @Produces("application/json")
public class DayLabelResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Context UriInfo uri;
    private Response tx(Supplier<ApiReplies> action){return store.authenticated(identity,()->store.withJournalSource(DayNoteHistory.Source.REST_LABEL,()->Response.fromResponse(action.get().response()).header("Cache-Control",null).header("Cache-Control","private, no-store").build()));}
    @GET public Response list(@QueryParam("fromDate")String from,@QueryParam("toDate")String to){
        ContractInputs.queries(uri.getQueryParameters(),"fromDate","toDate");var first=DayNotes.date(from);var last=DayNotes.date(to);
        return tx(()->new ApiReplies(200,new DayLabels(store).list(first,last),null,null));
    }
    @GET @Path("/{date}") public Response get(@PathParam("date")String date){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);
        return tx(()->ApiReplies.resource(200,new DayLabels(store).get(day),null));
    }
    @PUT @Path("/{date}") public Response save(@PathParam("date")String date,@HeaderParam("If-Match")String match,JsonNode body){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);var input=inputs.validate("DayLabelInput",body);
        return tx(()->ApiReplies.resource(200,new DayLabels(store).save(day,match,input.path("text").asText()),null));
    }
    @DELETE @Path("/{date}") public Response clear(@PathParam("date")String date,@HeaderParam("If-Match")String match){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);
        return tx(()->{var value=new DayLabels(store).save(day,match,"");return new ApiReplies(204,null,JsonValues.etag(value),null);});
    }
}
