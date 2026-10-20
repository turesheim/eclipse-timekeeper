package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;
import io.quarkus.test.junit.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(RequestTimingsTest.Profile.class)
class RequestTimingsTest {
    @jakarta.inject.Inject ServerStore store;
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:request_timings;LOCK_TIMEOUT=15000", "timekeeper.diagnostics.slow-request-ms","0", "timekeeper.diagnostics.slow-transaction-ms","0");}
    }
    @Test void timingsUseGeneratedIdsAndRouteTemplatesWithoutPrivateInput() {
        var messages=new CopyOnWriteArrayList<String>();
        var capture=new Handler(){
            public void publish(LogRecord record){messages.add(record instanceof org.jboss.logmanager.ExtLogRecord value?value.getFormattedMessage():record.getMessage());}
            public void flush(){}public void close(){}
        };
        Logger requestLog=Logger.getLogger(RequestTimings.class.getName()),databaseLog=Logger.getLogger(ServerStore.class.getName());
        requestLog.addHandler(capture);databaseLog.addHandler(capture);
        try {
            var response=given().header("Authorization","Bearer test-a").get("/api/v1/me");
            assertEquals(200,response.statusCode());String id=response.header(RequestTimings.HEADER);assertNotNull(id);java.util.UUID.fromString(id);
            assertTrue(messages.stream().anyMatch(message->message.contains("request_id="+id)&&message.contains("route=/api/v1/me")&&message.contains("elapsed_ms=")));
            assertTrue(messages.stream().anyMatch(message->message.contains("Database transaction")&&List.of("pool_ms=","owner_lock_ms=","work_ms=","commit_ms=").stream().allMatch(message::contains)));
            String resource="ebcddfed-3ac9-4a54-8f8f-408362eeff73",secret="do-not-log-this-value";
            var rejected=given().header("Authorization","Bearer test-a").queryParam("private",secret).get("/api/v1/clients/"+resource);
            assertEquals(400,rejected.statusCode());String rejectedId=rejected.header(RequestTimings.HEADER);
            assertTrue(messages.stream().anyMatch(message->message.contains("request_id="+rejectedId)&&message.contains("route=/api/v1/clients/{id}")&&message.contains("status=400")));
            assertThrows(ApiFailure.class,()->store.transaction("a",()->{store.update("SELECT 'private-database-value' FROM no_such_table");return null;}));
            assertTrue(messages.stream().anyMatch(message->message.contains("Database operation failed sql_state=")));
            assertTrue(messages.stream().anyMatch(message->message.contains("outcome=server-error phase=work")));
            assertFalse(messages.stream().anyMatch(message->message.contains("private-database-value")));
            assertFalse(messages.stream().anyMatch(message->message.contains(secret)||message.contains(resource)||message.contains("test-a")));
        } finally {requestLog.removeHandler(capture);databaseLog.removeHandler(capture);}
    }
    @Test void aBlockedAccountReadReportsThePendingRequestAndItsLockWait() throws Exception {
        var messages=new CopyOnWriteArrayList<String>();
        var capture=new Handler(){
            public void publish(LogRecord record){messages.add(record instanceof org.jboss.logmanager.ExtLogRecord value?value.getFormattedMessage():record.getMessage());}
            public void flush(){}public void close(){}
        };
        Logger requestLog=Logger.getLogger(RequestTimings.class.getName()),databaseLog=Logger.getLogger(ServerStore.class.getName());
        requestLog.addHandler(capture);databaseLog.addHandler(capture);
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var blocker=workers.submit(()->store.transaction("a",()->{held.countDown();try{release.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}return null;}));
            try {
                assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var pending=workers.submit(()->given().header("Authorization","Bearer test-a").get("/api/v1/me"));
                long deadline=System.nanoTime()+8_000_000_000L;
                while(messages.stream().noneMatch(message->message.contains("API request still running"))&&System.nanoTime()<deadline)Thread.sleep(20);
                assertTrue(messages.stream().anyMatch(message->message.contains("API request still running")&&message.contains("route=/api/v1/me")));
                release.countDown();var response=pending.get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(200,response.statusCode());
                String id=response.header(RequestTimings.HEADER);
                assertTrue(messages.stream().anyMatch(message->message.contains("API request still running request_id="+id)));
                assertTrue(messages.stream().anyMatch(message->message.matches(".*owner_lock_ms=[1-9][0-9]{3,}.*")));
            } finally {release.countDown();blocker.get(5,java.util.concurrent.TimeUnit.SECONDS);}
        } finally {requestLog.removeHandler(capture);databaseLog.removeHandler(capture);}
    }
    @Test void ordinaryLongPollingHasItsRequestedWaitAllowanceOnlyOnTheChangeRoute(){
        assertEquals(5000,RequestTimings.pollAllowance("/api/v1/changes","5"));
        assertEquals(25000,RequestTimings.pollAllowance("/api/v1/changes","25"));
        for(String value:new String[]{"-1","26","invalid"})assertEquals(0,RequestTimings.pollAllowance("/api/v1/changes",value));
        assertEquals(0,RequestTimings.pollAllowance("/api/v1/me","25"));
    }
}
