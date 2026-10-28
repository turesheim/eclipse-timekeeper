package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.hash;
import java.sql.Connection;
import java.time.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Fresh browser-account authority; bearer tokens can never gain this privilege. */
@ApplicationScoped
class InstanceAdministrator {
    @Inject AccountStore accounts;
    @Inject BackendClock clock;
    static final class Proof {
        private final TokenRegistry.Principal principal;private final Instant verifiedAt;
        private Proof(TokenRegistry.Principal principal,Instant verifiedAt){this.principal=principal;this.verifiedAt=verifiedAt;}
        TokenRegistry.Principal principal(){return principal;}
        @Override public String toString(){return "Private instance-operation authority";}
    }
    void require(TokenRegistry.Principal principal) {accounts.tx(c->{lock(c);check(c,principal);return null;});}
    void check(Connection connection,TokenRegistry.Principal principal) {
        if(principal==null||!principal.kind().equals("SESSION")||!principal.permissions().contains("write"))throw new ApiFailure(403,"FORBIDDEN");
        accounts.guard(connection,principal);requireAdmin(connection,principal);
        if(!accountSession(connection,principal))throw new ApiFailure(403,"FORBIDDEN");
    }
    Proof verify(TokenRegistry.Principal principal,String password) {
        String key="instance-backup:proof:"+(principal==null?"":principal.owner());
        boolean allowed=accounts.tx(c->{lock(c);check(c,principal);return attempt(c,key,5,Duration.ofMinutes(5),clock.now());});
        if(!allowed)throw new ApiFailure(429,"RATE_LIMITED");
        boolean verified=accounts.tx(c->{
            lock(c);check(c,principal);
            if(password==null||password.length()>1024||!currentPassword(c,principal.owner(),password))return false;
            execute(c,"DELETE FROM tk_auth_throttle WHERE id=?",hash(key));return true;
        });
        if(!verified)throw new ApiFailure(422,"INSTANCE_PROOF_REQUIRED");return new Proof(principal,clock.now());
    }
    void requireFresh(Proof proof) {
        if(proof==null||proof.verifiedAt.isBefore(clock.now().minusSeconds(300))||proof.verifiedAt.isAfter(clock.now().plusSeconds(5)))throw new ApiFailure(422,"INSTANCE_PROOF_REQUIRED");
        require(proof.principal);
    }
}
