package net.resheim.timekeeper.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/** Authenticated HTTP boundary for the shared server-side reconciliation engine. */
@Path("/api/v1/task-sync") @Blocking @Consumes("application/json") @Produces("application/json")
public class TaskSyncPreviewResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;

    private JsonNode input(String schema,JsonNode body){
        ContractInputs.queries(uri.getQueryParameters());return inputs.validate(schema,body);
    }
    private TaskSync sync(){return new TaskSync(store,receipts);}
    @GET @Path("/lists") public Response synchronizedTaskLists(){
        ContractInputs.queries(uri.getQueryParameters(),"limit","cursor");
        return store.authenticated(identity,()->{
            var result=JsonValues.object().put("revision",store.revision());var rows=result.putArray("items");store.service().synchronizedTaskLists().forEach(list->rows.add(JsonValues.snapshot(store.owner(),list)));result.putNull("nextCursor");return new ApiReplies(200,result,null,null).response();
        });
    }
    @POST @Path("/import") public Response importTask(JsonNode body){
        var input=input("TaskSyncImportInput",body);
        return store.authenticated(identity,()->sync().importTask(input).response());
    }
    @POST @Path("/apply") public Response apply(JsonNode body,@HeaderParam("If-Match") String match){
        var input=input("TaskSyncApplyInput",body);
        return store.authenticated(identity,()->sync().apply(input,match).response());
    }
    @POST @Path("/export-preview") public Response exportPreview(JsonNode body){
        var input=input("TaskSyncExportInput",body);
        return store.authenticated(identity,()->sync().exportPreview(input).response());
    }
    @POST @Path("/preview") public Response preview(JsonNode body){
        var input=input("TaskSyncPreviewInput",body);
        return store.authenticated(identity,()->sync().preview(input).response());
    }
}
