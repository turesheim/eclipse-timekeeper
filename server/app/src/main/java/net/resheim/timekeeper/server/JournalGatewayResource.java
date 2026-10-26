package net.resheim.timekeeper.server;

import java.util.*;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** Private transport protocol, deliberately outside the user API/bearer-token namespace. */
@Path("/internal/journal/{username}") @Blocking @Produces("application/json") @Consumes("application/json")
public class JournalGatewayResource {
    @Inject JournalSynchronization synchronization;
    @Inject ServerStore store;
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    @Context io.vertx.core.http.HttpServerRequest request;
    private String authorization(){
        if(!uri.getQueryParameters().isEmpty())throw new ApiFailure(400,"JOURNAL_QUERY_DENIED");
        if(headers.getHeaderString("Cookie")!=null||headers.getHeaderString("Origin")!=null)throw new ApiFailure(400,"JOURNAL_BROWSER_CREDENTIALS_DENIED");
        String peer=request.connection().remoteAddress().hostAddress();
        if(!uri.getRequestUri().getScheme().equals("https")&&!Set.of("127.0.0.1","::1").contains(peer))throw new ApiFailure(403,"JOURNAL_ENCRYPTED_BROKER_REQUIRED");
        var authorization=headers.getRequestHeader("Authorization");if(authorization==null||authorization.size()!=1)throw ApiFailure.unauthorized(false);
        return authorization.getFirst();
    }
    private Response call(String username,Function<JournalSynchronization.Gateway,ObjectNode> operation){
        var result=synchronization.gateway(username,authorization(),operation);
        return Response.ok(result).header("Cache-Control","private, no-store").build();
    }
    static void fields(JsonNode value,String... allowed){
        if(value==null||!value.isObject())throw new ApiFailure(400,"JOURNAL_OBJECT_REQUIRED");var names=Set.of(allowed);
        value.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw new ApiFailure(400,"JOURNAL_UNKNOWN_FIELD");});
    }
    static String text(JsonNode value,String field,int limit){var property=value.path(field);if(!property.isTextual()||property.asText().length()>limit)throw ApiFailure.validation();return property.asText();}
    @POST @Path("/keys") public Response key(@PathParam("username")String username,JsonNode input){
        fields(input,"publicKey");String key=text(input,"publicKey",4096);return call(username,gateway->synchronization.key(gateway,key));
    }
    @POST @Path("/verify") public Response verify(@PathParam("username")String username,JsonNode input){
        fields(input,"deviceId");var device=ContractInputs.pathId(text(input,"deviceId",36));return call(username,gateway->synchronization.verify(gateway,device));
    }
    @POST @Path("/transfers") public Response begin(@PathParam("username")String username,JsonNode input){
        fields(input,"deviceId","exchangeId");var device=ContractInputs.pathId(text(input,"deviceId",36));var exchange=input.has("exchangeId")?ContractInputs.pathId(text(input,"exchangeId",36)):null;return call(username,gateway->synchronization.begin(gateway,device,exchange));
    }
    @POST @Path("/transfers/{id}/heartbeat") public Response heartbeat(@PathParam("username")String username,@PathParam("id")String id,JsonNode input){
        fields(input);var transfer=ContractInputs.pathId(id);return call(username,gateway->synchronization.heartbeat(gateway,transfer));
    }
    @POST @Path("/transfers/{id}/abort") public Response abort(@PathParam("username")String username,@PathParam("id")String id,JsonNode input){
        fields(input);var transfer=ContractInputs.pathId(id);return call(username,gateway->synchronization.abort(gateway,transfer));
    }
    @PUT @Path("/transfers/{id}/files/{filename}") public Response upload(@PathParam("username")String username,@PathParam("id")String id,@PathParam("filename")String filename,JsonNode input){
        fields(input,"data");String data=text(input,"data",4*((JournalMarkdown.FILE_BYTES+2)/3));var transfer=ContractInputs.pathId(id);return call(username,gateway->synchronization.upload(gateway,transfer,filename,data));
    }
    @POST @Path("/transfers/{id}/complete") public Response complete(@PathParam("username")String username,@PathParam("id")String id,JsonNode input){
        fields(input,"format","completed","files","errors","absent","clientStateId","exchangeId");if(!input.path("files").isArray()||input.path("files").size()>JournalImport.BATCH_FILES)throw ApiFailure.validation();
        if(input.has("errors")){if(!input.path("errors").isArray()||input.path("errors").size()>JournalImport.BATCH_FILES)throw ApiFailure.validation();for(var error:input.path("errors"))fields(error,"name","code");}
        for(var file:input.path("files"))fields(file,"name","bytes","sha256","baseToken");var transfer=ContractInputs.pathId(id);
        return call(username,gateway->synchronization.complete(gateway,transfer,input));
    }
    @POST @Path("/transfers/{id}/settle") public Response settle(@PathParam("username")String username,@PathParam("id")String id,JsonNode input){
        fields(input);return Response.ok(synchronization.settle(username,authorization(),ContractInputs.pathId(id))).header("Cache-Control","private, no-store").build();
    }
    @POST @Path("/exchanges/inventory") public Response inventory(@PathParam("username")String username,JsonNode input){
        fields(input,"deviceId","after");var device=ContractInputs.pathId(text(input,"deviceId",36));String after=input.has("after")?text(input,"after",13):null;
        return call(username,gateway->new JournalExchanges(store).inventory(device,after));
    }
    @POST @Path("/exchanges/{exchange}/results") public Response results(@PathParam("username")String username,@PathParam("exchange")String exchange,JsonNode input){
        fields(input,"deviceId");var device=ContractInputs.pathId(text(input,"deviceId",36));var id=ContractInputs.pathId(exchange);
        return call(username,gateway->new JournalExchanges(store).results(device,id,gateway.deployment().epoch()));
    }
    @POST @Path("/exchanges/{exchange}/files/{offer}") @Produces("application/octet-stream") public Response outgoing(@PathParam("username")String username,@PathParam("exchange")String exchange,@PathParam("offer")String offer,JsonNode input){
        fields(input,"deviceId");var device=ContractInputs.pathId(text(input,"deviceId",36));var id=ContractInputs.pathId(exchange);var candidate=ContractInputs.pathId(offer);
        byte[] data=synchronization.gateway(username,authorization(),gateway->new JournalExchanges(store).outbound(device,id,candidate,gateway.deployment().epoch()));
        return Response.ok(data,"application/octet-stream").header("Cache-Control","private, no-store").build();
    }
    @POST @Path("/offers/{offer}/acknowledge") public Response acknowledge(@PathParam("username")String username,@PathParam("offer")String offer,JsonNode input){
        fields(input,"deviceId","token","exists","sha256");var device=ContractInputs.pathId(text(input,"deviceId",36));var id=ContractInputs.pathId(offer);String token=text(input,"token",36),hash=text(input,"sha256",64);
        if(!input.path("exists").isBoolean())throw ApiFailure.validation();
        return call(username,gateway->{var value=new JournalReconciliation(store).acknowledge(id,device,token,input.path("exists").asBoolean(),hash,gateway.deployment().epoch());
            var state=new JournalState(store);state.update(JournalState.Kind.JOURNAL_DEVICE,state.required(JournalState.Kind.JOURNAL_DEVICE,device).put("lastSyncAt",store.now().toString()));return value;});
    }
}
