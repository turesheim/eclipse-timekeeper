package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasskeyCeremoniesTest {
    @org.junit.jupiter.api.extension.RegisterExtension final PasskeyTestDatabase database=new PasskeyTestDatabase();
    @TempDir Path directory;
    private static final Instant NOW=Instant.parse("2026-10-17T00:00:00Z");
    private PasskeyCeremonies.Scope scope(String browser,String session) {return new PasskeyCeremonies.Scope(browser,session,"https://timekeeper.example:8443","timekeeper.example",7);}
    private String initialized() throws Exception {
        String url="jdbc:h2:"+directory.resolve("ceremonies");
        try(var c=database.connection(url)) {
            ServerSchema.initializeOrValidate(c);execute(c,"INSERT INTO tk_owner VALUES('alice','UTC',0,0,'cursor')");
            execute(c,"INSERT INTO tk_account(owner_id,username,password_hash,display_name,state,administrator,version) VALUES('alice','alice','hash','Alice','ENABLED',FALSE,0)");
        }return url;
    }
    @Test void restartAndConcurrentReplicasConsumeOnlyOnceAndNeverStoreBrowserOrChallengeSecrets() throws Exception {
        String url=initialized();var scope=scope(AccountPasswords.secret(),null);PasskeyCeremonies.Pending pending;
        try(var c=database.connection(url)) {
            c.setAutoCommit(false);lock(c);pending=new PasskeyCeremonies(c).create("LOGIN",null,scope,object().put("request","serialized library request"),NOW);c.commit();
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT digest,browser_digest,payload FROM tk_passkey_ceremony")) {
                rows.next();assertEquals(hash(pending.secret()),rows.getString(1));assertEquals(hash(scope.browserSecret()),rows.getString(2));
                assertFalse(rows.getString(3).contains(pending.secret()));assertFalse(rows.getString(3).contains(scope.browserSecret()));
            }
        }
        var executor=Executors.newFixedThreadPool(2);
        try {
            var ready=new CyclicBarrier(2);var tasks=new ArrayList<Callable<Boolean>>();
            for(int n=0;n<2;n++) tasks.add(()->{ready.await(5,TimeUnit.SECONDS);try(var c=database.connection(url)) {
                c.setAutoCommit(false);lock(c);
                try {new PasskeyCeremonies(c).claim(pending.secret(),"LOGIN",null,scope,NOW.plusSeconds(1));c.commit();return true;}
                catch(ApiFailure rejected) {c.rollback();assertEquals(401,rejected.status);return false;}
            }});
            var results=executor.invokeAll(tasks,15,TimeUnit.SECONDS);assertEquals(1,results.stream().filter(result->{try {return result.get();}catch(Exception failure) {throw new AssertionError(failure);}}).count());
        } finally {executor.shutdownNow();}
    }
    @Test void browserSessionPurposeOwnerOriginRpEpochAndExpiryAreBoundWithoutConsumingForeignFlows() throws Exception {
        String url=initialized();var scope=scope(AccountPasswords.secret(),AccountPasswords.secret());
        try(var c=database.connection(url)) {
            c.setAutoCommit(false);lock(c);var flows=new PasskeyCeremonies(c);var pending=flows.create("REGISTER","alice",scope,object().put("accountVersion",3),NOW);
            var wrong=List.of(scope(AccountPasswords.secret(),scope.sessionSecret()),scope(scope.browserSecret(),AccountPasswords.secret()),
                    new PasskeyCeremonies.Scope(scope.browserSecret(),scope.sessionSecret(),"https://timekeeper.example:9443","timekeeper.example",7),
                    new PasskeyCeremonies.Scope(scope.browserSecret(),scope.sessionSecret(),"https://new.example","new.example",8),
                    new PasskeyCeremonies.Scope(scope.browserSecret(),scope.sessionSecret(),scope.origin(),scope.rpId(),8));
            for(var context:wrong) assertEquals(401,assertThrows(ApiFailure.class,()->flows.claim(pending.secret(),"REGISTER","alice",context,NOW)).status);
            assertThrows(ApiFailure.class,()->flows.claim(pending.secret(),"MANAGE","alice",scope,NOW));assertThrows(ApiFailure.class,()->flows.claim(pending.secret(),"REGISTER","bob",scope,NOW));
            assertThrows(ApiFailure.class,()->flows.claim(pending.secret(),"REGISTER","alice",scope,NOW.plusSeconds(120)));
            assertEquals(3,flows.claim(pending.secret(),"REGISTER","alice",scope,NOW.plusSeconds(1)).data().path("accountVersion").asInt());c.commit();
            assertThrows(ApiFailure.class,()->flows.claim(pending.secret(),"REGISTER","alice",scope,NOW.plusSeconds(1)));
        }
    }
    @Test void browserCancellationBoundsAndCommittedCryptoRejectionCannotLeaveReusableAuthority() throws Exception {
        String url=initialized();var scope=scope(AccountPasswords.secret(),null);
        try(var c=database.connection(url)) {
            c.setAutoCommit(false);lock(c);var flows=new PasskeyCeremonies(c);var first=flows.create("LOGIN",null,scope,object(),NOW);
            for(int n=1;n<10;n++) flows.create("LOGIN",null,scope,object(),NOW);
            assertEquals(429,assertThrows(ApiFailure.class,()->flows.create("LOGIN",null,scope,object(),NOW)).status);
            flows.cancelBrowser(scope);c.commit();assertThrows(ApiFailure.class,()->flows.claim(first.secret(),"LOGIN",null,scope,NOW));
            var next=flows.create("LOGIN",null,scope,object(),NOW.plusSeconds(1));flows.claim(next.secret(),"LOGIN",null,scope,NOW.plusSeconds(2));
            // Simulate an expected rejected verification result: commit consumption,
            // then report failure outside the transaction instead of rolling it back.
            c.commit();assertThrows(ApiFailure.class,()->flows.claim(next.secret(),"LOGIN",null,scope,NOW.plusSeconds(3)));
        }
    }
}
