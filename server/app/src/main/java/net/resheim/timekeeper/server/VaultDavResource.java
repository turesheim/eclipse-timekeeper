package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.lang.annotation.*;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Retention(RetentionPolicy.RUNTIME) @HttpMethod("MKCOL") @interface MKCOL {}
@Retention(RetentionPolicy.RUNTIME) @HttpMethod("MOVE") @interface MOVE {}

/** Hierarchical, owner-private WebDAV. No unconditional destructive operations. */
@Path("/dav/vault") @Blocking
public class VaultDavResource {
    private static final String ALLOW="OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE";
    private static final DateTimeFormatter HTTP_DATE=DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject RequestIdentity identity;
    @jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uri;
    private String root(){return uri.getPath().replaceFirst("^/","").startsWith("remote.php/webdav/")?"/remote.php/webdav/vault/":"/dav/vault/";}
    private VaultPath path(){return parse(uri.getRequestUri().getRawPath());}
    private VaultPath parse(String raw){
        String prefix=root();if(raw.equals(prefix.substring(0,prefix.length()-1)))return VaultPath.root();
        if(!raw.startsWith(prefix))throw ApiFailure.badRequest();String relative=raw.substring(prefix.length());
        if(relative.endsWith("/"))relative=relative.substring(0,relative.length()-1);
        return VaultPath.encoded(relative);
    }
    private VaultCollection collection(){return new VaultCollection(store,limits);}
    private Response tx(Supplier<Response> action){return store.authenticated(identity,()->store.withJournalSource(DayNoteHistory.Source.WEBDAV,()->{
        collection().initialize();return Response.fromResponse(action.get()).header("Cache-Control","private, no-store").header("DAV","1").header("X-Content-Type-Options","nosniff").build();
    }));}
    @OPTIONS public Response options(){return tx(()->Response.ok().header("Allow",ALLOW).build());}
    @OPTIONS @Path("/{path:.*}") public Response pathOptions(){return options();}
    @PROPFIND public Response properties(@HeaderParam("Depth")String depth){return propertyResponse(depth);}
    @PROPFIND @Path("/{path:.*}") public Response pathProperties(@HeaderParam("Depth")String depth){return propertyResponse(depth);}
    private Response propertyResponse(String depth){
        if(depth!=null&&!Set.of("0","1","infinity").contains(depth))throw ApiFailure.badRequest();
        return tx(()->{
            var vault=collection();var entry=vault.get(path());var items=new ArrayList<ObjectNode>();items.add(entry);
            if(VaultCollection.folder(entry)&&!"0".equals(depth)){
                if("infinity".equals(depth)){var descendants=vault.descendants(entry,true);items.clear();items.addAll(descendants);}
                else items.addAll(new VaultIndex(store).children(VaultCollection.id(entry)).stream().filter(value->new VaultSettings(store).allowed(VaultCollection.path(value))).toList());
            }
            var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
            for(var item:items)property(xml,vault,item);xml.append("</D:multistatus>");
            return Response.status(207).type("application/xml; charset=utf-8").entity(xml.toString()).build();
        });
    }
    private void property(StringBuilder xml,VaultCollection vault,ObjectNode entry){
        boolean folder=VaultCollection.folder(entry);var path=VaultCollection.path(entry);
        xml.append("<D:response><D:href>").append(escape(root()+path.encoded()+(folder&&!path.isRoot()?"/":""))).append("</D:href><D:propstat><D:prop>")
                .append("<D:displayname>").append(escape(path.isRoot()?"vault":path.name())).append("</D:displayname><D:resourcetype>")
                .append(folder?"<D:collection/>":"").append("</D:resourcetype><D:getetag>").append(escape(vault.tag(entry))).append("</D:getetag>")
                .append("<D:getcontentlength>").append(folder?0:entry.path("byteLength").asLong()).append("</D:getcontentlength><D:getcontenttype>")
                .append(folder?"httpd/unix-directory":media(entry)).append("</D:getcontenttype>");
        Instant modified=entry.hasNonNull("modifiedAt")?Instant.parse(entry.path("modifiedAt").asText()):Instant.EPOCH;
        xml.append("<D:getlastmodified>").append(HTTP_DATE.format(modified)).append("</D:getlastmodified><D:creationdate>").append(modified)
                .append("</D:creationdate><D:supportedlock/><D:lockdiscovery/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static String media(ObjectNode entry){return Set.of("MARKDOWN","DAY","WEEK","EVENT").contains(entry.path("kind").asText())?"text/markdown; charset=utf-8":"application/octet-stream";}
    @GET @Path("/{path:.*}") public Response get(@HeaderParam("If-Match")String match,@HeaderParam("If-None-Match")String none){return retrieve(match,none,false);}
    @HEAD @Path("/{path:.*}") public Response head(@HeaderParam("If-Match")String match,@HeaderParam("If-None-Match")String none){return retrieve(match,none,true);}
    private Response retrieve(String match,String none,boolean head){return tx(()->{
        var vault=collection();var entry=vault.get(path());if(VaultCollection.folder(entry))throw new ApiFailure(405,"METHOD_NOT_ALLOWED");String tag=vault.tag(entry);
        if(match!=null&&!match.equals("*"))VaultCollection.match(tag,match);
        if(none!=null&&(none.equals("*")||Arrays.stream(none.split(",")).map(String::trim).anyMatch(tag::equals)))return Response.status(304).header("ETag",tag).build();
        return Response.ok(head?null:vault.read(entry)).type(media(entry)).header("ETag",tag).header("Content-Length",entry.path("byteLength").asLong()).build();
    });}
    @PUT @Path("/{path:.*}") @Consumes("*/*") public Response put(@HeaderParam("If-Match")String match,@HeaderParam("If-None-Match")String none,byte[] bytes){
        if(none!=null&&!none.equals("*")||none!=null&&match!=null)throw ApiFailure.badRequest();
        return tx(()->{var vault=collection();var target=path();boolean existed=new VaultIndex(store).at(target,false)!=null;
            var entry=vault.put(target,match,none!=null,bytes);return Response.status(existed?204:201).header("ETag",vault.tag(entry)).header("Location",root()+VaultCollection.path(entry).encoded()).build();});
    }
    @MKCOL @Path("/{path:.*}") public Response mkdir(byte[] body){
        if(body!=null&&body.length>0)throw new ApiFailure(415,"UNSUPPORTED_MEDIA_TYPE");
        return tx(()->{var vault=collection();var entry=vault.mkdir(path());return Response.status(201).header("ETag",vault.tag(entry)).build();});
    }
    @DELETE @Path("/{path:.*}") public Response delete(@HeaderParam("If-Match")String match){return tx(()->{var vault=collection();vault.delete(vault.get(path()),match);return Response.noContent().build();});}
    @MOVE @Path("/{path:.*}") public Response move(@HeaderParam("If-Match")String match,@HeaderParam("Destination")String destination,@HeaderParam("Overwrite")String overwrite){
        if(destination==null||overwrite!=null&&!Set.of("T","F").contains(overwrite))throw ApiFailure.badRequest();
        final URI target;try{target=URI.create(destination);}catch(IllegalArgumentException invalid){throw ApiFailure.badRequest();}
        var request=uri.getRequestUri();
        if(target.getRawQuery()!=null||target.getRawFragment()!=null||target.getRawUserInfo()!=null
                ||target.isAbsolute()&&(!Objects.equals(target.getScheme(),request.getScheme())||!Objects.equals(target.getRawAuthority(),request.getRawAuthority()))
                ||target.getRawPath()==null||!target.getRawPath().startsWith("/"))throw ApiFailure.badRequest();
        var next=parse(target.getRawPath());
        return tx(()->{var vault=collection();if(new VaultIndex(store).at(next,false)!=null)throw new ApiFailure(412,"VAULT_DESTINATION_EXISTS");
            var entry=vault.move(vault.get(path()),next,match);return Response.status(201).header("ETag",vault.tag(entry)).header("Location",root()+VaultCollection.path(entry).encoded()).build();});
    }
}
