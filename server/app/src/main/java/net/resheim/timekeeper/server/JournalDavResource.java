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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Retention(RetentionPolicy.RUNTIME) @HttpMethod("PROPFIND") @interface PROPFIND {}

/** Account-scoped WebDAV view of daily and ISO-week Markdown documents. */
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
        return store.authenticated(identity,()->store.withJournalSource(DayNoteHistory.Source.WEBDAV,()->Response.fromResponse(action.get()).header("Cache-Control","private, no-store").build()));
    }
    private interface NoteFile {
        String filename();
        ObjectNode get();
        byte[] document();
        Instant modifiedAt();
        ObjectNode save(String match,byte[] bytes);
        ObjectNode delete(String match);
    }
    private NoteFile file(String filename){
        if(filename!=null&&filename.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")){
            final java.time.LocalDate date;
            try{date=JournalMarkdown.date(filename);}catch(JournalMarkdown.Invalid invalid){throw ApiFailure.notFound();}
            var notes=new DayNotes(store);
            return new NoteFile(){
                public String filename(){return filename;}
                public ObjectNode get(){return notes.getContent(date);}
                public byte[] document(){return notes.document(date);}
                public Instant modifiedAt(){return notes.modifiedAt(date);}
                public ObjectNode save(String match,byte[] bytes){return notes.saveDocument(date,match,bytes);}
                public ObjectNode delete(String match){return notes.deleteContent(date,match);}
            };
        }
        if(filename!=null&&filename.matches("[0-9]{4}-W[0-9]{2}\\.md")){
            final WeekNotes.Week week;
            try{week=WeekNotes.filename(filename);}catch(ApiFailure invalid){throw ApiFailure.notFound();}
            var notes=new WeekNotes(store);
            return new NoteFile(){
                public String filename(){return filename;}
                public ObjectNode get(){return notes.get(week);}
                public byte[] document(){return notes.document(week);}
                public Instant modifiedAt(){return notes.modifiedAt(week);}
                public ObjectNode save(String match,byte[] bytes){return notes.saveDocument(week,match,bytes);}
                public ObjectNode delete(String match){return notes.delete(week,match);}
            };
        }
        throw ApiFailure.notFound();
    }
    private static Response missing(){throw ApiFailure.notFound();}
    private static boolean present(NoteFile file){
        var value=file.get();return value.path("version").asLong()>0&&!value.path("deleted").asBoolean();
    }
    private static boolean containsEtag(String header,String tag){
        return List.of(header.split(",")).stream().map(String::trim).anyMatch(tag::equals);
    }
    @OPTIONS public Response options(){return tx(()->Response.ok().header("DAV",DAV).header("Allow",ROOT_ALLOW).build());}
    @OPTIONS @Path("/{filename}") public Response fileOptions(@PathParam("filename")String filename){
        file(filename);return tx(()->Response.ok().header("DAV",DAV).header("Allow",FILE_ALLOW).build());
    }
    @PROPFIND @Produces("application/xml") public Response collection(@HeaderParam("Depth")String depth){
        return tx(()->{
            int level=depth(depth);var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
            collection(xml);
            if(level!=0){
                xml.append("<D:response><D:href>").append(ROOT).append("assets/</D:href><D:propstat><D:prop>")
                        .append("<D:displayname>assets</D:displayname><D:resourcetype><D:collection/></D:resourcetype>")
                        .append("<D:supportedlock/><D:lockdiscovery/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
                var notes=new DayNotes(store);for(var date:notes.presentDates())file(xml,file(date+".md"));
                var weeks=new WeekNotes(store);for(var week:weeks.presentWeeks())file(xml,file(week.filename()));
            }
            xml.append("</D:multistatus>");return Response.status(207).type("application/xml; charset=utf-8").entity(xml.toString()).header("DAV",DAV).build();
        });
    }
    @PROPFIND @Path("/{filename}") @Produces("application/xml") public Response fileProperties(@PathParam("filename")String filename,@HeaderParam("Depth")String depth){
        var file=file(filename);depth(depth);
        return tx(()->{
            if(!present(file))return missing();
            var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
            file(xml,file);xml.append("</D:multistatus>");
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
    private static void file(StringBuilder xml,NoteFile file){
        byte[] bytes=file.document();if(bytes==null)return;
        Instant modified=file.modifiedAt();
        xml.append("<D:response><D:href>").append(ROOT).append(file.filename()).append("</D:href><D:propstat><D:prop>")
                .append("<D:displayname>").append(file.filename()).append("</D:displayname><D:resourcetype/>")
                .append("<D:getcontenttype>text/markdown</D:getcontenttype><D:getcontentlength>").append(bytes.length).append("</D:getcontentlength>")
                .append("<D:getetag>").append(etag(file.get())).append("</D:getetag>")
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
        var file=file(filename);
        return tx(()->{
            if(!present(file))return missing();
            var value=file.get();String tag=etag(value);
            if(match!=null&&!match.equals("*")&&!containsEtag(match,tag))return Response.status(412).build();
            if(none!=null&&(none.equals("*")||containsEtag(none,tag)))
                return Response.status(304).header("ETag",tag).build();
            byte[] bytes=file.document();
            return Response.ok(head?null:bytes).type("text/markdown; charset=utf-8").header("Content-Length",bytes.length)
                    .header("ETag",tag).header("Last-Modified",HTTP_DATE.format(file.modifiedAt())).build();
        });
    }
    @PUT @Path("/{filename}") @Consumes("*/*") public Response put(@PathParam("filename")String filename,
            @HeaderParam("If-Match")String match,@HeaderParam("If-None-Match")String none,byte[] body){
        var file=file(filename);
        if(none!=null&&!"*".equals(none))throw ApiFailure.badRequest();
        if(match!=null&&none!=null)throw ApiFailure.badRequest();
        return tx(()->{
            boolean existed=present(file);byte[] bytes=body==null?new byte[0]:body;
            if(none!=null&&existed)return Response.status(412).build();
            var previous=file.get();
            String current=etag(previous);
            if("*".equals(match))throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            if(match!=null&&!containsEtag(match,current))return Response.status(412).build();
            // An unconditional create cannot overwrite another writer. A byte-identical
            // retry is also safe after a lost response; changed content needs an ETag.
            if(match==null&&none==null&&existed){
                if(Arrays.equals(file.document(),bytes))return Response.noContent().header("ETag",current).build();
                throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            }
            var value=file.save(current,bytes);
            var response=Response.status(existed?204:201).header("ETag",etag(value));
            if(!existed)response.header("Location",ROOT+filename);
            return response.build();
        });
    }
    @DELETE @Path("/{filename}") public Response delete(@PathParam("filename")String filename,@HeaderParam("If-Match")String match){
        var file=file(filename);if(match==null)throw new ApiFailure(428,"PRECONDITION_REQUIRED");
        return tx(()->{
            if(!present(file))return missing();
            String current=etag(file.get());
            if("*".equals(match))throw new ApiFailure(428,"PRECONDITION_REQUIRED");
            if(!containsEtag(match,current))return Response.status(412).build();
            return Response.noContent().header("ETag",etag(file.delete(current))).build();
        });
    }
}
