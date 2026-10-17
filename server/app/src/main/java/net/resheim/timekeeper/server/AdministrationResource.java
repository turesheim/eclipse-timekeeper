package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Service administration has its own namespace for reverse-proxy access policy. */
@Path("/api/v1/admin") @Blocking @Produces("application/json") @Consumes("application/json")
public class AdministrationResource {
    @Inject AccountStore accounts;
    @Inject RequestIdentity identity;
    @Inject ContractInputs inputs;
    @Inject BrowserOrigins origins;
    @Inject RecoveryMail recoveryMail;
    @Inject RecoveryOrigin origin;
    @Inject PasskeyRealm realm;
    @ConfigProperty(name="quarkus.datasource.db-kind",defaultValue="h2") String databaseType;
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    private static String text(JsonNode input,String field,int limit) {
        var value=input.get(field);if(!value.isTextual() || value.asText().length()>limit) throw ApiFailure.validation();return value.asText();
    }
    private static boolean flag(JsonNode input,String field) {if(!input.get(field).isBoolean()) throw ApiFailure.validation();return input.get(field).asBoolean();}
    private void origin() {origins.check(uri,headers.getRequestHeader("Origin"));}
    @GET @Path("/settings") public Response settings() {
        ContractInputs.queries(uri.getQueryParameters());accounts.requireAdministration(identity.principal());
        var value=object().put("configurationManagedBy","deployment").put("databaseType",databaseType).put("schemaVersion",ServerSchema.CURRENT)
                .put("publicOrigin",origin.configured().equals("NONE") ? null : origin.configured()).put("originEpoch",origin.epoch())
                .put("passkeysEnabled",realm.enabled()).put("passkeyRpId",realm.rpId().orElse(null)).put("emailRecoveryEnabled",recoveryMail.enabled());
        return Response.ok(value).header("Cache-Control","no-store").build();
    }
    @GET @Path("/users") public JsonNode users() {ContractInputs.queries(uri.getQueryParameters());return accounts.users(identity.principal());}
    @POST @Path("/users") public Response user(JsonNode body) {
        origin();ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("UserCreateInput",body);
        var user=accounts.createUser(identity.principal(),text(value,"username",200),text(value,"password",1024),text(value,"displayName",400),flag(value,"administrator"),text(value,"email",320));
        recoveryMail.initialVerification(user.path("ownerId").asText());return userReply(201,user);
    }
    private Response userReply(int status,JsonNode user) {var body=object();body.set("user",user);return new ApiReplies(status,body,etag(user),null).response();}
    @GET @Path("/users/{key}") public Response user(@PathParam("key") String key) {
        ContractInputs.queries(uri.getQueryParameters());return userReply(200,accounts.user(identity.principal(),OwnerBinding.decode(key)));
    }
    @PUT @Path("/users/{key}") public Response user(@PathParam("key") String key,@HeaderParam("If-Match") String match,JsonNode body) {
        origin();ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("UserInput",body);
        return userReply(200,accounts.updateUser(identity.principal(),OwnerBinding.decode(key),match,text(value,"username",200),text(value,"displayName",400),flag(value,"enabled"),flag(value,"administrator")));
    }
}
