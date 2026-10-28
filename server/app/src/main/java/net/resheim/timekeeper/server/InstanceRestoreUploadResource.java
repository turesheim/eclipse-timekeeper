package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.InputStream;
import java.util.Arrays;

@Path("/api/v1/admin/instance/uploads") @Blocking @Produces("application/json")
public class InstanceRestoreUploadResource {
    @Inject InstanceAdministrator administrators;
    @Inject InstanceRestoreUploads uploads;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Context UriInfo uri;
    private Response reply(int status,ObjectNode upload,boolean location){return new ApiReplies(status,upload,null,location?"/api/v1/admin/instance/uploads/"+upload.path("operation").path("id").asText():null).response();}
    @POST @Consumes("application/json") public Response create(JsonNode body) {
        ContractInputs.queries(uri.getQueryParameters());administrators.require(identity.principal());var input=inputs.validate("InstanceRestoreUploadInput",body);
        try{return reply(201,uploads.create(administrators.verify(identity.principal(),input.path("currentPassword").asText()),input.path("bytes").asLong(),input.path("sha256").asText()),true);}
        finally{((ObjectNode)input).remove("currentPassword");}
    }
    @GET @Path("/{id}") public Response status(@PathParam("id") String id) {
        ContractInputs.queries(uri.getQueryParameters());return reply(200,uploads.get(identity.principal(),ContractInputs.pathId(id)),false);
    }
    @PUT @Path("/{id}/content") @Consumes("application/octet-stream") public Response chunk(@PathParam("id") String id,InputStream body) {
        ContractInputs.queries(uri.getQueryParameters(),"offset");administrators.require(identity.principal());
        return reply(200,uploads.append(identity.principal(),ContractInputs.pathId(id),ContractInputs.sequence(uri.getQueryParameters().getFirst("offset")),body),false);
    }
    @POST @Path("/{id}/target") @Consumes("application/json") public Response target(@PathParam("id") String id,JsonNode body) {
        ContractInputs.queries(uri.getQueryParameters());administrators.require(identity.principal());var input=inputs.validate("InstanceOperationProofInput",body);
        try{return new ApiReplies(200,uploads.target(administrators.verify(identity.principal(),input.path("currentPassword").asText()),ContractInputs.pathId(id)),null,null).response();}
        finally{((ObjectNode)input).remove("currentPassword");}
    }
    @POST @Path("/{id}/validate") @Consumes("application/json") public Response validate(@PathParam("id") String id,JsonNode body) {
        ContractInputs.queries(uri.getQueryParameters());administrators.require(identity.principal());var input=inputs.validate("InstanceBackupCreateInput",body);char[] phrase=null;
        try{var proof=administrators.verify(identity.principal(),input.path("currentPassword").asText());phrase=input.path("passphrase").asText().toCharArray();return reply(202,uploads.startValidation(proof,ContractInputs.pathId(id),phrase),true);}
        finally{if(phrase!=null)Arrays.fill(phrase,'\0');((ObjectNode)input).remove(java.util.List.of("currentPassword","passphrase"));}
    }
}
