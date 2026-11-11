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
    @Inject ContractInputs inputs;
    @Inject AccountArchive archives;
    @Context UriInfo uri;
    @Context io.vertx.ext.web.RoutingContext routing;
    private VaultCollection vault(){return new VaultCollection(store,limits);}
    private static UUID uuid(String input){if(input==null)throw ApiFailure.validation();try{var value=UUID.fromString(input);if(!value.toString().equals(input))throw ApiFailure.validation();return value;}catch(IllegalArgumentException invalid){throw ApiFailure.validation();}}
    private Response tx(Supplier<Response> operation){return tx(true,operation);}
    private Response tx(boolean initialize,Supplier<Response> operation){return store.authenticated(identity,()->{
        if(initialize)vault().initialize();return Response.fromResponse(operation.get()).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").build();
    });}
    private static void fields(JsonNode input,String...allowed){
        if(input==null||!input.isObject())throw ApiFailure.validation();var fields=Set.of(allowed);
        input.fieldNames().forEachRemaining(field->{if(!fields.contains(field))throw ApiFailure.validation();});
        for(String field:allowed)if(!input.path(field).isTextual())throw ApiFailure.validation();
    }
    @GET public Response info(){ContractInputs.queries(uri.getQueryParameters());return tx(()->{
        var value=object().put("rootId",VaultIndex.ROOT.toString()).put("snapshot",vault().generation());value.set("settings",new VaultSettings(store).get());value.set("limits",limits.snapshot());return Response.ok(value).build();});}
    @GET @Path("/export") @Produces("application/zip") public Response export(){
        ContractInputs.queries(uri.getQueryParameters());
        // Finish lazy catalogue initialization before opening the independent
        // repeatable-read export snapshot; never nest its authorization locks.
        store.authenticated(identity,()->{vault().initialize();return null;});
        var file=archives.notesDownload(identity.principal());routing.addEndHandler(ignored->file.close());
        StreamingOutput body=output->{try(file){java.nio.file.Files.copy(file.file(),output);}};
        return Response.ok(body,"application/zip").header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").header("Content-Length",file.length())
                .header("Content-Disposition","attachment; filename=\"timekeeper-notes-"+java.time.LocalDate.now(java.time.ZoneOffset.UTC)+".zip\"").build();
    }
    @GET @Path("/revisions") public Response revisions(@QueryParam("sourceType")String type,@QueryParam("sourceId")String source){
        ContractInputs.queries(uri.getQueryParameters(),"sourceType","sourceId");UUID id=uuid(source);
        return tx(false,()->{var history=new NoteRevisions(store);return Response.ok(history.list(history.reference(type,id))).build();});
    }
    @GET @Path("/bookmark") public Response bookmark(@QueryParam("sourceType")String type,@QueryParam("sourceKey")String key){
        ContractInputs.queries(uri.getQueryParameters(),"sourceType","sourceKey");return tx(false,()->{var bookmarks=new NoteBookmarks(store);var ref=bookmarks.reference(type,key);return Response.ok(bookmarks.get(ref)).header("ETag",bookmarks.tag(ref)).build();});
    }
    @PUT @Path("/bookmark") @Consumes("application/json") public Response bookmark(@QueryParam("sourceType")String type,@QueryParam("sourceKey")String key,@HeaderParam("If-Match")String match,JsonNode input){
        ContractInputs.queries(uri.getQueryParameters(),"sourceType","sourceKey");var value=inputs.validate("NoteBookmarkInput",input);
        return tx(false,()->{var bookmarks=new NoteBookmarks(store);var ref=bookmarks.reference(type,key);return Response.ok(bookmarks.save(ref,match,value.path("bookmarked").asBoolean())).header("ETag",bookmarks.tag(ref)).build();});
    }
    @GET @Path("/revisions/{revisionId}") public Response revision(@PathParam("revisionId")String revision,@QueryParam("sourceType")String type,@QueryParam("sourceId")String source){
        ContractInputs.queries(uri.getQueryParameters(),"sourceType","sourceId");UUID id=uuid(source),version=uuid(revision);
        return tx(false,()->{var history=new NoteRevisions(store);var snapshot=history.snapshot(history.reference(type,id),version);return Response.ok(snapshot.body()).header("ETag",snapshot.etag()).build();});
    }
    @GET @Path("/client") public Response client(@QueryParam("sourceType")String type,@QueryParam("sourceKey")String key){
        ContractInputs.queries(uri.getQueryParameters(),"sourceType","sourceKey");return tx(false,()->{var clients=new NoteClients(store);var ref=clients.reference(type,key);return Response.ok(clients.get(ref)).header("ETag",clients.tag(ref)).build();});
    }
    @PUT @Path("/client") @Consumes("application/json") public Response client(@QueryParam("sourceType")String type,@QueryParam("sourceKey")String key,@HeaderParam("If-Match")String match,JsonNode input){
        ContractInputs.queries(uri.getQueryParameters(),"sourceType","sourceKey");var value=inputs.validate("NoteClientInput",input);UUID client=value.path("clientId").isNull()?null:ContractInputs.pathId(value.path("clientId").asText());
        return tx(false,()->{var clients=new NoteClients(store);var ref=clients.reference(type,key);return Response.ok(clients.save(ref,match,client)).header("ETag",clients.tag(ref)).build();});
    }
    @DELETE @Path("/revisions/{revisionId}") public Response deleteRevision(@PathParam("revisionId")String revision,@QueryParam("sourceType")String type,@QueryParam("sourceId")String source,@HeaderParam("If-Match")String match){
        ContractInputs.queries(uri.getQueryParameters(),"sourceType","sourceId");UUID id=uuid(source),version=uuid(revision);
        return tx(false,()->{var history=new NoteRevisions(store);history.delete(history.reference(type,id),version,match);return Response.noContent().build();});
    }
    @GET @Path("/settings") public Response settings(){ContractInputs.queries(uri.getQueryParameters());return tx(false,()->ApiReplies.resource(200,new VaultSettings(store).get(),null).response());}
    @GET @Path("/resolve") public Response resolve(@QueryParam("path")String path){ContractInputs.queries(uri.getQueryParameters(),"path");var target=new VaultPath(path);return tx(()->{var collection=vault();var value=collection.get(target);return Response.ok(collection.metadata(value)).header("ETag",collection.metadataTag(value)).build();});}
    @PUT @Path("/settings") @Consumes("application/json") public Response settings(@HeaderParam("If-Match")String match,JsonNode input){ContractInputs.queries(uri.getQueryParameters());return tx(false,()->{
        // Check the caller's representation before derived folder initialization
        // can migrate the default assets path, all under the same owner lock.
        ApiReplies.match(new VaultSettings(store).get(),match);var collection=vault();collection.initialize();
        return ApiReplies.resource(200,collection.configure(etag(new VaultSettings(store).get()),input),null).response();});}
    @GET @Path("/entries") public Response entries(@QueryParam("parentId")String parent,@QueryParam("query")String query,@QueryParam("trash")String trash,
            @QueryParam("offset")String offset,@QueryParam("limit")String limit,@HeaderParam("If-Match")String snapshot){
        ContractInputs.queries(uri.getQueryParameters(),"parentId","query","trash","offset","limit");
        if(trash!=null&&!Set.of("true","false").contains(trash))throw ApiFailure.validation();
        UUID directory=parent==null?VaultIndex.ROOT:uuid(parent);
        int start=ContractInputs.number(offset,0,0,Integer.MAX_VALUE),count=ContractInputs.number(limit,limits.pageSize(),1,limits.pageSize());
        return tx(()->Response.ok(vault().page(directory,query==null&&uri.getQueryParameters().containsKey("query")?"":query,"true".equals(trash),start,count,snapshot)).build());
    }
    @GET @Path("/recent") public Response recent(@QueryParam("offset")String offset,@QueryParam("limit")String limit,@HeaderParam("If-Match")String snapshot){
        ContractInputs.queries(uri.getQueryParameters(),"offset","limit");
        int start=ContractInputs.number(offset,0,0,Integer.MAX_VALUE),count=ContractInputs.number(limit,Math.min(20,limits.pageSize()),1,limits.pageSize());
        return tx(false,()->Response.ok(new RecentNotes(store,limits).page(start,count,snapshot)).build());
    }
    @GET @Path("/storage") public Response storage(){
        ContractInputs.queries(uri.getQueryParameters());return tx(false,()->Response.ok(new VaultStorage(store,limits).get()).header("Cache-Control","no-store").build());
    }
    @POST @Path("/folders") @Consumes("application/json") public Response mkdir(JsonNode input){
        ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("VaultFolderInput",input);var path=new VaultPath(value.path("path").asText());
        var dates=VaultImportDates.parse(value.has("createdAt")?value.path("createdAt").asText():null,value.has("modifiedAt")?value.path("modifiedAt").asText():null,value.has("createdAtSource")?value.path("createdAtSource").asText():null);
        return tx(()->{var collection=vault();var entry=dates.apply(store,collection.mkdir(path));return Response.status(201).entity(collection.metadata(entry)).header("ETag",collection.metadataTag(entry)).build();});
    }
    @PUT @Path("/files") @Consumes("application/octet-stream") public Response create(@QueryParam("path")String name,@HeaderParam("If-None-Match")String none,
            @QueryParam("createdAt")String created,@QueryParam("modifiedAt")String modified,@QueryParam("createdAtSource")String createdSource,@QueryParam("taskPolicy")String tasks,byte[] bytes){
        ContractInputs.queries(uri.getQueryParameters(),"path","createdAt","modifiedAt","createdAtSource","taskPolicy");if(!"*".equals(none))throw new ApiFailure(428,"PRECONDITION_REQUIRED");var path=new VaultPath(name);var dates=VaultImportDates.parse(created,modified,createdSource);var policy=NoteTaskImport.parse(tasks);
        return tx(()->{var collection=vault();var entry=policy.write(store,dates,()->collection.put(path,null,true,bytes));return Response.status(201).entity(collection.metadata(entry)).header("ETag",collection.metadataTag(entry)).build();});
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
    @PUT @Path("/entries/{id}/import") @Consumes("application/octet-stream") public Response importFile(@PathParam("id")String id,@HeaderParam("If-Match")String match,
            @QueryParam("createdAt")String created,@QueryParam("modifiedAt")String modified,@QueryParam("createdAtSource")String source,@QueryParam("taskPolicy")String tasks,byte[] bytes){
        ContractInputs.queries(uri.getQueryParameters(),"createdAt","modifiedAt","createdAtSource","taskPolicy");UUID entry=uuid(id);var dates=VaultImportDates.parse(created,modified,source);var policy=NoteTaskImport.parse(tasks);
        return tx(()->{var collection=vault();var current=collection.get(entry);VaultCollection.match(collection.metadataTag(current),match);
            if(current.path("deleted").asBoolean()||VaultCollection.folder(current))throw new ApiFailure(412,"STALE_VERSION");
            var saved=policy.write(store,dates,()->collection.put(VaultCollection.path(current),collection.tag(current),false,bytes));
            if(!saved.equals(current)){new VaultState(store,limits).changed(0,0,false);store.change("PROFILE","UPDATED",store.owner());}
            return Response.ok(collection.metadata(saved)).header("ETag",collection.metadataTag(saved)).build();});
    }
    @PUT @Path("/entries/{id}/title") @Consumes("application/json") public Response title(@PathParam("id")String id,@HeaderParam("If-Match")String match,JsonNode input){
        ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("VaultTitleInput",input);UUID entry=uuid(id);
        return tx(()->{var collection=vault();var result=collection.renameTitle(collection.get(entry),value.path("title").asText(),match);return Response.ok(collection.metadata(result)).header("ETag",collection.metadataTag(result)).build();});
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
