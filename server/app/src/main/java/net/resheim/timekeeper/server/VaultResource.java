package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** Cookie/CSRF and bearer API for the Notes interface; DAV credentials stay out of it. */
@Path("/api/v1/notes") @Blocking @Produces("application/json")
public class VaultResource {
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject RequestIdentity identity;
    @Context UriInfo uri;
    private VaultCollection vault(){return new VaultCollection(store,limits);}
    private static UUID uuid(String input){try{var value=UUID.fromString(input);if(!value.toString().equals(input))throw ApiFailure.validation();return value;}catch(IllegalArgumentException invalid){throw ApiFailure.validation();}}
    private Response tx(Supplier<Response> operation){return store.authenticated(identity,()->{
        vault().initialize();return Response.fromResponse(operation.get()).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").build();
    });}
    private static void fields(JsonNode input,String...allowed){
        if(input==null||!input.isObject())throw ApiFailure.validation();var fields=Set.of(allowed);
        input.fieldNames().forEachRemaining(field->{if(!fields.contains(field))throw ApiFailure.validation();});
        for(String field:allowed)if(!input.path(field).isTextual())throw ApiFailure.validation();
    }
    @GET public Response info(){ContractInputs.queries(uri.getQueryParameters());return tx(()->{
        var value=object().put("rootId",VaultIndex.ROOT.toString()).put("snapshot",vault().generation());value.set("settings",new VaultSettings(store).get());value.set("limits",limits.snapshot());return Response.ok(value).build();});}
    @GET @Path("/settings") public Response settings(){ContractInputs.queries(uri.getQueryParameters());return tx(()->ApiReplies.resource(200,new VaultSettings(store).get(),null).response());}
    @GET @Path("/resolve") public Response resolve(@QueryParam("path")String path){ContractInputs.queries(uri.getQueryParameters(),"path");var target=new VaultPath(path);return tx(()->{var collection=vault();var value=collection.get(target);return Response.ok(collection.metadata(value)).header("ETag",collection.metadataTag(value)).build();});}
    @PUT @Path("/settings") @Consumes("application/json") public Response settings(@HeaderParam("If-Match")String match,JsonNode input){ContractInputs.queries(uri.getQueryParameters());return tx(()->ApiReplies.resource(200,vault().configure(match,input),null).response());}
    @GET @Path("/entries") public Response entries(@QueryParam("parentId")String parent,@QueryParam("query")String query,@QueryParam("trash")String trash,
            @QueryParam("offset")String offset,@QueryParam("limit")String limit,@HeaderParam("If-Match")String snapshot){
        ContractInputs.queries(uri.getQueryParameters(),"parentId","query","trash","offset","limit");
        if(trash!=null&&!Set.of("true","false").contains(trash))throw ApiFailure.validation();
        UUID directory=parent==null?VaultIndex.ROOT:uuid(parent);
        int start=ContractInputs.number(offset,0,0,Integer.MAX_VALUE),count=ContractInputs.number(limit,limits.pageSize(),1,limits.pageSize());
        return tx(()->Response.ok(vault().page(directory,query,"true".equals(trash),start,count,snapshot)).build());
    }
    @POST @Path("/folders") @Consumes("application/json") public Response mkdir(JsonNode input){
        ContractInputs.queries(uri.getQueryParameters());fields(input,"path");var path=new VaultPath(input.path("path").asText());
        return tx(()->{var collection=vault();var entry=collection.mkdir(path);return Response.status(201).entity(collection.metadata(entry)).header("ETag",collection.metadataTag(entry)).build();});
    }
    @PUT @Path("/files") @Consumes("application/octet-stream") public Response create(@QueryParam("path")String name,@HeaderParam("If-None-Match")String none,byte[] bytes){
        ContractInputs.queries(uri.getQueryParameters(),"path");if(!"*".equals(none))throw new ApiFailure(428,"PRECONDITION_REQUIRED");var path=new VaultPath(name);
        return tx(()->{var collection=vault();var entry=collection.put(path,null,true,bytes);return Response.status(201).entity(collection.metadata(entry)).header("ETag",collection.metadataTag(entry)).build();});
    }
    @GET @Path("/entries/{id}") public Response entry(@PathParam("id")String id){ContractInputs.queries(uri.getQueryParameters());UUID entry=uuid(id);return tx(()->{var collection=vault();var value=collection.get(entry);return Response.ok(collection.metadata(value)).header("ETag",collection.metadataTag(value)).build();});}
    @GET @Path("/entries/{id}/content") @Produces("application/octet-stream") public Response content(@PathParam("id")String id){
        ContractInputs.queries(uri.getQueryParameters());UUID entry=uuid(id);return tx(()->{var collection=vault();var value=collection.get(entry);
            return Response.ok(collection.read(value)).header("ETag",collection.tag(value)).header("Content-Disposition","attachment").build();});
    }
    @PUT @Path("/entries/{id}/content") @Consumes("application/octet-stream") public Response save(@PathParam("id")String id,@HeaderParam("If-Match")String match,byte[] bytes){
        ContractInputs.queries(uri.getQueryParameters());UUID entry=uuid(id);return tx(()->{var collection=vault();var current=collection.get(entry);
            if(current.path("deleted").asBoolean())throw new ApiFailure(412,"STALE_VERSION");VaultCollection.match(collection.tag(current),match);
            var value=collection.put(VaultCollection.path(current),match,false,bytes);return Response.ok(collection.metadata(value)).header("ETag",collection.tag(value)).build();});
    }
    @PUT @Path("/entries/{id}/path") @Consumes("application/json") public Response move(@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode input){
        ContractInputs.queries(uri.getQueryParameters());fields(input,"path");UUID entry=uuid(id);var target=new VaultPath(input.path("path").asText());
        return tx(()->{var collection=vault();var current=collection.get(entry);VaultCollection.match(collection.metadataTag(current),match);var value=collection.move(current,target,collection.tag(current));return Response.ok(collection.metadata(value)).header("ETag",collection.metadataTag(value)).build();});
    }
    @DELETE @Path("/entries/{id}") public Response delete(@PathParam("id")String id,@HeaderParam("If-Match")String match){
        ContractInputs.queries(uri.getQueryParameters());UUID entry=uuid(id);return tx(()->{var collection=vault();var current=collection.get(entry);VaultCollection.match(collection.metadataTag(current),match);collection.delete(current,collection.tag(current));return Response.noContent().build();});
    }
    @POST @Path("/entries/{id}/restore") public Response restore(@PathParam("id")String id,@HeaderParam("If-Match")String match){
        ContractInputs.queries(uri.getQueryParameters());UUID entry=uuid(id);return tx(()->{var collection=vault();var current=collection.get(entry);VaultCollection.match(collection.metadataTag(current),match);var value=collection.restore(current,collection.tag(current));return Response.ok(collection.metadata(value)).header("ETag",collection.metadataTag(value)).build();});
    }
}
