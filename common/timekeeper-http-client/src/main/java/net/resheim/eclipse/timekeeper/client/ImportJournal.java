package net.resheim.eclipse.timekeeper.client;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Stores only command keys and hashed endpoint/owner/payload scope; no token or records. */
final class ImportJournal {
    private final Path folder;
    private final String scope;
    ImportJournal(Path folder, String endpoint, String owner, String payload) {
        this.folder=folder;
        this.scope=digest(endpoint+"\n"+owner+"\n"+digest(payload));
    }
    String pending() throws IOException {
        Files.createDirectories(folder);
        try (var channel=FileChannel.open(folder.resolve(scope+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.lock()) {
            Path file=folder.resolve(scope+".key");
            if (Files.exists(file)) {
                String value=Files.readString(file,StandardCharsets.US_ASCII);
                try {return UUID.fromString(value).toString();}
                catch(IllegalArgumentException corrupt) {throw new IOException("The pending import key is invalid. Verify the previous import before trying again.");}
            }
            String key=UUID.randomUUID().toString();
            Path temporary=Files.createTempFile(folder,scope,".pending");
            try {
                try (var output=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    var bytes=java.nio.ByteBuffer.wrap(key.getBytes(StandardCharsets.US_ASCII));
                    while (bytes.hasRemaining()) output.write(bytes);output.force(true);
                }
                Files.move(temporary,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } finally {Files.deleteIfExists(temporary);}
            return key;
        }
    }
    void acknowledged(String key) {
        try (var channel=FileChannel.open(folder.resolve(scope+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.lock()) {
            Path file=folder.resolve(scope+".key");
            if (Files.exists(file) && key.equals(Files.readString(file,StandardCharsets.US_ASCII))) Files.delete(file);
        } catch(IOException ignored) {
            // A retained receipt key is safe to retry. Never turn an acknowledged
            // server import into an apparent failure because local cleanup failed.
        }
    }
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
