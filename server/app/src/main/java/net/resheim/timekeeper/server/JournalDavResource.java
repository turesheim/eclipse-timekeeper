package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.etag;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Retention(RetentionPolicy.RUNTIME) @HttpMethod("PROPFIND") @interface PROPFIND {}

/** Account-scoped WebDAV view of the canonical DAY_NOTE Markdown documents. */
@Path("/dav/journal") @Blocking
public class JournalDavResource {
    private static final String ROOT="/dav/journal/";
    private static final String DAV="1";
    private static final String FILE_ALLOW="OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE";
    private static final String ROOT_ALLOW="OPTIONS, PROPFIND";
    private static final DateTimeFormatter HTTP_DATE=DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    @Inject ServerStore store;
    @Inject RequestIdentity identity;

    private Response tx(Supplier<Response> action){
        return store.authenticated(identity,()->Response.fromResponse(action.get()).header("Cache-Control","private, no-store").build());
    }
    private static java.time.LocalDate date(String filename){
        try{return JournalMarkdown.date(filename);}catch(JournalMarkdown.Invalid invalid){throw ApiFailure.notFound();}
    }
    private static Response missing(){throw ApiFailure.notFound();}
    private static boolean present(DayNotes notes,java.time.LocalDate date){
        var value=notes.get(date);return value.path("version").asLong()>0&&!value.path("deleted").asBoolean();
    }
    private static boolean containsEtag(String header,String tag){
        return List.of(header.split(",")).stream().map(String::trim).anyMatch(tag::equals);
    }
    @OPTIONS public Response options(){return tx(()->Response.ok().header("DAV",DAV).header("Allow",ROOT_ALLOW).build());}
    @OPTIONS @Path("/{filename}") public Response fileOptions(@PathParam("filename")String filename){
        date(filename);return tx(()->Response.ok().header("DAV",DAV).header("Allow",FILE_ALLOW).build());
    }
    @PROPFIND @Produces("application/xml") public Response collection(@HeaderParam("Depth")String depth){
        return tx(()->{
            int level=depth(depth);var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
            collection(xml);
            if(level!=0){
                xml.append("<D:response><D:href>").append(ROOT).append("assets/</D:href><D:propstat><D:prop>")
                        .append("<D:displayname>assets</D:displayname><D:resourcetype><D:collection/></D:resourcetype>")
                        .append("<D:supportedlock/><D:lockdiscovery/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
                var notes=new DayNotes(store);for(var date:notes.presentDates())file(xml,notes,date);
            }
            xml.append("</D:multistatus>");return Response.status(207).type("application/xml; charset=utf-8").entity(xml.toString()).header("DAV",DAV).build();
        });
    }
    @PROPFIND @Path("/{filename}") @Produces("application/xml") public Response fileProperties(@PathParam("filename")String filename,@HeaderParam("Depth")String depth){
        var date=date(filename);depth(depth);
        return tx(()->{
            var notes=new DayNotes(store);if(!present(notes,date))return missing();
            var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
            file(xml,notes,date);xml.append("</D:multistatus>");
            return Response.status(207).type("application/xml; charset=utf-8").entity(xml.toString()).header("DAV",DAV).build();
        });
    }
    private static int depth(String depth){
        if(depth==null||depth.equals("1")||depth.equalsIgnoreCase("infinity"))return 1;
        if(depth.equals("0"))return 0;
        throw ApiFailure.badRequest();
    }
    private static void collection(StringBuilder xml){
        xml.append("<D:response><D:href>").append(ROOT).append("</D:href><D:propstat><D:prop>")
                .append("<D:displayname>journal</D:displayname><D:resourcetype><D:collection/></D:resourcetype>")
                .append("<D:supportedlock/><D:lockdiscovery/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }
    private static void file(StringBuilder xml,DayNotes notes,java.time.LocalDate date){
        byte[] bytes=notes.document(date);if(bytes==null)return;
        Instant modified=notes.modifiedAt(date);
        xml.append("<D:response><D:href>").append(ROOT).append(date).append(".md</D:href><D:propstat><D:prop>")
                .append("<D:displayname>").append(date).append(".md</D:displayname><D:resourcetype/>")
                .append("<D:getcontenttype>text/markdown</D:getcontenttype><D:getcontentlength>").append(bytes.length).append("</D:getcontentlength>")
                .append("<D:getetag>").append(etag(notes.get(date))).append("</D:getetag>")
                .append("<D:getlastmodified>").append(HTTP_DATE.format(modified)).append("</D:getlastmodified>")
                .append("<D:creationdate>").append(modified).append("</D:creationdate>")
                .append("<D:supportedlock/><D:lockdiscovery/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }
    @GET @Path("/{filename}") @Produces("text/markdown") public Response get(@PathParam("filename")String filename,
            @HeaderParam("If-None-Match")String none,@HeaderParam("If-Match")String match){
        return retrieve(filename,none,match,false);
    }
    @HEAD @Path("/{filename}") @Produces("text/markdown") public Response head(@PathParam("filename")String filename,
            @HeaderParam("If-None-Match")String none,@HeaderParam("If-Match")String match){
        return retrieve(filename,none,match,true);
    }
    private Response retrieve(String filename,String none,String match,boolean head){
        var date=date(filename);
        return tx(()->{
            var notes=new DayNotes(store);if(!present(notes,date))return missing();
            var value=notes.get(date);String tag=etag(value);
            if(match!=null&&!match.equals("*")&&!containsEtag(match,tag))return Response.status(412).build();
            if(none!=null&&(none.equals("*")||containsEtag(none,tag)))
                return Response.status(304).header("ETag",tag).build();
            byte[] bytes=notes.document(date);
            return Response.ok(head?null:bytes).type("text/markdown; charset=utf-8").header("Content-Length",bytes.length)
                    .header("ETag",tag).header("Last-Modified",HTTP_DATE.format(notes.modifiedAt(date))).build();
        });
    }
    @PUT @Path("/{filename}") @Consumes("*/*") public Response put(@PathParam("filename")String filename,
            @HeaderParam("If-Match")String match,@HeaderParam("If-None-Match")String none,byte[] body){
        var date=date(filename);
        if(none!=null&&!"*".equals(none))throw ApiFailure.badRequest();
        if(match!=null&&none!=null)throw ApiFailure.badRequest();
        return tx(()->{
            var notes=new DayNotes(store);boolean existed=present(notes,date);byte[] bytes=body==null?new byte[0]:body;
            if(none!=null&&existed)return Response.status(412).build();
            var previous=notes.get(date);
            String current=etag(previous);
            if("*".equals(match))throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            if(match!=null&&!containsEtag(match,current))return Response.status(412).build();
            // An unconditional create cannot overwrite another writer. A byte-identical
            // retry is also safe after a lost response; changed content needs an ETag.
            if(match==null&&none==null&&existed){
                if(Arrays.equals(notes.document(date),bytes))return Response.noContent().header("ETag",current).build();
                throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            }
            var value=notes.saveDocument(date,current,bytes);
            var response=Response.status(existed?204:201).header("ETag",etag(value));
            if(!existed)response.header("Location",ROOT+filename);
            return response.build();
        });
    }
    @DELETE @Path("/{filename}") public Response delete(@PathParam("filename")String filename,@HeaderParam("If-Match")String match){
        var date=date(filename);if(match==null)throw new ApiFailure(428,"PRECONDITION_REQUIRED");
        return tx(()->{
            var notes=new DayNotes(store);if(!present(notes,date))return missing();
            String current=etag(notes.get(date));
            if("*".equals(match))throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            if(!containsEtag(match,current))return Response.status(412).build();
            return Response.noContent().header("ETag",etag(notes.delete(date,current))).build();
        });
    }
}
