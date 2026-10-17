package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/imports") @Blocking @Consumes("application/json") @Produces("application/json")
public class ImportResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Context UriInfo uri;
    private Response tx(Supplier<ApiReplies> work) {return store.authenticated(identity,()->work.get().response());}
    private ApiReplies json(JsonNode value) {return new ApiReplies(200,value,null,null);}
    private ImportLedger ledger() {return new ImportLedger(store);}
    private void noQueries() {ContractInputs.queries(uri.getQueryParameters());}

    @POST @Path("/toggl/preview") public Response preview(JsonNode body) {
        var input=inputs.validate("TogglImportPreviewInput",body);noQueries();return tx(()->json(new TogglImport(store).preview(input)));
    }
    @POST @Path("/toggl/jobs") public Response create(@HeaderParam("Idempotency-Key") String key,JsonNode body) {
        var input=inputs.validate("TogglImportJobInput",body);noQueries();
        return tx(()->receipts.execute(key,"POST","/imports/toggl/jobs",null,input,()->{
            var job=new TogglImport(store).create(input);return ApiReplies.resource(201,job,"/api/v1/imports/jobs/"+job.path("id").asText());
        }));
    }
    @GET @Path("/jobs/{id}") public Response get(@PathParam("id") String id) {
        UUID job=ContractInputs.pathId(id);noQueries();return tx(()->ApiReplies.resource(200,ledger().required(ImportLedger.Kind.IMPORT_JOB,job),null));
    }
    @GET public Response history() {
        var query=uri.getQueryParameters();ContractInputs.queries(query,"limit","after");
        int limit=ContractInputs.number(query.getFirst("limit"),20,1,100);
        UUID after=query.getFirst("after")==null ? null : ContractInputs.pathId(query.getFirst("after"));
        return tx(()->{
            if(after!=null) ledger().required(ImportLedger.Kind.IMPORT_JOB,after);
            var result=object();var items=result.putArray("items");result.putNull("nextAfter");
            String sql="SELECT payload FROM tk_resource WHERE owner_id=? AND entity_type='IMPORT_JOB'"+(after==null ? "" : " AND id>?")+" ORDER BY id FETCH FIRST "+(limit+1)+" ROWS ONLY";
            Object[] parameters=after==null ? new Object[]{store.owner()} : new Object[]{store.owner(),after};
            try(var statement=store.statement(sql,parameters);var rows=statement.executeQuery()) {
                while(rows.next()) {
                    if(items.size()==limit) {result.put("nextAfter",items.get(items.size()-1).path("id").asText());break;}
                    var job=(com.fasterxml.jackson.databind.node.ObjectNode)parse(rows.getString(1));job.remove("mappings");items.add(job);
                }
            } catch(SQLException failure) {throw new ApiFailure(503,"UNAVAILABLE");}
            return json(result);
        });
    }
    @GET @Path("/jobs/{id}/items") public Response items(@PathParam("id") String id) {
        UUID job=ContractInputs.pathId(id);var query=uri.getQueryParameters();ContractInputs.queries(query,"offset","limit");
        int offset=ContractInputs.number(query.getFirst("offset"),0,0,1001000),limit=ContractInputs.number(query.getFirst("limit"),100,1,100);
        return tx(()->{
            var current=ledger().required(ImportLedger.Kind.IMPORT_JOB,job);int count=current.path("itemCount").asInt();if(offset>count) throw ApiFailure.validation();
            var result=object().put("jobId",job.toString());var items=result.putArray("items");
            int next=Math.min(count,offset+limit);for(int index=offset;index<next;index++) items.add(ledger().required(ImportLedger.Kind.IMPORT_ITEM,ledger().itemId(job,index)));
            if(next<count) result.put("nextOffset",next);else result.putNull("nextOffset");return json(result);
        });
    }
    @POST @Path("/jobs/{id}/preview") public Response previewJob(@PathParam("id") String id,JsonNode body) {
        UUID job=ContractInputs.pathId(id);var input=inputs.validate("TogglImportRowsInput",body);noQueries();
        return tx(()->json(new TogglImport(store).preview(job,input.path("rows"))));
    }
    @POST @Path("/jobs/{id}/batches/{index}") public Response batch(@PathParam("id") String id,@PathParam("index") String index,JsonNode body) {
        UUID job=ContractInputs.pathId(id);int number=ContractInputs.number(index,-1,0,100000);var input=inputs.validate("TogglImportBatchInput",body);noQueries();
        return tx(()->json(new TogglImport(store).batch(job,number,input)));
    }
    @POST @Path("/jobs/{id}/undo") @Consumes("*/*") public Response undo(@PathParam("id") String id,@HeaderParam("If-Match") String match,@HeaderParam("Idempotency-Key") String key) {
        UUID job=ContractInputs.pathId(id);noQueries();
        return tx(()->receipts.execute(key,"POST","/imports/jobs/"+job+"/undo",match,null,()->ApiReplies.resource(200,new ImportUndo(store).start(job,match),null)));
    }
    @GET @Path("/jobs/{id}/undo/preview") public Response undoPreview(@PathParam("id") String id) {
        UUID job=ContractInputs.pathId(id);noQueries();return tx(()->json(new ImportUndo(store).preview(job)));
    }
    @POST @Path("/jobs/{id}/undo/batches/{index}") public Response undoBatch(@PathParam("id") String id,@PathParam("index") String index,JsonNode body) {
        UUID job=ContractInputs.pathId(id);int number=ContractInputs.number(index,-1,0,100000);var input=inputs.validate("ImportUndoBatchInput",body);noQueries();
        return tx(()->json(new ImportUndo(store).apply(job,number,input)));
    }
}
