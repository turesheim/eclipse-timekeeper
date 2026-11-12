package net.resheim.timekeeper.connect;

import static net.resheim.timekeeper.connect.ConnectProtocol.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** One registered OAuth client, with a PKCE-bound backchannel for each Dagsverk installation. */
@Path("/") @Blocking
public class ConnectResource {
    @Inject ConnectSettings settings;
    @Inject ConnectStore store;
    @Inject ConnectProvider providers;
    @Inject ConnectLimits limits;
    @Context HttpHeaders headers;
    @Context io.vertx.core.http.HttpServerRequest request;
    private static long now(){return Instant.now().getEpochSecond();}

    @GET @Path("/v1/providers") @Produces(MediaType.APPLICATION_JSON)
    public ObjectNode providers(){store.ready();return object().put("protocol",1).put("google",settings.available("google")).put("microsoft",settings.available("microsoft"));}

    @POST @Path("/v1/requests") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public ObjectNode create(JsonNode input){
        var peer=request.connection().remoteAddress();limits.acquire(peer==null?"local":peer.hostAddress());
        fields(input,"provider","returnUri","state","challenge","services","language");String provider=provider(text(input,"provider",20));settings.app(provider);
        URI target=callback(text(input,"returnUri",8192));String state=opaque(input,"state"),challenge=opaque(input,"challenge");var services=Services.read(input.get("services"));ConnectProvider.scopes(provider,services);
        String language=input.path("language").asText("en");if(!List.of("en","nb").contains(language))throw new ConnectFailure(400,"INVALID_REQUEST");
        store.clean();if(store.count("FLOW")>=10000)throw new ConnectFailure(429,"TRY_LATER");
        String id=random(),oauthState=random();long expires=now()+600;
        var value=object().put("provider",provider).put("returnUri",target.toString()).put("state",state).put("challenge",challenge)
                .put("phase","CREATED").put("oauthState",oauthState).put("verifier",random()).put("language",language);value.set("services",services.json());
        store.insert("flow:"+id,"FLOW",expires,value);store.insert("oauth:"+hash(oauthState),"OAUTH",expires,object().put("requestId",id));
        return object().put("authorizationUrl",settings.origin()+"/connect/"+id).put("expiresAt",Instant.ofEpochSecond(expires).toString());
    }

    @GET @Path("/connect/{id}") @Produces(MediaType.TEXT_HTML)
    public Response consent(@PathParam("id")String id){
        var row=flow(id);var value=row.value();String browser=cookie(id);
        if(value.path("phase").asText().equals("CREATED")){
            browser=random();value.put("browserHash",hash(browser)).put("nonce",random()).put("phase","CONSENT");row=store.save(row,value);
        }else if(!value.path("phase").asText().equals("CONSENT")||browser==null||!equal(value.path("browserHash").asText(),hash(browser)))throw new ConnectFailure(409,"AUTHORIZATION_CHANGED");
        boolean nb=value.path("language").asText().equals("nb");String provider=value.path("provider").asText().equals("google")?"Google":"Microsoft";
        String target=origin(value.path("returnUri").asText().replace("/web/task-provider-callback.html","")).toString();var services=Services.read(value.get("services"));
        String title=nb?"Koble til "+provider:"Connect "+provider;
        String body="<!doctype html><html lang=\""+(nb?"nb":"en")+"\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>"+html(title)+" · Dagsverk</title><link rel=\"stylesheet\" href=\"/connect.css\"><main><h1>"+html(title)+"</h1><p>"+(nb?"Du kobler kontoen til denne Dagsverk-installasjonen:":"You are connecting your account to this Dagsverk installation:")+"</p><p class=\"destination\">"+html(target)+"</p><ul>"
                +(services.calendars()?"<li>"+(nb?"Les kalenderhendelser":"Read calendar events")+"</li>":"")
                +(services.tasks()?"<li>"+(services.writable()?(nb?"Les og gjennomgå endringer i oppgaver":"Read tasks and review outgoing changes"):(nb?"Les oppgaver":"Read tasks"))+"</li>":"")
                +"</ul><p>"+(nb?"Fortsett bare hvis dette er Dagsverk-installasjonen du vil bruke. Du velger kalendere og lister etter pålogging.":"Continue only if this is the Dagsverk installation you intend to use. You choose calendars and lists after signing in.")
                +"</p><form method=\"post\" action=\"/connect/"+id+"\"><input type=\"hidden\" name=\"nonce\" value=\""+value.path("nonce").asText()+"\"><button type=\"submit\">"+(nb?"Fortsett til ":"Continue to ")+provider+"</button></form></main></html>";
        return Response.ok(body).header("Set-Cookie",setCookie(id,browser,600)).build();
    }

    @POST @Path("/connect/{id}") @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response authorize(@PathParam("id")String id,@FormParam("nonce")String nonce){
        if(!equal(headers.getHeaderString("Origin"),settings.origin().toString()))throw new ConnectFailure(403,"BROWSER_CHANGED");
        var row=flow(id);var value=row.value();checkBrowser(id,value);
        if(!value.path("phase").asText().equals("CONSENT")||!equal(value.path("nonce").asText(),nonce))throw new ConnectFailure(409,"AUTHORIZATION_CHANGED");
        value.put("phase","STARTED");store.save(row,value);
        return Response.seeOther(URI.create(providers.authorize(value.path("provider").asText(),settings.app(value.path("provider").asText()),Services.read(value.get("services")),value.path("oauthState").asText(),value.path("verifier").asText()))).build();
    }

    @GET @Path("/oauth/{provider}/callback")
    public Response complete(@PathParam("provider")String kind,@QueryParam("state")String state,@QueryParam("code")String code,@QueryParam("error")String error){
        provider(kind);if(state==null||!state.matches("[A-Za-z0-9_-]{43}"))throw new ConnectFailure(400,"INVALID_REQUEST");
        var index=store.get("oauth:"+hash(state));String id=index.value().path("requestId").asText();var row=flow(id);var value=row.value();checkBrowser(id,value);
        if(!value.path("provider").asText().equals(kind)||!value.path("phase").asText().equals("STARTED")||!equal(value.path("oauthState").asText(),state))throw new ConnectFailure(409,"AUTHORIZATION_CHANGED");
        if(error!=null){store.delete(index);store.delete(row);return back(id,value,"error","access_denied");}
        if(code==null||code.isBlank()||code.length()>8192||code.codePoints().anyMatch(Character::isISOControl))throw new ConnectFailure(400,"INVALID_REQUEST");
        value.put("phase","EXCHANGING");row=store.save(row,value);store.delete(index);
        final ObjectNode tokens;
        try{tokens=providers.exchange(kind,settings.app(kind),Services.read(value.get("services")),code,value.path("verifier").asText());}
        catch(ConnectFailure|net.resheim.timekeeper.server.TaskProviderHttp.Failure failure){store.delete(row);return back(id,value,"error","sign_in_failed");}
        String handoff=random();var delivered=object().put("provider",kind).put("challenge",value.path("challenge").asText());delivered.set("tokens",tokens);delivered.set("services",value.get("services"));
        store.insert("exchange:"+hash(handoff),"EXCHANGE",now()+300,delivered);store.delete(row);return back(id,value,"code",handoff);
    }

    @POST @Path("/v1/exchanges") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public ObjectNode redeem(JsonNode input){
        fields(input,"code","verifier");String code=opaque(input,"code"),verifier=text(input,"verifier",128);
        if(!verifier.matches("[A-Za-z0-9._~-]{43,128}"))throw new ConnectFailure(400,"INVALID_REQUEST");
        var row=store.get("exchange:"+hash(code));var value=row.value();
        if(!equal(value.path("challenge").asText(),hash(verifier)))throw new ConnectFailure(403,"INSTALLATION_CHANGED");
        String credential=random();var grant=value.deepCopy();grant.remove("challenge");grant.put("busyUntil",0);
        store.redeem(row,"grant:"+hash(credential),grant,now()+180L*86400);return reply(grant).put("grantCredential",credential);
    }

    @POST @Path("/v1/grants/refresh") @Produces(MediaType.APPLICATION_JSON)
    public ObjectNode refresh(@HeaderParam("Authorization")String authorization){
        var row=grant(authorization);var value=row.value();if(value.path("blocked").asBoolean())throw new ConnectFailure(409,"RECONNECT_REQUIRED");
        if(Instant.parse(value.path("tokens").path("expiresAt").asText()).isAfter(Instant.now().plusSeconds(60)))return reply(value);
        if(value.path("busyUntil").asLong()>now())throw new ConnectFailure(429,"TRY_LATER");
        value.put("busyUntil",now()+60);var leased=store.save(row,value);
        try{
            value.set("tokens",providers.refresh(value.path("provider").asText(),settings.app(value.path("provider").asText()),Services.read(value.get("services")),value.get("tokens")));
            value.put("busyUntil",0);store.save(leased,value,now()+180L*86400);return reply(value);
        }catch(RuntimeException failure){
            value.put("busyUntil",0);if(failure instanceof net.resheim.timekeeper.server.TaskProviderHttp.Failure providerFailure&&(providerFailure.status==401||providerFailure.status==403))value.put("blocked",true);
            if(failure instanceof ConnectFailure expected&&Set.of("RECONNECT_REQUIRED","PERMISSION_REQUIRED","OFFLINE_ACCESS_REQUIRED").contains(expected.code))value.put("blocked",true);
            store.save(leased,value);throw failure;
        }
    }

    @DELETE @Path("/v1/grants/current")
    public Response disconnect(@HeaderParam("Authorization")String authorization){store.delete(grant(authorization));return Response.noContent().build();}

    @GET @Path("/connect.css") @Produces("text/css")
    public String css(){return "body{font:1rem/1.5 system-ui,sans-serif;margin:0;background:#fff;color:#111}main{max-width:36rem;margin:5vh auto;padding:1.5rem}.destination{font-weight:bold;overflow-wrap:anywhere;border:1px solid;padding:1rem}button{font:inherit;padding:.7rem 1rem;border:2px solid;border-radius:.3rem;background:#111;color:#fff;cursor:pointer}button:focus-visible{outline:3px solid;outline-offset:3px}";}

    private ConnectStore.Row flow(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{43}"))throw new ConnectFailure(404,"NOT_FOUND");return store.get("flow:"+id);}
    private ConnectStore.Row grant(String authorization){
        if(authorization==null||!authorization.matches("Bearer [A-Za-z0-9_-]{43}"))throw new ConnectFailure(401,"INVALID_CREDENTIAL");
        try{return store.get("grant:"+hash(authorization.substring(7)));}catch(ConnectFailure missing){if(missing.status==404||missing.status==410)throw new ConnectFailure(401,"RECONNECT_REQUIRED");throw missing;}
    }
    private ObjectNode reply(JsonNode grant){
        var result=object().put("provider",grant.path("provider").asText());result.set("services",grant.path("services"));var tokens=grant.path("tokens");
        for(String field:List.of("accessToken","expiresAt","scopes","remoteAccountId","name"))result.set(field,tokens.path(field));return result;
    }
    private String cookieName(String id){return (settings.origin().getScheme().equals("https")?"__Host-":"")+"dagsverk-connect-"+id;}
    private String cookie(String id){var cookie=headers.getCookies().get(cookieName(id));return cookie==null?null:cookie.getValue();}
    private void checkBrowser(String id,JsonNode value){String browser=cookie(id);if(browser==null||!browser.matches("[A-Za-z0-9_-]{43}")||!equal(value.path("browserHash").asText(),hash(browser)))throw new ConnectFailure(403,"BROWSER_CHANGED");}
    private String setCookie(String id,String value,int age){return cookieName(id)+"="+value+"; Path=/; HttpOnly; SameSite=Lax; Max-Age="+age+(settings.origin().getScheme().equals("https")?"; Secure":"");}
    private Response back(String id,JsonNode value,String key,String result){return Response.seeOther(URI.create(value.path("returnUri").asText()+"?"+form(Map.of("state",value.path("state").asText(),key,result)))).header("Set-Cookie",setCookie(id,"",0)).build();}
}
