package net.resheim.timekeeper.server;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

@Provider @Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {
    static final String COOKIE="__Host-timekeeper-session";
    @Inject TokenRegistry tokens;
    @Inject AccountStore accounts;
    @Inject BrowserOrigins origins;
    @Inject RequestIdentity identity;
    @Override public void filter(ContainerRequestContext request) {
        String path=request.getUriInfo().getPath();if(path.startsWith("/")) path=path.substring(1);
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
}
