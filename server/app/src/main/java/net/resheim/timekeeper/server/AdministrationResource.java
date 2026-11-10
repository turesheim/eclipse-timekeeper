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
    @Inject ServiceSettings settings;
    @Inject InternetAccountSetup internetAccounts;
    @Inject NotesLimitSettings notesLimits;
    @Inject InstanceAdministrator administrators;
    @ConfigProperty(name="quarkus.datasource.db-kind",defaultValue="h2") String databaseType;
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    private static String text(JsonNode input,String field,int limit) {
        var value=input.get(field);if(!value.isTextual() || value.asText().length()>limit) throw ApiFailure.validation();return value.asText();
    }
    private static boolean flag(JsonNode input,String field) {if(!input.get(field).isBoolean()) throw ApiFailure.validation();return input.get(field).asBoolean();}
    private void origin() {origins.check(uri,headers.getRequestHeader("Origin"));}
    @GET @Path("/settings") public Response settings() {
        ContractInputs.queries(uri.getQueryParameters());return settingsReply(settings.get(identity.principal()));
    }
    @PUT @Path("/settings") public Response settings(@HeaderParam("If-Match") String match,JsonNode body) {
        origin();ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("AdministrationSettingsInput",body);
        return settingsReply(settings.save(identity.principal(),match,value));
    }
    private Response settingsReply(ServiceSettings.Reply reply) {
        reply.data().put("databaseType",databaseType);
        return Response.ok(reply.data()).header("ETag",reply.etag()).header("Cache-Control","no-store").build();
    }
    @GET @Path("/internet-accounts") public Response internetAccounts(@QueryParam("check") @DefaultValue("false") String check){
        ContractInputs.queries(uri.getQueryParameters(),"check");var reply=settings.internetSettings(identity.principal());
        if(!java.util.Set.of("true","false").contains(check))throw ApiFailure.validation();
        return internetAccountsReply(reply,check.equals("true"));
    }
    @PUT @Path("/internet-accounts") public Response internetAccounts(@HeaderParam("If-Match")String match,JsonNode body){
        origin();ContractInputs.queries(uri.getQueryParameters());var input=inputs.validate("AdministrationInternetAccountsInput",body);
        try{return internetAccountsReply(settings.saveInternet(administrators.verify(identity.principal(),input.path("currentPassword").asText()),match,input),false);}
        finally{((com.fasterxml.jackson.databind.node.ObjectNode)input).remove(java.util.List.of("currentPassword","googleClientSecret"));}
    }
    private Response internetAccountsReply(ServiceSettings.Reply reply,boolean check){
        reply.data().setAll(internetAccounts.inspect(check));return Response.ok(reply.data()).header("ETag",reply.etag()).header("Cache-Control","no-store").build();
    }
    @GET @Path("/notes/limits") public Response notesLimits(){
        ContractInputs.queries(uri.getQueryParameters());return notesLimitsReply(notesLimits.get(identity.principal()));
    }
    @PUT @Path("/notes/limits") public Response notesLimits(@HeaderParam("If-Match")String match,JsonNode body){
        origin();ContractInputs.queries(uri.getQueryParameters());var input=inputs.validate("AdministrationNotesLimitsInput",body);
        try{return notesLimitsReply(notesLimits.save(administrators.verify(identity.principal(),input.path("currentPassword").asText()),match,input.path("limits")));}
        finally{((com.fasterxml.jackson.databind.node.ObjectNode)input).remove("currentPassword");}
    }
    private Response notesLimitsReply(NotesLimitSettings.Reply reply){return Response.ok(reply.data()).header("ETag",reply.etag()).header("Cache-Control","no-store").build();}
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
