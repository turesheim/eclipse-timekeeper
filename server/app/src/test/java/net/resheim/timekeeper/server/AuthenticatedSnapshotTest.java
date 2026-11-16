package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.Map;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(AuthenticatedSnapshotTest.Profile.class)
class AuthenticatedSnapshotTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("authenticatedsnapshots","jdbc:h2:mem:authenticated_snapshots");}
    }
    @Inject ServerStore store;
    @Inject AccountStore accounts;
    @Inject TokenRegistry tokens;

    private RequestIdentity identity(TokenRegistry.Principal principal){
        var identity=new RequestIdentity();identity.accounts=accounts;identity.tokens=tokens;identity.set(principal);
        store.authenticated(identity,()->store.profile());return identity;
    }
    @Test void revocationAndAccountDisableDuringAReadDoNotReturnThePrivateResult(){
        var principal=tokens.authenticate("Bearer test-read");
        for(boolean disable:new boolean[]{false,true}){
            var identity=identity(principal);var read=new java.util.concurrent.atomic.AtomicBoolean();
            try{
                var failure=assertThrows(ApiFailure.class,()->store.authenticatedReadSnapshot(identity,()->{
                    var result=store.profile();read.set(true);
                    accounts.tx(c->{if(disable)AccountStore.execute(c,"UPDATE tk_account SET state='DISABLED',username='snapshot-disabled',password_hash='unused-disabled-test' WHERE owner_id=?",principal.owner());
                        else AccountStore.execute(c,"UPDATE tk_api_token SET revoked=TRUE WHERE digest=?",principal.digest());return null;});
                    return result;
                }));
                assertEquals(401,failure.status,"disable="+disable+" cause="+failure.getCause());assertTrue(read.get());
            }finally{accounts.tx(c->{AccountStore.execute(c,"UPDATE tk_account SET state='UNCLAIMED',username=NULL,password_hash=NULL WHERE owner_id=?",principal.owner());AccountStore.execute(c,"UPDATE tk_api_token SET revoked=FALSE WHERE digest=?",principal.digest());return null;});}
            assertEquals(principal.owner(),store.authenticatedReadSnapshot(identity,()->store.owner()));
        }
    }
    @Test void aRevokedBrowserSessionCannotReceiveTheSnapshot(){
        var token=tokens.authenticate("Bearer test-a");var issued=accounts.exchange(token);
        var principal=accounts.session(issued.secret());var identity=identity(principal);
        var failure=assertThrows(ApiFailure.class,()->store.authenticatedReadSnapshot(identity,()->{
            var result=store.profile();accounts.tx(c->{AccountStore.execute(c,"DELETE FROM tk_auth_session WHERE digest=?",principal.digest());return null;});return result;
        }));
        assertEquals(401,failure.status);
    }
    @Test void authenticationTouchesCanCommitDuringTheReadAndWritesRemainForbidden(){
        var principal=tokens.authenticate("Bearer test-read");var identity=identity(principal);
        assertEquals(principal.owner(),store.authenticatedReadSnapshot(identity,()->{
            accounts.tx(c->{accounts.guard(c,principal);return null;});return store.owner();
        }));
        assertThrows(IllegalStateException.class,()->store.authenticatedReadSnapshot(identity,()->{
            var notes=new DayNotes(store);var date=DayNotes.date("2026-11-16");return notes.saveContent(date,etag(notes.getContent(date)),object().put("body","Forbidden"));
        }));
        assertEquals("",store.authenticatedReadSnapshot(identity,()->new DayNotes(store).get(DayNotes.date("2026-11-16")).path("body").asText()));
    }
    @Test void firstNoteOriginReadPreparesTheLegacyCatalogThenSubsequentReadsArePure(){
        var identity=identity(tokens.authenticate("Bearer test-b"));var date=DayNotes.date("2026-11-17");
        var before=store.authenticated(identity,()->{
            var notes=new DayNotes(store);var saved=notes.saveContent(date,etag(notes.getContent(date)),object().put("body","- [ ] Private checklist task\n"));
            assertFalse(new NoteTasks(store).originsReady());return saved;
        });
        var origins=store.authenticatedReadSnapshot(identity,()->new NoteTasks(store).originsReady(),()->new NoteTasks(store).origins());
        assertEquals(1,origins.size());assertEquals("DAY",origins.values().iterator().next().path("kind").asText());
        assertEquals(origins,store.authenticatedReadSnapshot(identity,()->{
            assertTrue(new NoteTasks(store).originsReady());assertEquals(before,new DayNotes(store).getContent(date));return new NoteTasks(store).origins();
        }));
    }
}
