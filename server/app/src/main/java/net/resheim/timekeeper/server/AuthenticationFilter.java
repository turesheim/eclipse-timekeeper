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
        if(java.util.Set.of("api/v1/auth/status","api/v1/auth/login","api/v1/auth/setup","api/v1/auth/recover").contains(path)) return;
        var headers=request.getHeaders().get("Authorization");
        String cookie=null;
        for(String header:request.getHeaders().getOrDefault("Cookie",java.util.List.of())) for(String part:header.split(";")) {
            var pair=part.strip().split("=",2);if(pair[0].equals(COOKIE)) {
                if(cookie!=null || pair.length!=2) throw ApiFailure.badRequest();cookie=pair[1];
            }
        }
        // Explicit token exchange replaces the browser session; other routes reject ambiguous credentials.
        if(headers!=null && cookie!=null && !path.equals("api/v1/auth/token-login")) throw ApiFailure.badRequest();
        TokenRegistry.Principal principal;
        if(headers!=null) {
            if(headers.size()!=1) throw ApiFailure.unauthorized(true);principal=tokens.authenticate(headers.getFirst());
        } else principal=tokens.session(cookie);
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
