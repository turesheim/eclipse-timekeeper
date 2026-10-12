package net.resheim.timekeeper.server;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class RequestIdentity {
    private TokenRegistry.Principal principal;
    public void set(TokenRegistry.Principal value) { principal=value; }
    public String owner() { if (principal==null) throw new ApiFailure(401,"UNAUTHENTICATED"); return principal.owner(); }
}
