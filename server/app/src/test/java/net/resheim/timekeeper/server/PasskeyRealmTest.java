package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(PasskeyRealmTest.Profile.class)
class PasskeyRealmTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:passkey_realm_tests");}
    }
    @Inject AccountStore accounts;
    @Inject ServiceSettings settings;
    @BeforeEach void reset() {accounts.tx(c->{lock(c);execute(c,"DELETE FROM tk_passkey_ceremony");execute(c,"DELETE FROM tk_auth_session");execute(c,"UPDATE tk_recovery_control SET origin=NULL,epoch=0");return null;});}
    private PasskeyRealm realm(String address,long epoch) {
        var origin=new RecoveryOrigin();origin.accounts=accounts;origin.settings=settings;origin.configured=address;origin.epoch=epoch;
        var realm=new PasskeyRealm();realm.origin=origin;realm.settings=settings;realm.configuredEnabled=true;realm.configuredRp="AUTO";return realm;
    }
    @Test void canonicalRpConfigurationDoesNotTrustNetworkAddressesWildcardsOrUnrelatedDomains() {
        assertEquals(Optional.of("seshat.tailad1f0.ts.net"),PasskeyRealm.rp("https://seshat.tailad1f0.ts.net:8443","AUTO",true));
        assertEquals(Optional.of("localhost"),PasskeyRealm.rp("https://localhost:8443","localhost",true));
        assertTrue(PasskeyRealm.rp("NONE","AUTO",true).isEmpty());assertTrue(PasskeyRealm.rp("https://127.0.0.1:8443","AUTO",true).isEmpty());
        assertTrue(PasskeyRealm.rp("https://timekeeper.example","AUTO",false).isEmpty());
        for(String invalid:List.of("https://Timekeeper.example","https://timekeeper.example.","http://timekeeper.example","https://timekeeper.example/","https://timekeeper.example:443"))
            assertThrows(IllegalArgumentException.class,()->PasskeyRealm.rp(invalid,"AUTO",true),invalid);
        for(String invalid:List.of("*.example","example","different.example")) assertThrows(IllegalArgumentException.class,()->PasskeyRealm.rp("https://timekeeper.example",invalid,true));
        assertThrows(IllegalArgumentException.class,()->PasskeyRealm.rp("https://127.0.0.1:8443","127.0.0.1",true));
    }
    @Test void sameHostPortCutoverKeepsRpButCancelsFlowsAndRetiredSessionsEvenWithoutMail() {
        var old=realm("https://timekeeper.example:8443",1);old.initialize();
        accounts.tx(c->{lock(c);execute(c,"INSERT INTO tk_passkey_ceremony VALUES('old','browser','LOGIN',NULL,NULL,'{}','2026-10-18T00:00:00Z',FALSE)");
            execute(c,"INSERT INTO tk_owner(id,zone_id,version,revision,cursor_secret) VALUES('realm-owner','UTC',0,0,'cursor')");ensureAccount(c,"realm-owner");
            execute(c,"INSERT INTO tk_auth_session(digest,owner_id,csrf,permissions,created_at,expires_at,last_seen_at,persistent,origin_epoch) VALUES('old-session','realm-owner','csrf','READ_WRITE','2026-10-17T00:00:00Z','2026-10-18T00:00:00Z','2026-10-17T00:00:00Z',FALSE,1)");return null;});
        old.initialize();assertEquals(old.rpId(),realm("https://timekeeper.example",2).rpId());
        var replacement=realm("https://timekeeper.example",2);replacement.initialize();
        accounts.tx(c->{assertEquals(2,replacement.current(c).epoch());assertThrows(ApiFailure.class,()->old.current(c));
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT consumed FROM tk_passkey_ceremony WHERE digest='old'")) {rows.next();assertTrue(rows.getBoolean(1));}
            catch(java.sql.SQLException failure) {throw new AssertionError(failure);}
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM tk_auth_session")) {rows.next();assertEquals(0,rows.getInt(1));}
            catch(java.sql.SQLException failure) {throw new AssertionError(failure);}return null;});
        assertThrows(IllegalStateException.class,old::initialize);
    }
    @Test void newHostnameRequiresNewRpAndAnUnconfiguredReplicaCannotResumeOldBrowserAuthentication() {
        var unconfigured=realm("NONE",0);unconfigured.initialize();accounts.tx(c->{assertEquals(0,unconfigured.current(c).epoch());return null;});
        var vpn=realm("https://seshat.tailad1f0.ts.net:8443",1);vpn.initialize();
        assertThrows(IllegalStateException.class,()->realm("https://public.example",1).initialize());
        var replacement=realm("https://public.example",2);replacement.initialize();assertNotEquals(vpn.rpId(),replacement.rpId());
        accounts.tx(c->{assertEquals("https://public.example",replacement.current(c).origin());assertThrows(ApiFailure.class,()->vpn.current(c));assertThrows(ApiFailure.class,()->unconfigured.current(c));return null;});
        var invalid=realm("https://public.example",3);invalid.configuredRp="unrelated.example";assertThrows(IllegalArgumentException.class,invalid::initialize);
        accounts.tx(c->{assertEquals(2,replacement.current(c).epoch());return null;});
    }
}
