package net.resheim.eclipse.timekeeper.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;

class CsvImportRetryTest {
    @TempDir Path temporary;

    @Test void lostImportReplyReusesDurableKeyAfterClientRestart() throws Exception {
        Path csv=temporary.resolve("csv");CsvSnapshot.write(CsvSnapshotTest.snapshot(),csv);
        Path journal=temporary.resolve("journal");
        AtomicInteger committed=new AtomicInteger(),requests=new AtomicInteger();
        Set<String> keys=ConcurrentHashMap.newKeySet();
        CountDownLatch release=new CountDownLatch(1),confirmedCommit=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/api/v1/me",exchange->{
                byte[] reply="{\"ownerId\":\"owner-a\",\"zoneId\":\"Europe/Oslo\",\"version\":0}".getBytes(StandardCharsets.UTF_8);
                try(exchange) {exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);}
            });
            server.createContext("/api/v1/data/import",exchange->{
                exchange.getRequestBody().readAllBytes();
                String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
                requests.incrementAndGet();
                boolean first=keys.add(key);
                if(first) {
                    committed.incrementAndGet();
                    confirmedCommit.countDown();
                    try {release.await(30,TimeUnit.SECONDS);}
                    catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
                }
                byte[] reply="{\"ids\":[{},{},{},{},{}],\"revision\":1}".getBytes(StandardCharsets.UTF_8);
                try(exchange) {exchange.sendResponseHeaders(201,reply.length);exchange.getResponseBody().write(reply);}
            });
            server.start();URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            try {
                try(var first=new HttpTimekeeperService(uri,()->"test-only-token",Duration.ofSeconds(30))) {
                    AtomicReference<RemoteFailure> lostReply=new AtomicReference<>();
                    Thread worker=new Thread(()->{
                        try {first.importCsv(csv,journal);}
                        catch(RemoteFailure failure) {lostReply.set(failure);}
                        catch(java.io.IOException failure) {throw new AssertionError(failure);}
                    });
                    worker.start();
                    try {
                        assertTrue(confirmedCommit.await(10,TimeUnit.SECONDS),"The server must commit before simulating a lost reply");
                        worker.interrupt();worker.join(10000);
                        assertFalse(worker.isAlive(),"The cancelled client must release its worker");
                        assertNotNull(lostReply.get());assertEquals(RemoteFailure.Kind.CANCELLED,lostReply.get().kind());
                    } finally {worker.interrupt();worker.join(10000);}
                }
                try(var restarted=new HttpTimekeeperService(uri,()->"test-only-token",Duration.ofSeconds(10))) {
                    assertEquals(5,restarted.importCsv(csv,journal));
                }
                assertEquals(1,committed.get());assertEquals(1,keys.size());assertEquals(2,requests.get());
                try(var files=Files.list(journal)) {assertTrue(files.noneMatch(file->file.toString().endsWith(".key")));}
            } finally {release.countDown();server.stop(0);}
        }
    }
}
