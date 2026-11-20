package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.StartWorkSession;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(AuthenticatedSnapshotTest.Profile.class)
class AuthenticatedSnapshotTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("authenticatedsnapshots","jdbc:h2:mem:authenticated_snapshots");}
    }
    @Inject ServerStore store;
    @Inject AccountStore accounts;
    @Inject TokenRegistry tokens;
    @TestHTTPResource URI server;

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
    @Test void refreshReadsCommittedDataWhileAnOwnerWriteIsInProgress() throws Exception {
        var principal=tokens.authenticate("Bearer test-a");
        String owner=principal.owner();
        var session=store.authenticated(identity(principal),()->{
            new VaultCollection(store,store.vaultLimits()).initialize();
            new NotesSearchChanges(store).snapshot();
            return store.workSessionService().startWorkSession(new StartWorkSession(new OwnerId(owner),"Snapshot test"));
        });
        String project="Pending project "+UUID.randomUUID();
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var client=HttpClient.newHttpClient();var worker=Executors.newSingleThreadExecutor()) {
            var writer=worker.submit(()->store.transaction(owner,()->{
                var pending=store.service().createProject(new CreateProject(project));
                locked.countDown();
                try{assertTrue(release.await(30,TimeUnit.SECONDS));}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new AssertionError(stopped);}
                return pending;
            }));
            try {
                assertTrue(locked.await(5,TimeUnit.SECONDS));
                String range="from=2026-11-19T00:00:00Z&to=2026-11-20T00:00:00Z";
                String days="fromDate=2026-11-19&toDate=2026-11-20";
                for(String route:List.of("/me","/clients","/projects","/tasks?includeNoteSources=true","/labels",
                        "/work-sessions","/work-sessions/active","/work-sessions/"+session.id().value()+"/report",
                        "/activities?"+range,"/reports/activities?"+range,"/focus-areas","/changes?after=0",
                        "/calendar-subscriptions","/calendar-events?"+days,"/calendar-tasks",
                        "/day-notes?"+days,"/day-labels?"+days,"/day-entries/2026-11-19",
                        "/week-notes?fromWeek=2026-W47&toWeek=2026-W48","/event-notes",
                        "/notes","/notes/entries","/notes/recent","/notes/search/status")) {
                    var response=get(client,"test-a",route);
                    assertEquals(200,response.statusCode(),route+": "+response.body());
                    assertFalse(response.body().contains(project),route);
                }
                assertEquals(204,get(client,"test-a","/activities/active").statusCode());
                assertEquals(404,get(client,"test-b","/work-sessions/"+session.id().value()+"/report").statusCode());
            } finally {release.countDown();}
            var committed=writer.get(5,TimeUnit.SECONDS);
            var visible=get(client,"test-a","/projects/"+committed.id().value());
            assertEquals(200,visible.statusCode());
            assertEquals(project,parse(visible.body()).path("name").asText());
            assertEquals(404,get(client,"test-b","/projects/"+committed.id().value()).statusCode());
        }
    }
    private HttpResponse<String> get(HttpClient client,String token,String route) throws Exception {
        var request=HttpRequest.newBuilder(server.resolve("/api/v1"+route)).timeout(Duration.ofSeconds(5))
                .header("Authorization","Bearer "+token).GET().build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
}
