package net.resheim.timekeeper.server;

import java.time.LocalDate;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** A proposal becomes permanent only when the client saves its complete note presentation. */
@Path("/api/v1/daily-banners") @Blocking @Produces(MediaType.APPLICATION_JSON)
public class DailyBannerResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject BackendClock clock;
    @Inject DailyBannerForecast weather;
    @Inject WeatherLocations locations;
    @Context UriInfo uri;
    @GET @Path("/locations") public Response locations(@QueryParam("q")String query,@QueryParam("language")@DefaultValue("en")String language){
        ContractInputs.queries(uri.getQueryParameters(),"q","language");
        store.authenticated(identity,()->true);
        return Response.ok(locations.search(query,language,clock.now())).header("Cache-Control","private, no-store").build();
    }
    @GET @Path("/{date}/preview") public Response preview(@PathParam("date")String value){
        ContractInputs.queries(uri.getQueryParameters());final LocalDate date;
        try{date=LocalDate.parse(value);}catch(RuntimeException invalid){throw ApiFailure.badRequest();}
        var profile=store.authenticated(identity,()->store.profile());
        // Network I/O is outside the owner transaction.
        return Response.ok(weather.preview(profile,date,clock.now())).header("Cache-Control","private, no-store").build();
    }
}
