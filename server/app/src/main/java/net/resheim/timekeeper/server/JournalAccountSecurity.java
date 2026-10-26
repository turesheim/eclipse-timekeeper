package net.resheim.timekeeper.server;

import java.time.Duration;
import java.time.Instant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Fresh password proof for journal credentials, with failure throttles committed independently. */
@ApplicationScoped
class JournalAccountSecurity {
    @Inject AccountStore accounts;
    void prove(TokenRegistry.Principal principal,String password){
        if(password==null||password.length()>1024)throw ApiFailure.validation();
        String failure=accounts.tx(connection->{
            AccountStore.lock(connection);accounts.guard(connection,principal);
            if(!AccountStore.accountSession(connection,principal))return "JOURNAL_ACCOUNT_SIGN_IN_REQUIRED";
            Instant now=Instant.now();
            boolean global=AccountStore.attempt(connection,"journal:proof:global",120,Duration.ofMinutes(1),now);
            boolean owner=AccountStore.attempt(connection,"journal:proof:"+principal.owner(),5,Duration.ofMinutes(5),now);
            if(!global||!owner)return "RATE_LIMITED";
            return AccountStore.currentPassword(connection,principal.owner(),password)?null:"JOURNAL_PROOF_REQUIRED";
        });
        // Throw after commit: a wrong password must not roll back its throttle entry.
        if(failure!=null)throw new ApiFailure(failure.equals("RATE_LIMITED")?429:403,failure);
    }
}
