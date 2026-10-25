package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

@QuarkusTest @TestProfile(JournalImportTest.Profile.class)
class JournalAccountSecurityTest {
    @Inject ServerStore store;
    @Inject AccountStore accounts;
    @Inject TokenRegistry tokens;
    @Inject JournalAccountSecurity security;
    private static final String PASSWORD="Journal security fixture passphrase",ENCODED=AccountPasswords.hash(PASSWORD);
    private TokenRegistry.Principal principal;
    @BeforeEach void setup(){
        io.quarkus.arc.ClientProxy.unwrap(store).stopMaintenance();
        store.transaction("a",()->{
            store.update("UPDATE tk_account SET state='ENABLED',username='journal-proof-a',password_hash=? WHERE owner_id=?",ENCODED,store.owner());
            store.update("DELETE FROM tk_auth_throttle");return null;
        });
        var issued=accounts.login("journal-proof-a",PASSWORD,false);principal=tokens.session(issued.secret());
    }
    @Test void onlyDirectAccountSessionsCanAuthorizeJournalCredentialChanges(){
        assertDoesNotThrow(()->security.prove(principal,PASSWORD));
        var token=tokens.authenticate("Bearer test-a");assertEquals("JOURNAL_ACCOUNT_SIGN_IN_REQUIRED",assertThrows(ApiFailure.class,()->security.prove(token,PASSWORD)).code);
        var exchanged=accounts.exchange(token);var derived=tokens.session(exchanged.secret());
        assertEquals("JOURNAL_ACCOUNT_SIGN_IN_REQUIRED",assertThrows(ApiFailure.class,()->security.prove(derived,PASSWORD)).code);
    }
    @Test void wrongPasswordsCommitTheirThrottleAcrossSeparateProofOperations(){
        for(int i=0;i<5;i++)assertEquals("JOURNAL_PROOF_REQUIRED",assertThrows(ApiFailure.class,()->security.prove(principal,"wrong private password")).code);
        var blocked=assertThrows(ApiFailure.class,()->security.prove(principal,PASSWORD));assertEquals(429,blocked.status);assertEquals("RATE_LIMITED",blocked.code);assertNull(blocked.getCause());
    }
    @Test void revokedSessionsAndDisabledAccountsCannotUseAPreviousPasswordProof(){
        security.prove(principal,PASSWORD);accounts.logout(principal);assertEquals(401,assertThrows(ApiFailure.class,()->security.prove(principal,PASSWORD)).status);
        var newSession=accounts.login("journal-proof-a",PASSWORD,false);var active=tokens.session(newSession.secret());
        store.transaction("a",()->{store.update("UPDATE tk_account SET state='DISABLED' WHERE owner_id=?",store.owner());return null;});
        assertEquals(401,assertThrows(ApiFailure.class,()->security.prove(active,PASSWORD)).status);
    }
}
