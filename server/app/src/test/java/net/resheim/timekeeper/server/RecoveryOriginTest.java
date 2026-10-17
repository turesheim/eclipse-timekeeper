package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(RecoveryOriginTest.Profile.class)
class RecoveryOriginTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:recovery_origin_tests");}
    }
    @Inject AccountStore accounts;

    @BeforeEach void clear() {
        accounts.tx(c->{lock(c);
            execute(c,"DELETE FROM tk_recovery_link");execute(c,"DELETE FROM tk_recovery_address");
            execute(c,"UPDATE tk_recovery_control SET origin=NULL,epoch=0 WHERE id=1");
            execute(c,"INSERT INTO tk_owner(id,zone_id,version,revision,cursor_secret) SELECT 'alice','Europe/Oslo',0,0,'test-cursor' WHERE NOT EXISTS (SELECT 1 FROM tk_owner WHERE id='alice')");
            ensureAccount(c,"alice");return null;
        });
    }

    private RecoveryOrigin replica(String origin,long epoch) {
        var value=new RecoveryOrigin();value.accounts=accounts;value.configured=origin;value.epoch=epoch;return value;
    }
    private void pending() {
        accounts.tx(c->{execute(c,"INSERT INTO tk_recovery_link VALUES('link-digest','alice','PRIMARY','alice@example.org','RESET',1,'2026-10-17T00:00:00Z','2026-10-17T00:15:00Z',FALSE)");return null;});
    }
    private boolean consumed() {
        return accounts.tx(c->{try(var query=query(c,"SELECT consumed FROM tk_recovery_link WHERE digest='link-digest'");var rows=query.executeQuery()) {assertTrue(rows.next());return rows.getBoolean(1);}catch(java.sql.SQLException failure) {throw new AssertionError(failure);}});
    }
    private RecoveryOrigin.Current current(RecoveryOrigin replica) {return accounts.tx(c->{lock(c);return replica.requireCurrent(c);});}

    @Test void validationRejectsRequestDestinationsAndRequiresHttpsWithoutPath() {
        for(String invalid:List.of("http://example.org","https://example.org/","https://example.org/reset","https://example.org?next=evil","https://example.org#token","https://user@example.org","https://example.org:0","https://example.org:65536","//example.org","https://example.org\r\nHost: evil"))
            assertThrows(IllegalArgumentException.class,()->RecoveryOrigin.validate(invalid),invalid);
        assertEquals("https://seshat.tailad1f0.ts.net:8443",RecoveryOrigin.validate("https://seshat.tailad1f0.ts.net:8443"));
        var optional=replica("NONE",1);optional.initialize();assertThrows(ApiFailure.class,()->current(optional));
    }

    @Test void cutoverInvalidatesOldLinksWithoutTouchingAccountOrDataAndRejectsStaleReplicas() {
        var vpn=replica("https://seshat.tailad1f0.ts.net:8443",1);vpn.initialize();pending();
        accounts.tx(c->{execute(c,"INSERT INTO tk_recovery_address VALUES('alice','PRIMARY','alice@example.org',NULL,7)");return null;});
        vpn.initialize();assertFalse(consumed(),"Restart must not invalidate links at the same epoch");
        assertThrows(IllegalStateException.class,()->replica("https://time.example.org",1).initialize());
        assertFalse(consumed(),"Rejected configuration must not mutate authentication state");
        var external=replica("https://time.example.org",2);external.initialize();assertTrue(consumed());
        assertEquals(2,current(external).epoch());assertThrows(ApiFailure.class,()->current(vpn));
        assertThrows(IllegalStateException.class,vpn::initialize,"An old replica cannot roll the origin back");
        accounts.tx(c->{try(var query=query(c,"SELECT verified_address,version FROM tk_recovery_address WHERE owner_id='alice'");var rows=query.executeQuery()) {assertTrue(rows.next());assertEquals("alice@example.org",rows.getString(1));assertEquals(7,rows.getLong(2));}catch(java.sql.SQLException failure) {throw new AssertionError(failure);}return null;});
        assertTrue(consumed());
        // An intentional rollback is a new cutover, never a decrease of the persisted epoch.
        var rollback=replica(vpn.configured,3);rollback.initialize();assertEquals(3,current(rollback).epoch());assertTrue(consumed());
    }

    @Test void sameHostnamePortChangeAlsoFencesOldLinksAndConcurrentReplicasAgree() throws Exception {
        var original=replica("https://time.example.org:8443",1);original.initialize();pending();
        var first=replica("https://time.example.org",2);var second=replica("https://time.example.org",2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(first::initialize);var b=pool.submit(second::initialize);a.get();b.get();
        }
        assertTrue(consumed());assertEquals(current(first),current(second));assertThrows(ApiFailure.class,()->current(original));
    }
}
