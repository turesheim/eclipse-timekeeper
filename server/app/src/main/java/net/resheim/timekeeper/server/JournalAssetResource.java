package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.etag;

import java.util.Base64;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** Browser/API access to account-scoped journal companions. Binary content is never JSON API metadata. */
@Path("/api/v1/journal-assets") @Blocking
public class JournalAssetResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;

    private Response tx(Supplier<Response> action){
        return store.authenticated(identity,()->Response.fromResponse(action.get()).header("Cache-Control","private, no-store")
                .header("X-Content-Type-Options","nosniff").build());
    }

    @GET @Path("/{filename}") public Response get(@PathParam("filename")String filename,
            @HeaderParam("If-None-Match")String none){return retrieve(filename,none,false);}

    @HEAD @Path("/{filename}") public Response head(@PathParam("filename")String filename,
            @HeaderParam("If-None-Match")String none){return retrieve(filename,none,true);}

    private Response retrieve(String filename,String none,boolean head){
        return tx(()->{
            var value=new JournalAssets(store).get(filename);var tag=etag(value);
            if(tag.equals(none))return Response.status(304).header("ETag",tag).build();
            var bytes=JournalAssets.bytes(value);var type=value.path("mediaType").asText();
            var response=Response.ok(head?null:bytes).type(type).header("ETag",tag).header("Content-Length",bytes.length);
            if(!type.startsWith("image/"))response.header("Content-Disposition","attachment; filename=\""+filename+"\"");
            return response.build();
        });
    }

    @PUT @Path("/{filename}") @Consumes("application/json") public Response put(@PathParam("filename")String filename,
            @HeaderParam("If-Match")String match,@HeaderParam("If-None-Match")String none,JsonNode body){
        JournalAssets.name(filename);
        if(body==null||!body.isObject()||body.size()!=1||!body.path("data").isTextual())throw ApiFailure.badRequest();
        String encoded=body.path("data").asText();
        if(encoded.length()>(JournalAssets.MAX_BYTES+2)/3*4+4)throw new ApiFailure(413,"JOURNAL_ASSET_TOO_LARGE");
        final byte[] bytes;
        try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw ApiFailure.badRequest();}
        return tx(()->{
            var assets=new JournalAssets(store);var previous=assets.existing(filename);
            boolean present=previous!=null&&!previous.path("deleted").asBoolean();
            if(match!=null&&none!=null)throw ApiFailure.badRequest();
            if(none!=null&&!"*".equals(none))throw ApiFailure.badRequest();
            if(none!=null&&present)return Response.status(412).build();
            if(match!=null&&(!present||!match.equals(etag(previous))))return Response.status(412).build();
            if(match==null&&none==null)throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            var saved=assets.save(filename,match,bytes);
            return Response.status(present?204:201).header("ETag",etag(saved)).build();
        });
    }

    @DELETE @Path("/{filename}") public Response delete(@PathParam("filename")String filename,
            @HeaderParam("If-Match")String match){
        if(match==null||"*".equals(match))throw new ApiFailure(428,"PRECONDITION_REQUIRED");
        return tx(()->Response.noContent().header("ETag",etag(new JournalAssets(store).delete(filename,match))).build());
    }
}
