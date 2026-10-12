package net.resheim.timekeeper.server;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider @Priority(Priorities.HEADER_DECORATOR)
public class OwnerResponseFilter implements ContainerResponseFilter {
    @Inject RequestIdentity identity;
    @Override public void filter(ContainerRequestContext request,ContainerResponseContext response) {
        identity.authenticatedOwner().ifPresent(owner->response.getHeaders().putSingle(OwnerBinding.HEADER,OwnerBinding.encode(owner)));
    }
}
