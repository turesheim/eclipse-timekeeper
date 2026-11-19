package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountArchiveDisconnectTest {
    @Test void disconnectDuringPreparationRemovesPrivateZip(@TempDir Path temporary) throws Exception {
        Path folder=Files.createDirectory(temporary.resolve("archive"));
        Path zip=Files.writeString(folder.resolve("account.zip"),"synthetic private archive");
        long length=Files.size(zip);
        var building=new CompletableFuture<Void>();
        var disconnected=new CompletableFuture<Void>();
        var prepared=new CompletableFuture<Response>();
        var vertx=Vertx.vertx();
        try {
            var router=Router.router(vertx);
            router.get("/export").blockingHandler(routing->{
                routing.addEndHandler(ignored->disconnected.complete(null));
                try {
                    prepared.complete(AccountArchiveResource.exportResponse(routing,()->{
                        building.complete(null);
                        disconnected.orTimeout(5,TimeUnit.SECONDS).join();
                        return new AccountArchive.Download(zip,length);
                    },"timekeeper-account").build());
                }catch(RuntimeException failure) {prepared.completeExceptionally(failure);}
            });
            var server=vertx.createHttpServer().requestHandler(router).listen(0,"127.0.0.1")
                    .toCompletionStage().toCompletableFuture().get(5,TimeUnit.SECONDS);
            try(var client=new Socket("127.0.0.1",server.actualPort())) {
                client.getOutputStream().write("GET /export HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                building.get(5,TimeUnit.SECONDS);
            }
            assertEquals(204,prepared.get(10,TimeUnit.SECONDS).getStatus());
            assertFalse(Files.exists(zip));
            assertFalse(Files.exists(folder));
        }finally {vertx.close().toCompletionStage().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
}
