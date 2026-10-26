package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import net.resheim.eclipse.timekeeper.domain.TaskAttachment;

/** Bounded attachment bytes are stored under the same owner as their task. */
@Path("/api/v1/tasks/{taskId}/attachments") @Blocking @Consumes("application/json") @Produces("application/json")
public class TaskAttachmentResource {
    @Inject ServerStore store;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject CommandReceipts receipts;
    @Inject Pages pages;
    @Context UriInfo uri;
    private Response tx(java.util.function.Supplier<ApiReplies> work) {return store.authenticated(identity,() -> work.get().response());}
    private UUID task(String id) {UUID key=ContractInputs.pathId(id);store.find("TASK",key).orElseThrow(ApiFailure::notFound);return key;}
    private JsonNode attachment(UUID taskId,UUID id) {
        var value=store.find("TASK_ATTACHMENT",id).orElseThrow(ApiFailure::notFound);
        if(!value.path("taskId").asText().equals(taskId.toString())) throw ApiFailure.notFound();
        return value;
    }
    private static ObjectNode summary(JsonNode value) {
        var copy=((ObjectNode)value).deepCopy();copy.remove("data");return copy;
    }
    private static TaskAttachment value(UUID id,UUID taskId,JsonNode input,long version) {
        return new TaskAttachment(id,taskId,input.path("fileName").asText(),input.path("contentType").asText(),
                input.path("bytes").asInt(),input.path("sha256").asText(),input.path("data").asText(),
                optionalText(input.get("providerId")),optionalText(input.get("remoteId")),version);
    }
    private void unique(UUID taskId,TaskAttachment candidate,UUID except) {
        int count=0;
        for(var existing:store.list("TASK_ATTACHMENT")) if(existing.path("taskId").asText().equals(taskId.toString())) {
            count++;
            if(!uuid(existing.get("id")).equals(except) && candidate.providerId().isPresent()
                    && existing.path("providerId").asText().equals(candidate.providerId().orElseThrow())
                    && existing.path("remoteId").asText().equals(candidate.remoteId().orElseThrow())) throw ApiFailure.conflict();
        }
        if(except==null && count>=20) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
    }
    @GET public Response list(@PathParam("taskId") String taskId) {
        ContractInputs.queries(uri.getQueryParameters(),"limit","cursor");UUID key=ContractInputs.pathId(taskId);
        var query=uri.getQueryParameters();int limit=ContractInputs.number(query.getFirst("limit"),100,1,500);
        return tx(() -> {task(key.toString());var values=new ArrayList<JsonNode>();
            for(var item:store.list("TASK_ATTACHMENT")) if(item.path("taskId").asText().equals(key.toString())) values.add(summary(item));
            var filter=object();filter.put("taskId",key.toString());
            return new ApiReplies(200,pages.page("TASK_ATTACHMENT",values,filter,limit,query.getFirst("cursor")),null,null);
        });
    }
    @POST public Response create(@PathParam("taskId") String taskId,@HeaderParam("Idempotency-Key") String receipt,JsonNode body) {
        UUID key=ContractInputs.pathId(taskId);JsonNode input=inputs.validate("TaskAttachmentInput",body);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> receipts.execute(receipt,"POST","/tasks/"+key+"/attachments",null,input,() -> {
            task(key.toString());var created=value(UUID.randomUUID(),key,input,0);unique(key,created,null);
            store.save("TASK_ATTACHMENT",created);store.change("TASK_ATTACHMENT","CREATED",created.id().toString());
            return ApiReplies.resource(201,snapshot(store.owner(),created),"/api/v1/tasks/"+key+"/attachments/"+created.id());
        }));
    }
    @GET @Path("/{id}") public Response get(@PathParam("taskId") String taskId,@PathParam("id") String id) {
        UUID parent=ContractInputs.pathId(taskId),key=ContractInputs.pathId(id);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> ApiReplies.resource(200,attachment(task(parent.toString()),key),null));
    }
    @GET @Path("/{id}/content") @Produces("application/octet-stream") public Response content(@PathParam("taskId") String taskId,@PathParam("id") String id) {
        UUID parent=ContractInputs.pathId(taskId),key=ContractInputs.pathId(id);ContractInputs.queries(uri.getQueryParameters());
        return store.authenticated(identity,() -> {var item=attachment(task(parent.toString()),key);
            return Response.ok(Base64.getDecoder().decode(item.path("data").asText())).type(item.path("contentType").asText())
                    .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                    .header("Content-Disposition","attachment")
                    .header("Digest","sha-256="+Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(item.path("sha256").asText())))
                    .build();
        });
    }
    @PUT @Path("/{id}") public Response update(@PathParam("taskId") String taskId,@PathParam("id") String id,@HeaderParam("If-Match") String match,JsonNode body) {
        UUID parent=ContractInputs.pathId(taskId),key=ContractInputs.pathId(id);JsonNode input=inputs.validate("TaskAttachmentInput",body);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> {var old=attachment(task(parent.toString()),key);long version=ApiReplies.match(old,match);
            var updated=value(key,parent,input,version+1);unique(parent,updated,key);
            store.save("TASK_ATTACHMENT",updated);store.change("TASK_ATTACHMENT","UPDATED",key.toString());
            return ApiReplies.resource(200,snapshot(store.owner(),updated),null);
        });
    }
    @DELETE @Path("/{id}") public Response delete(@PathParam("taskId") String taskId,@PathParam("id") String id,@HeaderParam("If-Match") String match) {
        UUID parent=ContractInputs.pathId(taskId),key=ContractInputs.pathId(id);ContractInputs.queries(uri.getQueryParameters());
        return tx(() -> {ApiReplies.match(attachment(task(parent.toString()),key),match);
            store.delete("TASK_ATTACHMENT",key);store.change("TASK_ATTACHMENT","DELETED",key.toString());
            return new ApiReplies(204,null,null,null);
        });
    }
}
