package net.resheim.timekeeper.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** A private ZIP and an atomically published pointer on the shared instance volume. */
@ApplicationScoped
class PreparedAccountArchive {
    static final java.nio.file.attribute.FileAttribute<Set<PosixFilePermission>> DIRECTORY_MODE=
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final Logger LOG=Logger.getLogger(PreparedAccountArchive.class);

    @ConfigProperty(name="timekeeper.account-archive.storage",defaultValue="account-archives") String storage;
    @Inject AccountArchive archives;
    @Inject ServerStore store;

    record Download(InputStream input,long length,LocalDate date) implements AutoCloseable {
        @Override public void close(){try{input.close();}catch(IOException ignored){}}
    }

    // Filesystem publication, lookup and deletion run under the short owner transaction.
    // ZIP compression happens outside it, using the existing repeatable-read export.
    String current(RequestIdentity identity) {
        return store.authenticated(identity,()->io(()->currentId(directory(identity))));
    }

    String prepare(RequestIdentity identity) {
        Path directory=io(()->Files.createDirectories(directory(identity),DIRECTORY_MODE));
        try(var archive=archives.download(identity.principal(),directory)) {
            return store.authenticated(identity,()->io(()->publish(directory,archive.file())));
        }
    }

    void delete(RequestIdentity identity,String id) {
        store.authenticated(identity,()->io(()->{
            Path directory=directory(identity);
            requireCurrent(directory,id);
            Files.delete(directory.resolve("current"));
            removeInactive(directory,null);
            return null;
        }));
    }

    Download open(RequestIdentity identity,String id,java.util.function.Consumer<Download> onOpen) {
        return store.authenticated(identity,()->io(()->{
            Path directory=directory(identity);
            requireCurrent(directory,id);
            Path file=directory.resolve(id+".zip");
            LocalDate date=Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            try {
                var download=new Download(Channels.newInputStream(channel),channel.size(),date);
                onOpen.accept(download);
                return download;
            }catch(IOException | RuntimeException failure){channel.close();throw failure;}
        }));
    }

    private Path directory(RequestIdentity identity) {
        var key=ArchiveJournalFiles.digest(identity.owner().getBytes(StandardCharsets.UTF_8));
        return Path.of(storage,java.util.Objects.toString(store.restoreGeneration(),"initial"),key);
    }

    private static String publish(Path directory,Path archive) throws IOException {
        String id=UUID.randomUUID().toString();
        Path file=directory.resolve(id+".zip");
        Path pointer=archive.getParent().resolve("current");
        // Prepare the new pointer privately; readers can see only a complete ZIP.
        Files.writeString(pointer,id,StandardOpenOption.CREATE_NEW);
        try {
            Files.move(archive,file,StandardCopyOption.ATOMIC_MOVE);
            Files.move(pointer,directory.resolve("current"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException failure) {
            Files.deleteIfExists(file);
            throw failure;
        } finally {
            Files.deleteIfExists(pointer);
        }
        removeInactive(directory,id);
        return id;
    }

    private static String currentId(Path directory) throws IOException {
        String id;
        try{id=Files.readString(directory.resolve("current"));}
        catch(NoSuchFileException absent){return null;}
        if(!id.matches("[0-9a-f-]{36}") || !Files.isRegularFile(directory.resolve(id+".zip"),LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid prepared archive");
        return id;
    }

    private static void requireCurrent(Path directory,String id) throws IOException {
        if(!id.equals(currentId(directory)))throw ApiFailure.notFound();
    }

    private static void removeInactive(Path directory,String current) {
        try(var files=Files.newDirectoryStream(directory,"*.zip")) {
            for(Path file:files)if(current==null || !file.getFileName().toString().equals(current+".zip"))Files.deleteIfExists(file);
        }catch(IOException failure){LOG.warn("Could not remove a superseded private account archive");}
    }

    @FunctionalInterface private interface Operation<T>{T run() throws IOException;}
    private static <T> T io(Operation<T> operation){
        try{return operation.run();}
        catch(IOException | UnsupportedOperationException failure){throw new ApiFailure(503,"UNAVAILABLE",failure);}
    }
}
