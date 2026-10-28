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
    @Inject Pages pages;
    @Context UriInfo uri;
    private Response tx(Supplier<ApiReplies> operation){return tx(DayNoteHistory.Source.REST_LEGACY,operation);}
    private Response tx(DayNoteHistory.Source source,Supplier<ApiReplies> operation){return store.authenticated(identity,()->store.withJournalSource(source,()->Response.fromResponse(operation.get().response()).header("Cache-Control",null).header("Cache-Control","private, no-store").build()));}
    @GET @Path("/{date}/history") public Response history(@PathParam("date")String date){
        var query=uri.getQueryParameters();ContractInputs.queries(query,"limit","cursor");var day=DayNotes.date(date);
        int limit=ContractInputs.number(query.getFirst("limit"),50,1,100);
        return tx(()->new ApiReplies(200,new DayNoteHistory(store).page(day,limit,query.getFirst("cursor"),pages),null,null));
    }
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
    @GET @Path("/{date}/content") public Response content(@PathParam("date")String date){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);
        return tx(DayNoteHistory.Source.REST_NOTE,()->ApiReplies.resource(200,new DayNotes(store).getContent(day),null));
    }
    @PUT @Path("/{date}/content") public Response content(@PathParam("date")String date,@HeaderParam("If-Match")String match,JsonNode body){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);var input=inputs.validate("DayNoteContentInput",body);
        return tx(DayNoteHistory.Source.REST_NOTE,()->ApiReplies.resource(200,new DayNotes(store).saveContent(day,match,input),null));
    }
    @DELETE @Path("/{date}/content") public Response deleteContent(@PathParam("date")String date,@HeaderParam("If-Match")String match){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);
        return tx(DayNoteHistory.Source.REST_NOTE,()->{var value=new DayNotes(store).deleteContent(day,match);return new ApiReplies(204,null,JsonValues.etag(value),null);});
    }

}
