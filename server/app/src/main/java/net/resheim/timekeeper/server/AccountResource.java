package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import java.net.URI;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1/auth") @Blocking @Produces("application/json") @Consumes("application/json")
public class AccountResource {
    @Inject AccountStore accounts;
    @Inject ContractInputs inputs;
    @Inject RequestIdentity identity;
    @Inject BrowserOrigins origins;
    @Inject BrowserSignIns signIns;
    @Inject PasskeyRealm realm;
    @Inject ServerInfo serverInfo;
    @Inject RecoveryMail recoveryMail;
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    @Context io.vertx.core.http.HttpServerRequest request;
    private static String text(JsonNode input,String field,int limit) {
        var value=input.get(field);if(!value.isTextual() || value.asText().length()>limit) throw ApiFailure.validation();return value.asText();
    }
    private static boolean flag(JsonNode input,String field) {if(!input.get(field).isBoolean()) throw ApiFailure.validation();return input.get(field).asBoolean();}
    private void origin() {origins.check(uri,headers.getRequestHeader("Origin"));}
    private void browserOrigin() {origins.cookie(uri,headers.getRequestHeader("Origin"),headers.getRequestHeader("Referer"),true);}
    private BrowserCookies.Values cookies() {return BrowserCookies.read(headers.getRequestHeader("Cookie"));}
    private BrowserSignIns.Attempt begin(BrowserSignIns.Method method) {
        var cookies=cookies();String browser=origins.configured() ? cookies.requireBrowser() : null;
        var peer=request.connection().remoteAddress();
        return signIns.begin(method,browser,cookies.replacementSession(),peer==null ? "local" : peer.hostAddress());
    }
    private Response issued(AccountStore.Issued issued) {
        var principal=accounts.session(issued.secret());
        identity.set(principal);var value=accounts.context(principal);
        String cookie=AuthenticationFilter.COOKIE+"="+issued.secret()+"; Path=/; HttpOnly; Secure; SameSite=Lax";
        if(issued.persistent()) cookie+="; Max-Age="+Math.max(0,Duration.between(Instant.now(),issued.expires()).toSeconds());
        var reply=Response.ok(value).header("Cache-Control","no-store").header("Set-Cookie",cookie);
        if(origins.configured()) reply.header("Set-Cookie",BrowserCookies.bindingCookie(cookies().requireBrowser()));return reply.build();
    }
    @GET @Path("/status") public Response status() {
        ContractInputs.queries(uri.getQueryParameters());var result=object();
        result.put("setupRequired",accounts.status().path("setupRequired").asBoolean());
        result.put("spkVersion",serverInfo.spkVersion());result.put("emailRecoveryEnabled",recoveryMail.enabled());result.put("passkeysEnabled",realm.enabled());
        var reply=Response.ok(result).header("Cache-Control","no-store");var cookies=cookies();
        if(origins.configured() && (headers.getHeaderString("Origin")!=null || headers.getHeaderString("Referer")!=null)) {
            origins.cookie(uri,headers.getRequestHeader("Origin"),headers.getRequestHeader("Referer"),false);
            if(cookies.browser()==null) reply.header("Set-Cookie",BrowserCookies.bindingCookie(AccountPasswords.secret()));
        }
        return reply.build();
    }
    @POST @Path("/setup") public Response setup(JsonNode body) {return complete(body,false);}
    @POST @Path("/recover") public Response recover(JsonNode body) {return complete(body,true);}
    private Response complete(JsonNode body,boolean recovery) {
        browserOrigin();ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate(recovery ? "SetupInput" : "EmailSetupInput",body);
        var attempt=begin(recovery ? BrowserSignIns.Method.RECOVERY : BrowserSignIns.Method.SETUP);
        var result=accounts.complete(text(value,"code",100),text(value,"username",200),text(value,"password",1024),text(value,"displayName",400),recovery,recovery ? null : text(value,"email",320),signIns.issuer(attempt));
        if(!recovery) recoveryMail.initialVerification(result.account().path("ownerId").asText());
        return issued(result);
    }
    @POST @Path("/login") public Response login(JsonNode body) {
        browserOrigin();ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("LoginInput",body);
        var attempt=begin(BrowserSignIns.Method.PASSWORD);
        return issued(accounts.login(text(value,"username",200),text(value,"password",1024),flag(value,"remember"),signIns.issuer(attempt)));
    }
    @POST @Path("/token-login") @Consumes("*/*") public Response tokenLogin() {
        browserOrigin();ContractInputs.queries(uri.getQueryParameters());
        if(identity.principal().kind().equals("SESSION")) throw ApiFailure.badRequest();
        var attempt=begin(BrowserSignIns.Method.TOKEN);return issued(accounts.exchange(identity.principal(),signIns.issuer(attempt)));
    }
    @GET @Path("/session") public Response session() {ContractInputs.queries(uri.getQueryParameters());return Response.ok(accounts.context(identity.principal())).header("Cache-Control","no-store").build();}
    @POST @Path("/logout") @Consumes("*/*") public Response logout() {
        origin();ContractInputs.queries(uri.getQueryParameters());var cookies=cookies();
        if(identity.principal().kind().equals("SESSION")) signIns.logout(identity.principal(),origins.configured() ? cookies.requireBrowser() : null,cookies.replacementSession());
        else accounts.logout(identity.principal());
        var reply=Response.noContent().header("Cache-Control","no-store").header("Set-Cookie",AuthenticationFilter.COOKIE+"=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0");
        if(origins.configured()) reply.header("Set-Cookie",BrowserCookies.clearBindingCookie());return reply.build();
    }
    @GET @Path("/account") public Response account() {ContractInputs.queries(uri.getQueryParameters());return ApiReplies.resource(200,accounts.account(identity.principal()),null).response();}
    @PUT @Path("/account") public Response account(@HeaderParam("If-Match") String match,JsonNode body) {
        origin();ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("AccountInput",body);
        return ApiReplies.resource(200,accounts.updateSelf(identity.principal(),match,text(value,"username",200),text(value,"displayName",400)),null).response();
    }
    @POST @Path("/password") public Response password(JsonNode body) {
        origin();ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("PasswordInput",body);accounts.changePassword(identity.principal(),text(value,"currentPassword",1024),text(value,"newPassword",1024));
        return Response.noContent().header("Cache-Control","no-store").header("Set-Cookie",AuthenticationFilter.COOKIE+"=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0").build();
    }
    @GET @Path("/tokens") public JsonNode tokens() {ContractInputs.queries(uri.getQueryParameters());return accounts.tokens(identity.principal());}
    @POST @Path("/tokens") public Response token(JsonNode body) {
        origin();ContractInputs.queries(uri.getQueryParameters());var value=inputs.validate("TokenInput",body);
        if(!value.get("permissions").isArray()) throw ApiFailure.validation();var permissions=new HashSet<String>();
        for(var grant:value.get("permissions")) {if(!grant.isTextual() || !permissions.add(grant.asText())) throw ApiFailure.validation();}
        Instant expires=optionalText(value.get("expiresAt")).map(ContractInputs::instant).orElse(null);
        return Response.status(201).entity(accounts.createToken(identity.principal(),text(value,"name",400),permissions,expires)).header("Cache-Control","no-store").build();
    }
    @DELETE @Path("/tokens/{id}") public Response revoke(@PathParam("id") String id) {
        origin();ContractInputs.queries(uri.getQueryParameters());accounts.revokeToken(identity.principal(),ContractInputs.pathId(id));return Response.noContent().header("Cache-Control","no-store").build();
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
