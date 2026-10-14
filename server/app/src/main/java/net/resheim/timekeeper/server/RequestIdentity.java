package net.resheim.timekeeper.server;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.sql.Connection;

@RequestScoped
public class RequestIdentity {
    @Inject AccountStore accounts;
    @Inject TokenRegistry tokens;
    private TokenRegistry.Principal principal;
    private String permission="read";
    public void set(TokenRegistry.Principal value) { principal=value; }
    public void permission(String value) {permission=value;}
    public TokenRegistry.Principal principal() {if(principal==null) throw ApiFailure.unauthorized(false);return principal;}
    public void guard(Connection connection) {
        principal=tokens.recheck(principal());
        if(!principal.permissions().contains(permission)) throw new ApiFailure(403,"FORBIDDEN");
        accounts.guard(connection,principal);
    }
    public java.util.Optional<String> authenticatedOwner() {return principal==null ? java.util.Optional.empty() : java.util.Optional.of(principal.owner());}
    public String owner() {return principal().owner();}
}
