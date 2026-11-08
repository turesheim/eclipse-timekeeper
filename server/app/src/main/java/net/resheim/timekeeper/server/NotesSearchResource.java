package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** Search work runs outside owner transactions; publication rechecks canonical authorization. */
@Path("/api/v1/notes/search") @Blocking @Produces("application/json")
public class NotesSearchResource {
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject RequestIdentity identity;
    @Inject NotesSearchService search;
    @Context UriInfo uri;
    private record Access(NotesSearchSources.State state,NotesSearchCursor cursor){
        @Override public String toString(){return "NotesSearchResource.Access[]";}
    }
    private Access access(){return store.authenticated(identity,()->{
        new VaultCollection(store,limits).initialize();new NotesSearchChanges(store).snapshot();
        return new Access(new NotesSearchSources(store).state(),new NotesSearchCursor(store.cursorSecret(),store.owner(),store.now()));
    });}
    private static Response response(int status,Object entity){return Response.status(status).entity(entity).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").build();}
    private static ApiFailure indexing(){return new ApiFailure(503,"NOTES_SEARCH_INDEXING");}
    @GET public Response results(@QueryParam("q")String text,@QueryParam("limit")String requested,@QueryParam("cursor")String cursor){
        ContractInputs.queries(uri.getQueryParameters(),"q","limit","cursor");
        var query=NotesSearchQuery.parse(text);int count=ContractInputs.number(requested,50,1,200);var access=access();
        var frame=access.cursor().read(cursor,access.state(),query);
        final NotesLuceneIndex.Page page;
        try{page=search.search(access.state(),query,count,frame.after(),250);}catch(IOException unavailable){throw indexing();}
        return store.authenticated(identity,()->{
            var sources=new NotesSearchSources(store);var current=sources.state();
            if(!current.equals(access.state()))throw cursor==null?indexing():new ApiFailure(412,"NOTES_SEARCH_SNAPSHOT_EXPIRED");
            var result=object();result.put("snapshot",access.cursor().write(frame.position(null),current,query));var items=result.putArray("items");
            for(var hit:page.items()){
                var item=items.addObject();item.set("entry",sources.current(hit));
                var excerpt=item.putObject("excerpt").put("text",hit.excerpt().text());var ranges=excerpt.putArray("highlights");
                for(var range:hit.excerpt().highlights())ranges.addObject().put("start",range.start()).put("end",range.end());
            }
            if(page.next()!=null)result.put("nextCursor",access.cursor().write(frame.position(page.next()),current,query));
            return response(200,result);
        });
    }
    @GET @Path("/status") public Response status(){
        ContractInputs.queries(uri.getQueryParameters());var access=access();var status=search.status(access.state());
        return store.authenticated(identity,()->response(200,status));
    }
    @POST @Path("/rebuild") public Response rebuild(){
        ContractInputs.queries(uri.getQueryParameters());var access=access();
        try{search.rebuild(access.state());}catch(IOException unavailable){throw indexing();}
        return store.authenticated(identity,()->response(202,search.status(access.state())));
    }
}
