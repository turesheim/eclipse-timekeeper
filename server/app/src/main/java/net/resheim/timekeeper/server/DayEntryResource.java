package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** Atomic form command; omitted resources do not participate in conflicts. */
@Path("/api/v1/day-entries/{date}") @Blocking @Consumes("application/json") @Produces("application/json")
public class DayEntryResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Context UriInfo uri;
    private ObjectNode entry(java.time.LocalDate date){
        var notes=new DayNotes(store);var labels=new DayLabels(store);
        return notes.get(date).put("noteEtag",etag(notes.getContent(date))).put("labelEtag",etag(labels.get(date)));
    }
    private Response reply(java.time.LocalDate date){
        return Response.fromResponse(ApiReplies.resource(200,entry(date),null).response()).header("Cache-Control",null).header("Cache-Control","private, no-store").build();
    }
    @GET public Response get(@PathParam("date")String date){
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);
        return store.authenticated(identity,()->reply(day));
    }
    @POST public Response edit(@PathParam("date")String date,@HeaderParam("If-Match")String match,JsonNode body){
        if(match!=null)throw ApiFailure.badRequest();
        ContractInputs.queries(uri.getQueryParameters());var day=DayNotes.date(date);var input=inputs.validate("DayEntryInput",body);
        return store.authenticated(identity,()->store.withJournalSource(DayNoteHistory.Source.REST_ENTRY,()->{
            var labels=new DayLabels(store);var notes=new DayNotes(store);
            var label=labels.get(day);var note=notes.getContent(day);
            if(input.has("text")&&input.path("labelVersion").asLong()!=label.path("version").asLong()
                    ||input.has("body")&&input.path("noteVersion").asLong()!=note.path("version").asLong())throw new ApiFailure(412,"STALE_VERSION");
            if(input.has("text"))labels.save(day,etag(label),input.path("text").asText());
            if(input.has("body"))notes.saveContent(day,etag(note),input);
            return reply(day);
        }));
    }
}
