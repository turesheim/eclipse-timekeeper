package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.util.UUID;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/journal") @Blocking @Consumes("application/json") @Produces("application/json")
public class JournalResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject JournalConfiguration configuration;
    @Inject JournalSynchronization synchronization;
    @Inject JournalAccountSecurity security;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;
    @Context io.vertx.core.http.HttpServerRequest request;
    private boolean secure(){return uri.getRequestUri().getScheme().equals("https")||java.util.Set.of("127.0.0.1","::1").contains(request.connection().remoteAddress().hostAddress());}
    private void requireSecure(){if(!secure())throw new ApiFailure(403,"JOURNAL_HTTPS_REQUIRED");}
    private Response tx(Supplier<Response> operation){
        ContractInputs.queries(uri.getQueryParameters());
        try{return store.authenticated(identity,()->Response.fromResponse(operation.get()).header("Cache-Control","private, no-store").build());}
        catch(JournalConfiguration.Unavailable unavailable){throw new ApiFailure(503,unavailable.code);}
    }
    private Response ok(JsonNode value){return Response.ok(value).build();}
    private void proof(JsonNode input){requireSecure();security.prove(identity.principal(),input.path("currentPassword").asText());}
    @GET public Response status(){return tx(()->{
        var value=new JournalAccount(store,configuration).status(identity.principal());
        if(!secure()){value.put("canManage",false);value.set("deployment",object().put("ready",false).put("code","JOURNAL_HTTPS_REQUIRED"));}return ok(value);
    });}
    @PUT @Path("/settings") public Response settings(@HeaderParam("If-Match")String match,JsonNode body){
        var input=inputs.validate("JournalSettingsInput",body);proof(input);
        return tx(()->{
            var devices=new JournalDevices(store);ApiReplies.match(devices.settings(),match);
            if(input.path("enabled").asBoolean()&&configuration.load().owner(store.owner()).isEmpty())throw new ApiFailure(503,"JOURNAL_ADMIN_ACCOUNT_NOT_PROVISIONED");
            devices.configure(input.path("enabled").asBoolean(),input.has("bidirectional")?input.path("bidirectional").asBoolean():devices.settings().path("bidirectional").asBoolean());return ok(new JournalAccount(store,configuration).status(identity.principal()));
        });
    }
    @POST @Path("/devices") public Response enroll(@HeaderParam("Idempotency-Key")String key,JsonNode body){
        var input=inputs.validate("JournalDeviceInput",body);ContractInputs.pathId(key);proof(input);
        var safe=((com.fasterxml.jackson.databind.node.ObjectNode)input).deepCopy();safe.remove("currentPassword");
        return tx(()->receipts.execute(key,"POST","/journal/devices",null,safe,()->{
            var deployment=configuration.load();if(deployment.owner(store.owner()).isEmpty())throw new ApiFailure(503,"JOURNAL_ADMIN_ACCOUNT_NOT_PROVISIONED");
            UUID rotate=input.has("rotateDeviceId")?ContractInputs.pathId(input.path("rotateDeviceId").asText()):null;
            return ApiReplies.resource(201,new JournalDevices(store).enroll(input.path("label").asText(),input.path("publicKey").asText(),rotate,input.path("bidirectional").asBoolean()),null);
        }).response());
    }
    @POST @Path("/devices/{id}/revoke") public Response revoke(@PathParam("id")String id,JsonNode body){
        UUID device=ContractInputs.pathId(id);var input=inputs.validate("JournalProofInput",body);proof(input);return tx(()->ok(new JournalDevices(store).revoke(device)));
    }
    @GET @Path("/devices/{id}/profile") public Response profile(@PathParam("id")String id){
        requireSecure();UUID device=ContractInputs.pathId(id);return tx(()->Response.ok(new JournalAccount(store,configuration).profile(device)).header("Content-Disposition","attachment; filename=\"timekeeper-journal-"+device+".json\"").build());
    }
    @POST @Path("/rescan") @Consumes("*/*") public Response rescan(){
        // Recheck the browser's identity before background-owner operations, and again for the reply.
        tx(()->Response.noContent().build());
        try{synchronization.rescan(identity.owner());}catch(JournalConfiguration.Unavailable unavailable){throw new ApiFailure(503,unavailable.code);}
        return status();
    }
    @GET @Path("/items/{id}") public Response candidate(@PathParam("id")String id){var key=ContractInputs.pathId(id);return tx(()->ok(new JournalState(store).find(JOURNAL_OFFER,key).isPresent()?new JournalReconciliation(store).candidate(key):new JournalImport(store).candidate(key)));}
    @GET @Path("/items/{id}/source") @Produces("application/octet-stream") public Response source(@PathParam("id")String id){
        var key=ContractInputs.pathId(id);return tx(()->{
            if(new JournalState(store).find(JOURNAL_OFFER,key).isPresent())return Response.ok(new JournalReconciliation(store).source(key,false),"application/octet-stream").header("Content-Disposition","attachment; filename=\""+new JournalState(store).required(JOURNAL_OFFER,key).path("filename").asText()+"\"").build();
            var item=new JournalState(store).required(JOURNAL_ITEM,key);
            return Response.ok(new JournalImport(store).download(key),"application/octet-stream").header("Content-Disposition","attachment; filename=\""+item.path("filename").asText()+"\"").build();
        });
    }
    @GET @Path("/items/{id}/calendar-source") @Produces("application/octet-stream") public Response calendarSource(@PathParam("id")String id){
        var key=ContractInputs.pathId(id);return tx(()->Response.ok(new JournalReconciliation(store).source(key,true),"application/octet-stream").header("Content-Disposition","attachment; filename=\""+new JournalState(store).required(JOURNAL_OFFER,key).path("filename").asText()+"\"").build());
    }
    @POST @Path("/items/{id}/resolve") public Response resolve(@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode body){
        var key=ContractInputs.pathId(id);var input=inputs.validate("JournalResolutionInput",body);
        return tx(()->{
            var offered=new JournalState(store).find(JOURNAL_OFFER,key);if(offered.isPresent()){var reconciliation=new JournalReconciliation(store);reconciliation.resolve(key,match,input.path("currentEtag").asText(),input.path("action").asText().equals("INCOMING"),uuid(offered.get().get("deviceId")),configuration.load().epoch());return ok(reconciliation.management(new JournalState(store).required(JOURNAL_OFFER,key)));}
            synchronization.authorizeCandidate(key);return ok(new JournalImport(store).resolve(key,match,input.path("currentEtag").asText(),input.path("action").asText().equals("INCOMING")));});
    }
    @POST @Path("/items/{id}/discard") @Consumes("*/*") public Response discard(@PathParam("id")String id,@HeaderParam("If-Match")String match){
        var key=ContractInputs.pathId(id);return tx(()->ok(new JournalState(store).find(JOURNAL_OFFER,key).isPresent()?new JournalReconciliation(store).discard(key,match):new JournalImport(store).discard(key,match)));
    }
}
