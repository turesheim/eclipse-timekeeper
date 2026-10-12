package net.resheim.timekeeper.server;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

// All API resources are @Blocking; post-match authentication keeps registry I/O on workers.
@Provider @Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {
    @Inject TokenRegistry tokens;
    @Inject RequestIdentity identity;
    @Override public void filter(ContainerRequestContext request) {
        String path=request.getUriInfo().getPath();
        if(path.startsWith("/")) path=path.substring(1);
        if (!(path.equals("api/v1") || path.startsWith("api/v1/")) || path.equals("api/v1/openapi.json")) return;
        var headers=request.getHeaders().get("Authorization");
        if (headers==null || headers.size()!=1) throw new ApiFailure(401,"UNAUTHENTICATED");
        var principal=tokens.authenticate(headers.getFirst());
        var expected=request.getHeaders().get(OwnerBinding.HEADER);
        if(expected!=null) {
            if(expected.size()!=1) throw ApiFailure.badRequest();
            if(!principal.owner().equals(OwnerBinding.decode(expected.getFirst()))) throw new ApiFailure(401,"OWNER_CHANGED");
        }
        boolean read=java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        if (!principal.permissions().contains(read ? "read" : "write")) throw new ApiFailure(403,"FORBIDDEN");
        identity.set(principal);
    }
}
