package net.resheim.timekeeper.server;

import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/week-notes") @Blocking @Consumes("application/json") @Produces("application/json")
public class WeekNoteResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Context UriInfo uri;
    private Response tx(Supplier<ApiReplies> operation){return store.authenticated(identity,()->Response.fromResponse(operation.get().response()).header("Cache-Control",null).header("Cache-Control","private, no-store").build());}
    @GET public Response list(@QueryParam("fromWeek")String from,@QueryParam("toWeek")String to){
        ContractInputs.queries(uri.getQueryParameters(),"fromWeek","toWeek");var first=WeekNotes.week(from);var last=WeekNotes.week(to);
        return tx(()->new ApiReplies(200,new WeekNotes(store).list(first,last),null,null));
    }
    @GET @Path("/{week}") public Response get(@PathParam("week")String week){
        ContractInputs.queries(uri.getQueryParameters());var key=WeekNotes.week(week);
        return tx(()->ApiReplies.resource(200,new WeekNotes(store).get(key),null));
    }
    @PUT @Path("/{week}") public Response save(@PathParam("week")String week,@HeaderParam("If-Match")String match,JsonNode body){
        ContractInputs.queries(uri.getQueryParameters());var key=WeekNotes.week(week);var input=inputs.validate("WeekNoteInput",body);
        return tx(()->ApiReplies.resource(200,new WeekNotes(store).save(key,match,input),null));
    }
    @DELETE @Path("/{week}") public Response delete(@PathParam("week")String week,@HeaderParam("If-Match")String match){
        ContractInputs.queries(uri.getQueryParameters());var key=WeekNotes.week(week);
        return tx(()->{var value=new WeekNotes(store).delete(key,match);return new ApiReplies(204,null,JsonValues.etag(value),null);});
    }
    @GET @Path("/{week}/markdown") @Produces("text/markdown") public Response download(@PathParam("week")String week){
        ContractInputs.queries(uri.getQueryParameters());var key=WeekNotes.week(week);
        return store.authenticated(identity,()->{
            var notes=new WeekNotes(store);byte[] bytes=notes.document(key);if(bytes==null)throw ApiFailure.notFound();
            return Response.ok(bytes).type("text/markdown; charset=utf-8").header("Content-Disposition","attachment; filename=\""+key.filename()+"\"")
                    .header("ETag",JsonValues.etag(notes.get(key))).header("Cache-Control","private, no-store").build();
        });
    }
}
