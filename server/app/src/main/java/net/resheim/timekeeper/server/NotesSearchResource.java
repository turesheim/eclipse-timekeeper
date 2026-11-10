package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    @jakarta.annotation.PostConstruct void register(){search.register(CalendarSearchSources.BACKEND);}
    private record Access(NotesSearchSources.State state,NotesSearchSources.State calendar,NotesSearchCursor cursor){
        @Override public String toString(){return "NotesSearchResource.Access[]";}
    }
    private Access access(){return store.authenticated(identity,()->{
        new VaultCollection(store,limits).initialize();new NotesSearchChanges(store).snapshot();
        return new Access(new NotesSearchSources(store).state(),new CalendarSearchSources(store).state(),new NotesSearchCursor(store.cursorSecret(),store.owner(),store.now()));
    });}
    private static Response response(int status,Object entity){return Response.status(status).entity(entity).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").build();}
    private static ApiFailure indexing(){return new ApiFailure(503,"NOTES_SEARCH_INDEXING");}
    @GET public Response results(@QueryParam("q")String text,@QueryParam("limit")String requested,@QueryParam("cursor")String cursor){
        ContractInputs.queries(uri.getQueryParameters(),"q","limit","cursor");
        var query=NotesSearchQuery.parse(text);int count=ContractInputs.number(requested,50,1,200);var access=access();
        var frame=access.cursor().read(cursor,access.state(),query,access.calendar());
        final NotesLuceneIndex.Page page;
        try{
            search.status(access.state());search.status(access.calendar(),CalendarSearchSources.BACKEND);
            var calendar=search.search(access.calendar(),query,count,frame.after(),250,CalendarSearchSources.BACKEND);
            var fences=search.sourceFences(access.calendar(),CalendarSearchSources.BACKEND,EventNotes.TYPE);
            var notes=search.search(access.state(),query,count,frame.after(),250,NotesSearchService.NOTES,fences);
            page=merge(notes,calendar,count);
        }catch(IOException unavailable){throw indexing();}
        return store.authenticated(identity,()->{
            var sources=new NotesSearchSources(store);var current=sources.state();
            var calendars=new CalendarSearchSources(store);var calendarState=calendars.state();
            if(!current.equals(access.state())||!calendarState.equals(access.calendar()))throw cursor==null?indexing():new ApiFailure(412,"NOTES_SEARCH_SNAPSHOT_EXPIRED");
            var result=object();result.put("snapshot",access.cursor().write(frame.position(null),current,query,calendarState));var items=result.putArray("items");var bindings=new HashMap<UUID,String>();
            for(var hit:page.items()){
                items.add(item(hit,sources,calendars,bindings));
            }
            if(page.next()!=null)result.put("nextCursor",access.cursor().write(frame.position(page.next()),current,query,calendarState));
            return response(200,result);
        });
    }
    static NotesLuceneIndex.Page merge(NotesLuceneIndex.Page notes,NotesLuceneIndex.Page calendar,int limit){
        var hits=new ArrayList<>(notes.items());hits.addAll(calendar.items());
        hits.sort((left,right)->{var a=left.position();var b=right.position();int score=Float.compare(b.score(),a.score());if(score!=0)return score;int path=new org.apache.lucene.util.BytesRef(a.pathKey()).compareTo(new org.apache.lucene.util.BytesRef(b.pathKey()));return path!=0?path:a.id().toString().compareTo(b.id().toString());});
        boolean more=hits.size()>limit||notes.next()!=null||calendar.next()!=null;var selected=hits.subList(0,Math.min(limit,hits.size()));
        return new NotesLuceneIndex.Page(selected,more&&!selected.isEmpty()?selected.getLast().position():null);
    }
    private static ObjectNode item(NotesLuceneIndex.Hit hit,NotesSearchSources sources,CalendarSearchSources calendars,Map<UUID,String> bindings){
        var item=object();if(!hit.sourceType().equals(CalendarSearchSources.SOURCE))item.set("entry",sources.current(hit));
        if(hit.calendar()!=null)item.set("calendarEvent",calendars.currentCalendar(hit,bindings));
        var excerpt=item.putObject("excerpt").put("text",hit.excerpt().text());var ranges=excerpt.putArray("highlights");
        for(var range:hit.excerpt().highlights())ranges.addObject().put("start",range.start()).put("end",range.end());return item;
    }
    @GET @Path("/calendar/{id}") public Response calendar(@PathParam("id")String id){
        ContractInputs.queries(uri.getQueryParameters());UUID key=ContractInputs.pathId(id);var access=access();final NotesLuceneIndex.Hit hit;
        try{search.status(access.calendar(),CalendarSearchSources.BACKEND);hit=search.lookup(access.calendar(),CalendarSearchSources.BACKEND,key);}catch(IOException unavailable){throw indexing();}
        if(hit==null)throw ApiFailure.notFound();
        return store.authenticated(identity,()->{
            var sources=new NotesSearchSources(store);var calendars=new CalendarSearchSources(store);
            if(!sources.state().equals(access.state())||!calendars.state().equals(access.calendar()))throw indexing();
            if(hit.calendar()==null)throw indexing();return response(200,item(hit,sources,calendars,new HashMap<>()));
        });
    }
    private NotesSearchService.Status combined(Access access){
        var notes=search.status(access.state());var calendar=search.status(access.calendar(),CalendarSearchSources.BACKEND);
        String state=notes.state().equals("UNAVAILABLE")||calendar.state().equals("UNAVAILABLE")?"UNAVAILABLE":notes.state().equals("READY")&&calendar.state().equals("READY")?"READY":"INDEXING";
        return new NotesSearchService.Status(state,notes.generation(),notes.target(),notes.rebuilding()||calendar.rebuilding(),notes.error()!=null?notes.error():calendar.error());
    }
    @GET @Path("/status") public Response status(){
        ContractInputs.queries(uri.getQueryParameters());var access=access();var status=combined(access);
        return store.authenticated(identity,()->response(200,status));
    }
    @POST @Path("/rebuild") public Response rebuild(){
        ContractInputs.queries(uri.getQueryParameters());var access=access();
        try{search.rebuild(access.state());search.rebuild(access.calendar(),CalendarSearchSources.BACKEND);}catch(IOException unavailable){throw indexing();}
        return store.authenticated(identity,()->response(202,combined(access)));
    }
}
