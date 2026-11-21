package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/data/account") @Blocking @Consumes("application/json") @Produces("application/json")
public class AccountArchiveResource {
    @Inject ServerStore store;
    @Inject AccountArchive archives;
    @Inject PreparedAccountArchive prepared;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;
    @Context RoutingContext routing;
    private void noQueries() {ContractInputs.queries(uri.getQueryParameters());}
    private Response tx(Supplier<ApiReplies> work) {return store.authenticated(identity,()->work.get().response());}
    private AccountUploads uploads() {return new AccountUploads(store);}
    private AccountArchiveImport importer() {return new AccountArchiveImport(store,inputs,receipts);}

    @GET @Path("/export") @Produces("application/zip") public Response download() {
        noQueries();
        // This operation owns one repeatable-read snapshot itself. Nesting it
        // inside a business transaction would wait on its authorization locks.
        return exportResponse(routing,()->archives.download(identity.principal()),"timekeeper-account")
                .header("Cache-Control","no-store").build();
    }
    static Response.ResponseBuilder exportResponse(RoutingContext routing,Supplier<AccountArchive.Download> prepare,String prefix) {
        // A disconnect can end the exchange while the ZIP is still being built.
        var ended=new CompletableFuture<Void>();
        routing.addEndHandler(ignored->ended.complete(null));
        var file=prepare.get();
        ended.thenRun(file::close);
        if(ended.isDone()||routing.response().closed()) {
            file.close();return Response.noContent();
        }
        StreamingOutput body=output->{try(file) {Files.copy(file.file(),output);}};
        return Response.ok(body,"application/zip").header("Content-Length",file.length())
                .header("Content-Disposition","attachment; filename=\""+prefix+"-"+LocalDate.now(ZoneOffset.UTC)+".zip\"");
    }
    @GET @Path("/prepared-archive") public Response preparedArchive() {
        noQueries();String id=prepared.current(identity);
        return (id==null ? Response.noContent() : Response.ok(object().put("id",id)))
                .header("Cache-Control","no-store").build();
    }
    @POST @Path("/prepared-archive") @Consumes("*/*") public Response prepareArchive() {
        noQueries();String id=prepared.prepare(identity);
        return Response.ok(object().put("id",id)).header("Cache-Control","no-store").build();
    }
    @DELETE @Path("/prepared-archive/{id}") public Response deletePreparedArchive(@PathParam("id") String id) {
        noQueries();prepared.delete(identity,ContractInputs.pathId(id).toString());
        return Response.noContent().build();
    }
    @GET @Path("/prepared-archive/{id}") @Produces("application/zip") public Response downloadPreparedArchive(@PathParam("id") String id) {
        noQueries();String key=ContractInputs.pathId(id).toString();
        var ended=new CompletableFuture<Void>();
        routing.addEndHandler(ignored->ended.complete(null));
        var file=prepared.open(identity,key,download->ended.thenRun(download::close));
        if(ended.isDone()||routing.response().closed()) {
            file.close();return Response.noContent().build();
        }
        StreamingOutput body=output->{try(file){file.input().transferTo(output);}};
        return Response.ok(body,"application/zip").header("Content-Length",file.length())
                .header("Content-Disposition","attachment; filename=\"timekeeper-account-"+file.date()+".zip\"")
                .header("Cache-Control","no-store").build();
    }
    @POST @Path("/uploads") public Response create(@HeaderParam("Idempotency-Key") String key,JsonNode body) {
        noQueries();var value=inputs.validate("AccountArchiveUploadInput",body);
        return tx(()->receipts.execute(key,"POST","/data/account/uploads",null,value,()->{
            var upload=uploads().create(value.path("fileName").asText(),value.path("mediaType").asText(),value.path("bytes").asLong(),value.path("sha256").asText());
            return ApiReplies.resource(201,upload,"/api/v1/data/account/uploads/"+upload.path("id").asText());
        }));
    }
    @GET @Path("/uploads") public Response pendingUploads() {
        noQueries();return tx(()->{
            var value=object();var items=value.putArray("items");
            for(var upload:store.list(AccountUploads.UPLOAD)) if(!upload.path("state").asText().equals("IMPORTED")
                    && java.time.Instant.parse(upload.path("expiresAt").asText()).isAfter(store.now())) items.add(upload);
            return new ApiReplies(200,value,null,null);
        });
    }
    @GET @Path("/uploads/{id}") public Response upload(@PathParam("id") String id) {
        noQueries();UUID key=ContractInputs.pathId(id);return tx(()->ApiReplies.resource(200,uploads().get(key),null));
    }
    @PUT @Path("/uploads/{id}/chunks/{index}") public Response chunk(@PathParam("id") String id,@PathParam("index") String index,JsonNode body) {
        noQueries();UUID key=ContractInputs.pathId(id);int number=ContractInputs.number(index,-1,0,AccountUploads.MAX_CHUNK_INDEX);var value=inputs.validate("AccountArchiveChunkInput",body);
        return tx(()->ApiReplies.resource(200,uploads().chunk(key,number,value.path("data").asText()),null));
    }
    @DELETE @Path("/uploads/{id}") @Consumes("*/*") public Response remove(@PathParam("id") String id,@HeaderParam("If-Match") String match) {
        noQueries();UUID key=ContractInputs.pathId(id);return tx(()->{ApiReplies.match(uploads().get(key),match);uploads().remove(key);return new ApiReplies(204,null,null,null);});
    }
    @POST @Path("/uploads/{id}/preview") public Response preview(@PathParam("id") String id,JsonNode body) {
        noQueries();UUID key=ContractInputs.pathId(id);var value=inputs.validate("AccountArchiveReviewInput",body);
        return tx(()->importer().preview(key,value.path("restoreTimeZone").asBoolean()));
    }
    @POST @Path("/uploads/{id}/import") public Response apply(@PathParam("id") String id,@HeaderParam("If-Match") String match,JsonNode body) {
        noQueries();UUID key=ContractInputs.pathId(id);var value=inputs.validate("AccountArchiveReviewInput",body);
        return tx(()->importer().apply(key,value.path("restoreTimeZone").asBoolean(),match));
    }
    @GET @Path("/imports") public Response history() {
        var query=uri.getQueryParameters();ContractInputs.queries(query,"limit","after");int limit=ContractInputs.number(query.getFirst("limit"),20,1,100);
        UUID after=query.getFirst("after")==null ? null : ContractInputs.pathId(query.getFirst("after"));
        return tx(()->{
            if(after!=null) store.find("ARCHIVE_IMPORT",after).orElseThrow(ApiFailure::notFound);
            var value=object();var items=value.putArray("items");value.putNull("nextAfter");
            String sql="SELECT payload FROM tk_resource WHERE owner_id=? AND entity_type='ARCHIVE_IMPORT'"+(after==null ? "" : " AND id>?")+" ORDER BY id FETCH FIRST "+(limit+1)+" ROWS ONLY";
            Object[] parameters=after==null ? new Object[]{store.owner()} : new Object[]{store.owner(),after};
            try(var statement=store.statement(sql,parameters);var rows=statement.executeQuery()) {
                while(rows.next()) {if(items.size()==limit) {value.put("nextAfter",items.get(items.size()-1).path("id").asText());break;}items.add(parse(rows.getString(1)));}
            }catch(SQLException unavailable) {throw new ApiFailure(503,"UNAVAILABLE");}
            return new ApiReplies(200,value,null,null);
        });
    }
}
