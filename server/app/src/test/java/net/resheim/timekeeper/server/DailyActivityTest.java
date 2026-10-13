package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;

@QuarkusTest @TestProfile(DailyActivityTest.Profile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DailyActivityTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:timekeeper_daily_tests");
        }
    }
    @Inject MockBackendClock clock;
    @Inject ServerStore store;
    @TestHTTPResource URI endpoint;
    @BeforeEach void unwrapStore() {store=io.quarkus.arc.ClientProxy.unwrap(store);}
    private HttpTimekeeperService client() {return new HttpTimekeeperService(endpoint,()->"test-a",Duration.ofSeconds(5));}
    private List<JsonNode> stored() throws Exception {
        var values=new ArrayList<JsonNode>();
        try(var connection=store.datasource.getConnection();var statement=connection.prepareStatement(
            "SELECT payload FROM tk_resource WHERE owner_id='a' AND entity_type='ACTIVITY'")) {
            try(var rows=statement.executeQuery()) {while(rows.next()) values.add(JsonValues.parse(rows.getString(1)));}
        }
        return values;
    }
    @AfterEach void clear() throws Exception {
        try(var connection=store.datasource.getConnection()) {
            connection.setAutoCommit(false);
            for(var table:List.of("tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner"))
                try(var statement=connection.createStatement()) {statement.executeUpdate("DELETE FROM "+table+" WHERE "+(table.equals("tk_owner") ? "id" : "owner_id")+"='a'");}
            connection.commit();
        } finally {clock.set(null);}
    }
    @Test @Order(1) void backgroundRolloverArchivesOwnedTimeWithoutAnyClientRequest() throws Exception {
        Instant start=Instant.parse("2025-10-25T21:59:59.123456789Z");clock.set(start);
        try(var api=client()) {
            api.profile();store.transaction("a",()->store.updateProfile("Europe/Oslo"));
            var task=api.createTask(new CreateTask(Optional.empty(),"Background daily tracking",Optional.empty()));
            var label=api.createLabel(new CreateLabel("Daily label",Optional.of("12,34,56")));
            var original=api.startActivity(new StartActivity(task.id(),OwnerId.LOCAL,"Exact tracking",Set.of(label.id())));
            clock.set(start.plus(Duration.ofHours(26)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(stored().size()!=3 && System.nanoTime()<deadline) Thread.sleep(20);
            assertEquals(3,stored().size(),"Background clock maintenance required a client request");
            var active=api.activeActivity(OwnerId.LOCAL).orElseThrow();
            assertEquals(original.id(),active.id());assertEquals(Instant.parse("2025-10-26T23:00:00Z"),active.start());
            var query=new ActivityQuery(start.minusSeconds(1),clock.now().plusSeconds(1),Optional.of(OwnerId.LOCAL),Optional.of(task.id()),Optional.empty(),ZoneId.of("Europe/Oslo"));
            var report=api.activities(query);assertEquals(Duration.ofHours(26),report.total());
            for(var part:report.activities()) {assertFalse(part.manual());assertEquals(Set.of(label.id()),part.labelIds());assertEquals("Exact tracking",part.summary());}
            assertEquals(label,api.label(label.id()).orElseThrow());
        }
    }
    @Test @Order(2) void concurrentServerInstancesProduceOneExactSetOfDailyArchives() throws Exception {
        store.stopMaintenance();
        Instant start=Instant.parse("2025-06-01T23:59:59.123456789Z");clock.set(start);
        try(var api=client()) {
            var task=api.createTask(new CreateTask(Optional.empty(),"Replica daily tracking",Optional.empty()));
            var original=api.startActivity(new StartActivity(task.id(),OwnerId.LOCAL,"Concurrent tracking",Set.of()));
            clock.set(start.plus(Duration.ofDays(2)));
            var replica=new ServerStore();replica.datasource=store.datasource;replica.clock=clock;replica.jdbcUrl=store.jdbcUrl;replica.tokens=store.tokens;
            var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
            try(var workers=Executors.newFixedThreadPool(2)) {
                var futures=new ArrayList<Future<?>>();
                for(var member:List.of(store,replica)) futures.add(workers.submit(()->{
                    ready.countDown();try {release.await();}catch(InterruptedException failure) {throw new IllegalStateException(failure);}
                    member.transaction("a",()->null);
                }));
                assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();for(var future:futures) future.get(10,TimeUnit.SECONDS);
            }
            var values=stored();assertEquals(3,values.size());assertEquals(1,values.stream().filter(value->value.get("end").isNull()).count());
            assertEquals(original.id(),api.activeActivity(OwnerId.LOCAL).orElseThrow().id());
            var report=api.activities(new ActivityQuery(start.minusSeconds(1),clock.now().plusSeconds(1),Optional.of(OwnerId.LOCAL),Optional.of(task.id()),Optional.empty(),ZoneId.of("UTC")));
            assertEquals(Duration.ofDays(2),report.total());
        }
    }
    @Test @Order(3) void failedArchiveWriteRollsBackAllPartsAndTheirChangeEvents() throws Exception {
        Instant start=Instant.parse("2025-06-01T23:59:59Z");clock.set(start);
        try(var api=client()) {
            var task=api.createTask(new CreateTask(Optional.empty(),"Atomic daily tracking",Optional.empty()));
            var original=api.startActivity(new StartActivity(task.id(),OwnerId.LOCAL,"Atomic tracking",Set.of()));
            var collision=new Activity(ActivityDailySegments.identifier(original.id(),Instant.parse("2025-06-02T00:00:00Z")),task.id(),new OwnerId("a"),start.minusSeconds(60),Optional.of(start),"Existing independent row",Set.of(),true,0);
            store.transaction("a",()->{store.save("ACTIVITY",collision);return null;});
            var before=stored();long revision=store.transaction("a",store::revision);
            clock.set(start.plus(Duration.ofDays(2)));
            assertThrows(net.resheim.eclipse.timekeeper.service.ServiceException.class,()->store.transaction("a",()->null));
            assertEquals(new HashSet<>(before),new HashSet<>(stored()));
            try(var connection=store.datasource.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT revision FROM tk_owner WHERE id='a'")) {
                assertTrue(rows.next());assertEquals(revision,rows.getLong(1));
            }
        }
    }
}
