package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.etag;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/** WebDAV collection for Markdown-relative journal attachments. */
@Path("/dav/journal/assets") @Blocking
public class JournalAssetDavResource {
    @jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uri;
    private String root(){return JournalDavPaths.root(uri)+"assets/";}
    private static final String FILE_ALLOW="OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE";
    private static final String ROOT_ALLOW="OPTIONS, PROPFIND";
    private static final DateTimeFormatter HTTP_DATE=DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    @Inject ServerStore store;
    @Inject RequestIdentity identity;

    private Response tx(Supplier<Response> action){
        return store.authenticated(identity,()->Response.fromResponse(action.get()).header("Cache-Control","private, no-store")
                .header("X-Content-Type-Options","nosniff").build());
    }

    @OPTIONS public Response options(){return tx(()->Response.ok().header("DAV","1").header("Allow",ROOT_ALLOW).build());}

    @OPTIONS @Path("/{filename}") public Response fileOptions(@PathParam("filename")String filename){
        JournalAssets.name(filename);return tx(()->Response.ok().header("DAV","1").header("Allow",FILE_ALLOW).build());
    }

    @PROPFIND @Produces("application/xml") public Response collection(@HeaderParam("Depth")String depth){
        return tx(()->{
            int level=depth(depth);var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
            xml.append("<D:response><D:href>").append(root()).append("</D:href><D:propstat><D:prop>")
                    .append("<D:displayname>assets</D:displayname><D:resourcetype><D:collection/></D:resourcetype>")
                    .append("<D:supportedlock/><D:lockdiscovery/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
            if(level!=0)for(var value:new JournalAssets(store).list())file(xml,value);
            xml.append("</D:multistatus>");
            return Response.status(207).type("application/xml; charset=utf-8").entity(xml.toString()).header("DAV","1").build();
        });
    }

    @PROPFIND @Path("/{filename}") @Produces("application/xml") public Response properties(@PathParam("filename")String filename,
            @HeaderParam("Depth")String depth){
        depth(depth);return tx(()->{
            var value=new JournalAssets(store).get(filename);
            var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
            file(xml,value);xml.append("</D:multistatus>");
            return Response.status(207).type("application/xml; charset=utf-8").entity(xml.toString()).header("DAV","1").build();
        });
    }

    private static int depth(String value){
        if(value==null||value.equals("1")||value.equalsIgnoreCase("infinity"))return 1;
        if(value.equals("0"))return 0;
        throw ApiFailure.badRequest();
    }

    private void file(StringBuilder xml,com.fasterxml.jackson.databind.JsonNode value){
        var filename=value.path("filename").asText();var modified=Instant.parse(value.path("modifiedAt").asText());
        xml.append("<D:response><D:href>").append(root()).append(filename).append("</D:href><D:propstat><D:prop>")
                .append("<D:displayname>").append(filename).append("</D:displayname><D:resourcetype/>")
                .append("<D:getcontenttype>").append(value.path("mediaType").asText()).append("</D:getcontenttype>")
                .append("<D:getcontentlength>").append(value.path("byteLength").asInt()).append("</D:getcontentlength>")
                .append("<D:getetag>").append(etag(value)).append("</D:getetag>")
                .append("<D:getlastmodified>").append(HTTP_DATE.format(modified)).append("</D:getlastmodified>")
                .append("<D:creationdate>").append(modified).append("</D:creationdate>")
                .append("<D:supportedlock/><D:lockdiscovery/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
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
            var response=Response.ok(head?null:bytes).type(type).header("ETag",tag).header("Content-Length",bytes.length)
                    .header("Last-Modified",HTTP_DATE.format(Instant.parse(value.path("modifiedAt").asText())));
            if(!type.startsWith("image/"))response.header("Content-Disposition","attachment; filename=\""+filename+"\"");
            return response.build();
        });
    }

    @PUT @Path("/{filename}") @Consumes("*/*") public Response put(@PathParam("filename")String filename,
            @HeaderParam("If-Match")String match,@HeaderParam("If-None-Match")String none,byte[] body){
        JournalAssets.name(filename);
        return tx(()->{
            var assets=new JournalAssets(store);var previous=assets.existing(filename);
            boolean present=previous!=null&&!previous.path("deleted").asBoolean();
            if(match!=null&&none!=null)throw ApiFailure.badRequest();
            if(none!=null&&!"*".equals(none))throw ApiFailure.badRequest();
            if(none!=null&&present)return Response.status(412).build();
            if(match!=null&&(!present||!match.equals(etag(previous))))return Response.status(412).build();
            if(match==null&&none==null&&present){
                if(java.util.Arrays.equals(JournalAssets.bytes(previous),body))return Response.noContent().header("ETag",etag(previous)).build();
                throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            }
            var saved=assets.save(filename,match,body==null?new byte[0]:body);
            return Response.status(present?204:201).header("ETag",etag(saved)).build();
        });
    }

    @DELETE @Path("/{filename}") public Response delete(@PathParam("filename")String filename,
            @HeaderParam("If-Match")String match){
        if(match==null||"*".equals(match))throw new ApiFailure(428,"PRECONDITION_REQUIRED");
        return tx(()->Response.noContent().header("ETag",etag(new JournalAssets(store).delete(filename,match))).build());
    }
}
