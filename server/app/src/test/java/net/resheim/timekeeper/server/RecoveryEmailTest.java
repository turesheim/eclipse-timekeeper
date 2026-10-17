package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryEmailTest {
    @Test void normalizesDomainAndPreservesMailboxWithoutAcceptingHeaderInjection() {
        assertEquals("Alice+time@example.org",RecoveryEmail.normalize(" Alice+time@EXAMPLE.ORG "));
        assertEquals("alice@xn--blbr-roah.no",RecoveryEmail.normalize("alice@blåbær.no"));
        assertTrue(RecoveryEmail.same("Alice@example.org","alice@EXAMPLE.ORG"));
        for(String invalid:List.of("alice", "alice@@example.org", ".alice@example.org", "alice..smith@example.org", "alice@example.org\r\nBcc: attacker@example.org", "Alice <alice@example.org>", "alice@localhost", "alice@exam ple.org", "alice@.example.org")) assertThrows(ApiFailure.class,()->RecoveryEmail.normalize(invalid),invalid);
    }
}
