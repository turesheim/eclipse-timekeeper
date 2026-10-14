package net.resheim.timekeeper.server;

import java.sql.SQLException;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Path("/api/v1/changes") @Blocking @Produces("application/json")
public class ChangeResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject TokenRegistry tokens;
    @Context HttpHeaders headers;
    @Context UriInfo uri;
    @GET public Response changes() {
        var query=uri.getQueryParameters();ContractInputs.queries(query,"after","limit","waitSeconds");
        long after=ContractInputs.sequence(query.getFirst("after"));int limit=ContractInputs.number(query.getFirst("limit"),100,1,500),wait=ContractInputs.number(query.getFirst("waitSeconds"),0,0,25);
        String owner=identity.owner();long deadline=System.nanoTime()+wait*1_000_000_000L;
        while(true) {
            ObjectNode page=store.authenticated(identity,() -> page(after,limit));
            if(!page.get("items").isEmpty() || System.nanoTime()>=deadline) return new ApiReplies(200,page,null,null).response();
            // Release connection/owner lock while waiting; cancellation does not mutate data.
            try {Thread.sleep(Math.min(250,Math.max(1,(deadline-System.nanoTime())/1_000_000)));}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ApiFailure(503,"UNAVAILABLE");}
        }
    }
    private ObjectNode page(long after,int limit) {
        long head=store.revision();if(after>head) throw new ApiFailure(409,"RESYNC_REQUIRED");
        var result=JsonValues.object();var items=result.putArray("items");long next=after;
        try(var statement=store.statement("SELECT sequence,entity_type,entity_id,change_type,occurred_at FROM tk_change WHERE owner_id=? AND sequence>? ORDER BY sequence FETCH FIRST ? ROWS ONLY",store.owner(),after,limit+1);var rows=statement.executeQuery()) {
            while(rows.next()) {
                if(items.size()==limit) break;
                var item=JsonValues.object();next=rows.getLong(1);item.put("sequence",next);item.put("entityType",rows.getString(2));item.put("entityId",rows.getString(3));item.put("changeType",rows.getString(4));item.put("occurredAt",store.timestamp(rows,5).toString());items.add(item);
            }
        } catch(SQLException e) {throw new ApiFailure(503,"UNAVAILABLE");}
        result.put("nextSequence",next);result.put("hasMore",next<head);return result;
    }
}
