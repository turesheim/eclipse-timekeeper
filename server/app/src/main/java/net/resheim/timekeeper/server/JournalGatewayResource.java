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
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    @Context io.vertx.core.http.HttpServerRequest request;
    private Response call(String username,Function<JournalSynchronization.Gateway,ObjectNode> operation){
        ContractInputs.queries(uri.getQueryParameters());
        if(headers.getHeaderString("Cookie")!=null||headers.getHeaderString("Origin")!=null)throw ApiFailure.badRequest();
        String peer=request.connection().remoteAddress().hostAddress();
        if(!uri.getRequestUri().getScheme().equals("https")&&!Set.of("127.0.0.1","::1").contains(peer))throw new ApiFailure(403,"JOURNAL_ENCRYPTED_BROKER_REQUIRED");
        var authorization=headers.getRequestHeader("Authorization");if(authorization==null||authorization.size()!=1)throw ApiFailure.unauthorized(false);
        var result=synchronization.gateway(username,authorization.getFirst(),operation);
        return Response.ok(result).header("Cache-Control","private, no-store").build();
    }
    static void fields(JsonNode value,String... allowed){
        if(value==null||!value.isObject())throw ApiFailure.badRequest();var names=Set.of(allowed);
        value.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw ApiFailure.badRequest();});
    }
    static String text(JsonNode value,String field,int limit){var property=value.path(field);if(!property.isTextual()||property.asText().length()>limit)throw ApiFailure.validation();return property.asText();}
    @POST @Path("/keys") public Response key(@PathParam("username")String username,JsonNode input){
        fields(input,"publicKey");String key=text(input,"publicKey",4096);return call(username,gateway->synchronization.key(gateway,key));
    }
    @POST @Path("/verify") public Response verify(@PathParam("username")String username,JsonNode input){
        fields(input,"deviceId");var device=ContractInputs.pathId(text(input,"deviceId",36));return call(username,gateway->synchronization.verify(gateway,device));
    }
    @POST @Path("/transfers") public Response begin(@PathParam("username")String username,JsonNode input){
        fields(input,"deviceId");var device=ContractInputs.pathId(text(input,"deviceId",36));return call(username,gateway->synchronization.begin(gateway,device));
    }
    @POST @Path("/transfers/{id}/heartbeat") public Response heartbeat(@PathParam("username")String username,@PathParam("id")String id,JsonNode input){
        fields(input);var transfer=ContractInputs.pathId(id);return call(username,gateway->synchronization.heartbeat(gateway,transfer));
    }
    @PUT @Path("/transfers/{id}/files/{filename}") public Response upload(@PathParam("username")String username,@PathParam("id")String id,@PathParam("filename")String filename,JsonNode input){
        fields(input,"data");String data=text(input,"data",4*((JournalMarkdown.FILE_BYTES+2)/3));var transfer=ContractInputs.pathId(id);return call(username,gateway->synchronization.upload(gateway,transfer,filename,data));
    }
    @POST @Path("/transfers/{id}/complete") public Response complete(@PathParam("username")String username,@PathParam("id")String id,JsonNode input){
        fields(input,"format","completed","files");if(!input.path("files").isArray()||input.path("files").size()>JournalImport.BATCH_FILES)throw ApiFailure.validation();
        for(var file:input.path("files"))fields(file,"name","bytes","sha256");var transfer=ContractInputs.pathId(id);
        return call(username,gateway->synchronization.complete(gateway,transfer,input));
    }
}
