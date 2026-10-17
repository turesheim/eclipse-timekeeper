package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/v1/auth/passkeys") @Blocking @Produces("application/json") @Consumes("application/json")
public class PasskeyResource {
    @Inject PasskeyStore passkeys;
    @Inject BrowserSignIns signIns;
    @Inject BrowserOrigins origins;
    @Inject BrowserSessions sessions;
    @Inject ContractInputs inputs;
    @Inject RequestIdentity identity;
    @Context UriInfo uri;
    @Context HttpHeaders headers;
    @Context io.vertx.core.http.HttpServerRequest request;
    private BrowserCookies.Values boundary(boolean write) {
        if(!origins.configured()) throw new ApiFailure(503,"UNAVAILABLE");
        origins.cookie(uri,headers.getRequestHeader("Origin"),headers.getRequestHeader("Referer"),write);
        ContractInputs.queries(uri.getQueryParameters());var cookies=BrowserCookies.read(headers.getRequestHeader("Cookie"));cookies.requireBrowser();return cookies;
    }
    private JsonNode input(String schema,JsonNode body) {
        if(body!=null && body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>98304) throw ApiFailure.badRequest();
        return inputs.validate(schema,body);
    }
    private String peer() {var address=request.connection().remoteAddress();return address==null ? "local" : address.hostAddress();}
    private Response ok(JsonNode body) {return Response.ok(body).header("Cache-Control","no-store").build();}
    @POST @Path("/login/options") public Response loginOptions(JsonNode body) {
        var cookies=boundary(true);var value=input("PasskeyLoginInput",body);
        return ok(passkeys.loginOptions(cookies.requireBrowser(),cookies.replacementSession(),peer(),value.path("remember").asBoolean()));
    }
    @POST @Path("/login/finish") public Response login(JsonNode body) {
        var cookies=boundary(true);var value=input("PasskeyAssertionFinish",body);
        return sessions.issued(passkeys.login(cookies.requireBrowser(),cookies.replacementSession(),peer(),value),cookies);
    }
    @POST @Path("/cancel") public Response cancel(JsonNode body) {
        var cookies=boundary(true);input("PasskeyCancelInput",body);signIns.cancel(cookies.requireBrowser(),cookies.replacementSession());
        return Response.noContent().header("Cache-Control","no-store").build();
    }
    @GET public Response list() {boundary(false);return ok(passkeys.list(identity.principal()));}
    @POST @Path("/register/options") public Response registerOptions(JsonNode body) {
        var cookies=boundary(true);var value=input("PasskeyRegisterInput",body);
        return ok(passkeys.registerOptions(identity.principal(),cookies.requireBrowser(),cookies.session(),peer(),value));
    }
    @POST @Path("/register/finish") public Response register(JsonNode body) {
        var cookies=boundary(true);var value=input("PasskeyRegistrationFinish",body);
        return ApiReplies.resource(201,passkeys.register(identity.principal(),cookies.requireBrowser(),cookies.session(),peer(),value),null).response();
    }
    @POST @Path("/proof/options") public Response proofOptions(JsonNode body) {
        var cookies=boundary(true);var value=input("PasskeyActionInput",body);
        return ok(passkeys.managementOptions(identity.principal(),cookies.requireBrowser(),cookies.session(),peer(),value));
    }
    @POST @Path("/proof/finish") public Response proof(JsonNode body) {
        var cookies=boundary(true);var value=input("PasskeyAssertionFinish",body);
        return ok(passkeys.managementProof(identity.principal(),cookies.requireBrowser(),cookies.session(),peer(),value));
    }
    @PUT @Path("/{id}") public Response rename(@PathParam("id") String id,@HeaderParam("If-Match") String match,JsonNode body) {
        boundary(true);var value=input("PasskeyNameInput",body);
        return ApiReplies.resource(200,passkeys.rename(identity.principal(),ContractInputs.pathId(id),match,value.path("name").asText()),null).response();
    }
    @DELETE @Path("/{id}") public Response remove(@PathParam("id") String id,@HeaderParam("If-Match") String match,JsonNode body) {
        var cookies=boundary(true);var value=input("PasskeyProofInput",body);
        boolean signedOut=passkeys.remove(identity.principal(),ContractInputs.pathId(id),match,cookies.requireBrowser(),cookies.session(),peer(),value);
        var reply=Response.ok(object().put("signedOut",signedOut)).header("Cache-Control","no-store");
        return (signedOut ? sessions.clear(reply) : reply).build();
    }
}
