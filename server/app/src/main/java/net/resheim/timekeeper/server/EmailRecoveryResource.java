package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/auth/email") @Blocking @Produces("application/json") @Consumes("application/json")
public class EmailRecoveryResource {
    @Inject EmailRecoveryStore recovery;
    @Inject RecoveryMail mail;
    @Inject BrowserOrigins origins;
    @Inject ContractInputs inputs;
    @Inject RequestIdentity identity;
    @Context UriInfo uri;
    @Context HttpHeaders headers;

    private JsonNode input(String schema,JsonNode body) {
        origins.check(uri,headers.getRequestHeader("Origin"));ContractInputs.queries(uri.getQueryParameters());return inputs.validate(schema,body);
    }
    private Response empty(int status) {return Response.status(status).header("Cache-Control","no-store").build();}
    private void enabled() {if(!mail.enabled()) throw new ApiFailure(503,"UNAVAILABLE");}

    @POST @Path("/forgot") public Response forgot(JsonNode body) {
        var value=input("EmailForgotInput",body);mail.requestReset(value.get("username").asText());
        // Eligibility, lookup, queue capacity and SMTP outcomes never change the public reply.
        var reply=object();reply.put("message","If this account has verified recovery email, a reset link will be requested. If no email arrives, contact the server operator.");
        return Response.accepted(reply).header("Cache-Control","no-store").build();
    }
    @POST @Path("/preview") public Response preview(JsonNode body) {
        var value=input("EmailLinkInput",body);enabled();recovery.preview(value.get("token").asText(),value.get("purpose").asText());return empty(204);
    }
    @POST @Path("/verify") public Response verify(JsonNode body) {
        var value=input("EmailTokenInput",body);enabled();var result=recovery.verify(value.get("token").asText());
        mail.notify(result.notifyAddresses(),"A Timekeeper recovery email address was verified or replaced. If you did not make this change, contact your server operator.");return empty(204);
    }
    @POST @Path("/reset") public Response reset(JsonNode body) {
        var value=input("EmailResetInput",body);enabled();var result=recovery.reset(value.get("token").asText(),value.get("password").asText());
        mail.notify(result.notifyAddresses(),"Your Timekeeper password was reset. All passkeys, browser sessions and API tokens were revoked. Sign in with your new password and add replacement passkeys in My Account. Eclipse connections need new API tokens. If you did not do this, contact your server operator.");return empty(204);
    }
    @GET @Path("/addresses/{slot}") public Response address(@PathParam("slot") String slot) {
        ContractInputs.queries(uri.getQueryParameters());return ApiReplies.resource(200,recovery.address(identity.principal(),slot),null).response();
    }
    @PUT @Path("/addresses/{slot}") public Response address(@PathParam("slot") String slot,@HeaderParam("If-Match") String match,JsonNode body) {
        var value=input("RecoveryAddressInput",body);enabled();var result=recovery.change(identity.principal(),slot,optionalText(value.get("email")).orElse(null),value.get("currentPassword").asText(),match);
        mail.deliver(result.deliveries());mail.notify(result.notifyAddresses(),"A change to your Timekeeper recovery email addresses was requested. Existing verified addresses remain active until replacement verification completes. If you did not request this, contact your server operator.");
        return ApiReplies.resource(200,recovery.address(identity.principal(),slot),null).response();
    }
    @POST @Path("/addresses/{slot}/resend") public Response resend(@PathParam("slot") String slot,JsonNode body) {
        input("EmailResendInput",body);enabled();mail.deliver(recovery.resend(identity.principal(),slot));
        return Response.accepted(object()).header("Cache-Control","no-store").build();
    }
}
