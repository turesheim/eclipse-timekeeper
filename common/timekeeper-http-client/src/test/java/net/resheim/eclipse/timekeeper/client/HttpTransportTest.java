package net.resheim.eclipse.timekeeper.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Exercises actual sockets, response deadlines, cancellation and credential boundaries. */
class HttpTransportTest {
    private static final String TOKEN = "private-test-token";

    @Test void timeoutTerminatesAnOutstandingRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture server = new Fixture(exchange -> {
            received.countDown();
            await(release);
            reply(exchange, 200, "{}");
        }); var client = server.client(Duration.ofMillis(500))) {
            var failure = assertThrows(RemoteFailure.class, client::projects);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(RemoteFailure.Kind.TIMEOUT, failure.kind());
            assertSafe(failure);
        } finally { release.countDown(); }
    }

    @Test void interruptCancelsAnOutstandingRequestAndPreservesInterruptFlag() throws Exception {
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture server = new Fixture(exchange -> {
            received.countDown();
            await(release);
            reply(exchange, 200, "{}");
        }); var client = server.client(Duration.ofSeconds(20))) {
            AtomicReference<RemoteFailure> observed = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                try { client.projects(); }
                catch (RemoteFailure failure) { observed.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            worker.start();
            try {
                assertTrue(received.await(3, TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(3000);
                assertFalse(worker.isAlive(), "The client must release its worker promptly on cancellation");
                assertNotNull(observed.get());
                assertEquals(RemoteFailure.Kind.CANCELLED, observed.get().kind());
                assertTrue(interrupted.get());
                assertSafe(observed.get());
            } finally {
                release.countDown();worker.interrupt();worker.join(3000);
            }
        } finally { release.countDown(); }
    }

    @Test void nonJsonAuthenticationResponseStillReportsAuthenticationFailure() throws Exception {
        try (Fixture server = new Fixture(exchange -> reply(exchange, 401, "<html>" + TOKEN + "</html>"));
                var client = server.client(Duration.ofSeconds(2))) {
            var failure = assertThrows(RemoteFailure.class, client::projects);
            assertEquals(RemoteFailure.Kind.AUTHENTICATION, failure.kind());
            assertSafe(failure);
        }
    }

    @Test void invalidSuccessfulResponseIsSafeAndUnavailable() throws Exception {
        try (Fixture server = new Fixture(exchange -> reply(exchange, 200, "not-json " + TOKEN));
                var client = server.client(Duration.ofSeconds(2))) {
            var failure = assertThrows(RemoteFailure.class, client::projects);
            assertEquals(RemoteFailure.Kind.UNAVAILABLE, failure.kind());
            assertSafe(failure);
        }
    }

    @Test void redirectDoesNotForwardTheBearerToken() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        try (Fixture target = new Fixture(exchange -> { redirectedRequests.incrementAndGet();reply(exchange, 200, "{}"); });
                Fixture source = new Fixture(exchange -> {
                    assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getResponseHeaders().add("Location", target.url() + "/other");
                    reply(exchange, 307, "");
                }); var client = source.client(Duration.ofSeconds(2))) {
            assertEquals(RemoteFailure.Kind.UNAVAILABLE, assertThrows(RemoteFailure.class, client::projects).kind());
            assertEquals(0, redirectedRequests.get());
        }
    }

    @Test void missingTokenNeverSendsARequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> { requests.incrementAndGet();reply(exchange, 200, "{}"); });
                var client = new HttpTimekeeperService(server.url(), () -> "", Duration.ofSeconds(2))) {
            assertEquals(RemoteFailure.Kind.AUTHENTICATION, assertThrows(RemoteFailure.class, client::projects).kind());
            assertEquals(0, requests.get());
        }
    }

    @Test void refusedConnectionIsSafeAndOffline() throws Exception {
        URI address;
        try (Fixture server = new Fixture(exchange -> reply(exchange, 200, "{}"))) { address = server.url(); }
        try (var client = new HttpTimekeeperService(address, () -> TOKEN, Duration.ofSeconds(2))) {
            var failure = assertThrows(RemoteFailure.class, client::projects);
            assertEquals(RemoteFailure.Kind.OFFLINE, failure.kind());
            assertSafe(failure);
        }
    }

    @Test void changePagesCarryOrderedCursorsAndSignalMissingHistory() throws Exception {
        AtomicReference<String> response=new AtomicReference<>("{\"items\":[{\"sequence\":5},{\"sequence\":6}],\"nextSequence\":6,\"hasMore\":false}");
        try(Fixture server=new Fixture(exchange->{
            assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("after=4&waitSeconds=5",exchange.getRequestURI().getQuery());reply(exchange,200,response.get());
        });var client=server.client(Duration.ofSeconds(2))) {
            assertEquals(new HttpTimekeeperService.ChangeBatch(6,true,false),client.changeBatch(4,5));
            response.set("{\"items\":[],\"nextSequence\":4,\"hasMore\":true}");
            assertEquals(new HttpTimekeeperService.ChangeBatch(4,false,true),client.changeBatch(4,5));
        }
    }
    @Test void invalidChangeCursorCannotAdvanceTheClientOrExposeAResponse() throws Exception {
        try(Fixture server=new Fixture(exchange->reply(exchange,200,"{\"items\":[{\"sequence\":5},{\"sequence\":4}],\"nextSequence\":4,\"hasMore\":false,\"detail\":\""+TOKEN+"\"}"));
                var client=server.client(Duration.ofSeconds(2))) {
            var failure=assertThrows(RemoteFailure.class,()->client.changeBatch(4,0));
            assertEquals(RemoteFailure.Kind.UNAVAILABLE,failure.kind());assertEquals("INVALID_CHANGE_PAGE",failure.code());assertSafe(failure);
        }
    }
    @Test void fullResynchronizationDropsEtagsFromBeforeARevisionReset() throws Exception {
        String id="77777777-7777-4777-8777-777777777777";
        AtomicInteger revision=new AtomicInteger(2);
        try(Fixture server=new Fixture(exchange->{
            int current=revision.get();String tag="\"task-"+current+"\"";
            if(exchange.getRequestMethod().equals("PUT")) {
                if(!tag.equals(exchange.getRequestHeaders().getFirst("If-Match"))) {reply(exchange,412,"{\"code\":\"STALE_VERSION\"}");return;}
                current=revision.incrementAndGet();tag="\"task-"+current+"\"";
            }
            exchange.getResponseHeaders().set("ETag",tag);
            reply(exchange,200,"{\"id\":\""+id+"\",\"ownerId\":\"owner-test\",\"version\":"+current+",\"projectId\":null,\"parentTaskId\":null,\"summary\":\"Restored task\",\"url\":null,\"externalReferences\":[]}");
        });var client=server.client(Duration.ofSeconds(2))) {
            var taskId=new net.resheim.eclipse.timekeeper.domain.TaskId(java.util.UUID.fromString(id));
            assertEquals(2,client.task(taskId).orElseThrow().version());revision.set(1);
            var command=new net.resheim.eclipse.timekeeper.service.Commands.UpdateTask(taskId,1,java.util.Optional.empty(),java.util.Optional.empty(),"Reviewed restored task",java.util.Optional.empty());
            assertEquals(RemoteFailure.Kind.CONFLICT,assertThrows(RemoteFailure.class,()->client.updateTask(command)).kind());
            client.invalidateVersions();assertEquals(2,client.updateTask(command).version());
        }
    }
    private static void assertSafe(RemoteFailure failure) {
        assertFalse(failure.getMessage().contains(TOKEN));
        assertNull(failure.getCause(), "Transport internals must not expose a response or credential");
    }
    private static void await(CountDownLatch release) {
        try { release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length != 0) exchange.getResponseBody().write(bytes);
        }
    }
    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Fixture(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler);server.setExecutor(executor);server.start();
        }
        URI url() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        HttpTimekeeperService client(Duration timeout) { return new HttpTimekeeperService(url(), () -> TOKEN, timeout); }
        @Override public void close() { server.stop(0);executor.shutdownNow(); }
    }
}
