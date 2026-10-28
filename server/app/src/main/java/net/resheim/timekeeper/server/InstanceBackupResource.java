package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.Arrays;

/** Privileged browser-account operations; never use owner-scoped command receipts for secrets. */
@Path("/api/v1/admin/instance") @Blocking @Consumes("application/json") @Produces("application/json")
public class InstanceBackupResource {
    @Inject InstanceAdministrator administrators;
    @Inject InstanceBackupJobs jobs;
    @Inject InstanceDownloads downloads;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    private void noQueries(){ContractInputs.queries(uri.getQueryParameters());}
    private static Response reply(int status,InstanceOperations.Status value,boolean location) {
        var body=object().put("id",value.id().toString()).put("type",value.type().name()).put("state",value.state().name()).put("version",value.version()).put("updatedAt",value.updatedAt().toString());
        return new ApiReplies(status,body,null,location?"/api/v1/admin/instance/operations/"+value.id():null).response();
    }
    @POST @Path("/backups") public Response create(JsonNode body) {
        noQueries();administrators.require(identity.principal());var input=inputs.validate("InstanceBackupCreateInput",body);char[] phrase=null;
        try {
            var proof=administrators.verify(identity.principal(),input.path("currentPassword").asText());phrase=input.path("passphrase").asText().toCharArray();
            return reply(202,jobs.start(proof,phrase),true);
        }finally{if(phrase!=null)Arrays.fill(phrase,'\0');((ObjectNode)input).remove(java.util.List.of("currentPassword","passphrase"));}
    }
    @GET @Path("/operations/{id}") public Response status(@PathParam("id") String id) {
        noQueries();return reply(200,jobs.get(identity.principal(),ContractInputs.pathId(id)),false);
    }
    @POST @Path("/operations/{id}/download-grant") public Response grant(@PathParam("id") String id,JsonNode body) {
        noQueries();administrators.require(identity.principal());var operation=ContractInputs.pathId(id);var input=inputs.validate("InstanceOperationProofInput",body);
        try {
            var issued=downloads.issue(administrators.verify(identity.principal(),input.path("currentPassword").asText()),operation);
            var reply=object().put("id",operation.toString()).put("downloadUrl","/api/v1/admin/instance/operations/"+operation+"/content").put("expiresAt",issued.expiresAt().toString());
            return Response.ok(reply).header("Cache-Control","no-store").header("Set-Cookie",InstanceDownloads.cookie(issued.secret())).build();
        }finally{((ObjectNode)input).remove("currentPassword");}
    }
    @GET @Path("/operations/{id}/content") @Produces("application/octet-stream") public Response download(@PathParam("id") String id) {
        noQueries();var operation=ContractInputs.pathId(id);
        var grant=downloads.consume(identity.principal(),operation,InstanceDownloads.readCookie(headers.getRequestHeader("Cookie")));
        var proof=administrators.downloadProof(grant);
        StreamingOutput body=output->jobs.read(proof,operation,(input,length)->{
            if(length!=grant.bytes())throw new ApiFailure(503,"INSTANCE_BACKUP_FAILED");input.transferTo(output);return null;
        });
        return Response.ok(body,"application/octet-stream").header("Cache-Control","no-store").header("Content-Length",grant.bytes())
            .header("Set-Cookie",InstanceDownloads.clearCookie()).header("Content-Disposition","attachment; filename=\"timekeeper-instance-"+operation+".tkbackup\"").build();
    }
    @POST @Path("/operations/{id}/discard") public Response discard(@PathParam("id") String id,JsonNode body) {
        noQueries();administrators.require(identity.principal());var operation=ContractInputs.pathId(id);var input=inputs.validate("InstanceOperationProofInput",body);
        try{return reply(200,jobs.discard(administrators.verify(identity.principal(),input.path("currentPassword").asText()),operation),false);}
        finally{((ObjectNode)input).remove("currentPassword");}
    }
}
