package net.resheim.timekeeper.server;

import java.util.UUID;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1") @Blocking @Consumes("application/json") @Produces("application/json")
public class CalendarResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Inject CalendarRefresh refresh;
    @Inject Pages pages;
    @Context UriInfo uri;
    private Response tx(Supplier<ApiReplies> operation){return store.authenticated(identity,()->operation.get().response());}
    private CalendarSubscriptions calendars(){return new CalendarSubscriptions(store);}
    private void noQueries(){ContractInputs.queries(uri.getQueryParameters());}
    @GET @Path("/calendar-subscriptions") public Response list(){noQueries();return tx(()->new ApiReplies(200,calendars().list(),null,null));}
    @GET @Path("/calendar-subscriptions/{id}") public Response get(@PathParam("id")String id){
        UUID key=ContractInputs.pathId(id);noQueries();return tx(()->ApiReplies.resource(200,calendars().visible(calendars().required(key)),null));
    }
    @POST @Path("/calendar-subscriptions") public Response create(@HeaderParam("Idempotency-Key")String key,JsonNode body){
        var input=inputs.validate("CalendarSubscriptionCreate",body);noQueries();
        var response=tx(()->receipts.execute(key,"POST","/calendar-subscriptions",null,input,()->{
            var value=calendars().create(input,refresh.policy());return ApiReplies.resource(201,calendars().visible(value),"/api/v1/calendar-subscriptions/"+value.path("id").asText());
        }));refresh.wake();return response;
    }
    @PUT @Path("/calendar-subscriptions/{id}") public Response edit(@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        UUID key=ContractInputs.pathId(id);var input=inputs.validate("CalendarSubscriptionUpdate",body);noQueries();
        var response=tx(()->ApiReplies.resource(200,calendars().visible(calendars().edit(key,match,input,refresh.policy())),null));refresh.wake();return response;
    }
    @DELETE @Path("/calendar-subscriptions/{id}") public Response remove(@PathParam("id")String id,@HeaderParam("If-Match")String match){
        UUID key=ContractInputs.pathId(id);noQueries();return tx(()->{if(calendars().required(key).hasNonNull("managedConnectionId"))new InternetCalendars(store).removeSubscription(key,match);else calendars().remove(key,match);return new ApiReplies(204,null,null,null);});
    }
    @POST @Path("/calendar-subscriptions/{id}/refresh") @Consumes("*/*") public Response refresh(@PathParam("id")String id){
        UUID key=ContractInputs.pathId(id);noQueries();return refresh.refresh(identity,key).response();
    }
    @GET @Path("/calendar-events") public Response events(@QueryParam("fromDate")String from,@QueryParam("toDate")String to){
        ContractInputs.queries(uri.getQueryParameters(),"fromDate","toDate");return tx(()->new ApiReplies(200,calendars().events(from,to),null,null));
    }
    @GET @Path("/calendar-tasks") public Response tasks(@QueryParam("limit")String limit,@QueryParam("cursor")String cursor,@HeaderParam("If-Match")String match){
        ContractInputs.queries(uri.getQueryParameters(),"limit","cursor");int size=ContractInputs.number(limit,100,1,500);
        return tx(()->{
            var catalog=calendars().tasks();String snapshot=catalog.path("snapshot").asText();
            if(cursor!=null&&match==null)throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            if(match!=null&&!match.equals(snapshot))throw new ApiFailure(412,"STALE_VERSION");
            var values=new java.util.ArrayList<JsonNode>();catalog.path("items").forEach(values::add);values.sort(java.util.Comparator.comparing(value->value.path("id").asText()));
            var page=pages.page("CALENDAR_TASKS",values,JsonValues.object().put("snapshot",snapshot),size,cursor);page.set("errors",catalog.path("errors"));page.put("snapshot",snapshot).set("zoneId",catalog.path("zoneId"));
            return new ApiReplies(200,page,snapshot,null);
        });
    }
    @POST @Path("/calendar-subscriptions/{id}/events/{key}/activity") public Response convert(@PathParam("id")String id,@PathParam("key")String event,@HeaderParam("Idempotency-Key")String key,JsonNode body){
        UUID subscription=ContractInputs.pathId(id);var input=inputs.validate("CalendarEventConversion",body);noQueries();
        return tx(()->receipts.execute(key,"POST","/calendar-subscriptions/"+subscription+"/events/"+event+"/activity",null,input,()->{
            var result=calendars().convert(subscription,event,input);return new ApiReplies(201,result,null,"/api/v1/activities/"+result.path("activityIds").get(0).asText());
        }));
    }
}
