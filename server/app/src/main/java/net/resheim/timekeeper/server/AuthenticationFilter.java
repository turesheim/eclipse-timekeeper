package net.resheim.timekeeper.server;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.core.Context;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

@Provider @Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {
    static final String COOKIE="__Host-timekeeper-session";
    @Inject TokenRegistry tokens;
    @Inject AccountStore accounts;
    @Inject BrowserOrigins origins;
    @Inject RequestIdentity identity;
    @Context io.vertx.core.http.HttpServerRequest serverRequest;
    @Override public void filter(ContainerRequestContext request) {
        String path=request.getUriInfo().getPath();if(path.startsWith("/")) path=path.substring(1);
        if(path.equals("dav/journal")||path.equals("dav/journal/")||path.startsWith("dav/journal/")){
            dav(request);return;
        }
        if (!(path.equals("api/v1") || path.startsWith("api/v1/")) || path.equals("api/v1/openapi.json")) return;
        if(java.util.Set.of("api/v1/auth/status","api/v1/auth/login","api/v1/auth/setup","api/v1/auth/recover",
                "api/v1/auth/email/forgot","api/v1/auth/email/preview","api/v1/auth/email/verify","api/v1/auth/email/reset",
                "api/v1/auth/passkeys/login/options","api/v1/auth/passkeys/login/finish","api/v1/auth/passkeys/cancel").contains(path)) return;
        var headers=request.getHeaders().get("Authorization");
        var cookies=BrowserCookies.read(request.getHeaders().get("Cookie"));String cookie=path.equals("api/v1/auth/token-login") ? cookies.replacementSession() : cookies.session();
        // Explicit token exchange replaces the browser session; other routes reject ambiguous credentials.
        if(headers!=null && cookie!=null && !path.equals("api/v1/auth/token-login")) throw ApiFailure.badRequest();
        TokenRegistry.Principal principal;
        if(headers!=null) {
            if(headers.size()!=1) throw ApiFailure.unauthorized(true);principal=tokens.authenticate(headers.getFirst());
        } else {
            origins.cookie(request.getUriInfo(),request.getHeaders().get("Origin"),request.getHeaders().get("Referer"),!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()));
            principal=tokens.session(cookie,cookies.browser());
        }
        var expected=request.getHeaders().get(OwnerBinding.HEADER);
        if(expected!=null) {
            if(expected.size()!=1) throw ApiFailure.badRequest();
            if(!principal.owner().equals(OwnerBinding.decode(expected.getFirst()))) throw new ApiFailure(401,"OWNER_CHANGED");
        }
        boolean read=java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())
                || java.util.Set.of("api/v1/auth/logout","api/v1/auth/token-login").contains(path);
        String permission=read ? "read" : "write";
        if(!principal.permissions().contains(permission)) throw new ApiFailure(403,"FORBIDDEN");
        if(principal.kind().equals("SESSION") && !java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
            origins.check(request.getUriInfo(),request.getHeaders().get("Origin"));
            accounts.csrf(principal,request.getHeaderString("X-CSRF-Token"));
        }
        identity.set(principal);identity.permission(permission);
    }
    private void dav(ContainerRequestContext request){
        var uri=request.getUriInfo().getRequestUri();
        boolean loopback=serverRequest!=null&&serverRequest.connection().remoteAddress()!=null
                &&Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(serverRequest.connection().remoteAddress().hostAddress());
        String publicOrigin=origins.currentOrigin();
        // A TLS reverse proxy may forward plain HTTP across the private server
        // network. Its configured public HTTPS authority is the trust boundary;
        // deployments must restrict direct access to that backend network.
        boolean proxy=false;
        if(publicOrigin!=null&&!publicOrigin.equals("NONE")){
            try{var origin=URI.create(publicOrigin);proxy="https".equalsIgnoreCase(origin.getScheme())
                    &&origin.getRawAuthority()!=null&&origin.getRawAuthority().equalsIgnoreCase(uri.getRawAuthority());}
            catch(IllegalArgumentException ignored){/* Invalid deployment origin cannot grant HTTP access. */}
        }
        if(!"https".equalsIgnoreCase(uri.getScheme())
                &&!("http".equalsIgnoreCase(uri.getScheme())
                    &&((loopback&&Set.of("localhost","127.0.0.1","::1").contains(uri.getHost()))||proxy)))
            throw new ApiFailure(403,"JOURNAL_HTTPS_REQUIRED");
        var headers=request.getHeaders().get("Authorization");
        if(headers==null||headers.size()!=1||!headers.getFirst().regionMatches(true,0,"Basic ",0,6)||headers.getFirst().length()>5500)
            throw ApiFailure.unauthorized(false);
        final String credentials;
        try{
            var bytes=Base64.getDecoder().decode(headers.getFirst().substring(6));
            credentials=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }catch(Exception invalid){throw ApiFailure.unauthorized(false);}
        int separator=credentials.indexOf(':');
        if(separator<0||separator==credentials.length()-1)throw ApiFailure.unauthorized(false);
        // The token is the authority; WebDAV usernames are client-side labels only.
        var principal=tokens.authenticate("Bearer "+credentials.substring(separator+1));
        String method=request.getMethod();
        String permission=Set.of("GET","HEAD","OPTIONS","PROPFIND").contains(method)?"read":"write";
        if(!principal.permissions().contains(permission))throw new ApiFailure(403,"FORBIDDEN");
        identity.set(principal);identity.permission(permission);
    }
}
