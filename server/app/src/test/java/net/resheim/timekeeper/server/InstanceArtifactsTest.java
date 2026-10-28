package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceArtifactsTest {
    @TempDir Path directory;
    private InstanceArtifacts store() throws IOException {return new InstanceArtifacts(directory.resolve("private"),8_000_000,12_000_000,3);}
    private static String digest(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private Path part(UUID id){return directory.resolve("private").resolve(id.toString()).resolve("input.part");}
    private static void opaque(IOException failure){assertNull(failure.getCause());assertFalse(failure.getMessage().contains("/"));}
    @Test void storageRequiresAnExistingProvisionedParent() {
        opaque(assertThrows(IOException.class,()->new InstanceArtifacts(directory.resolve("missing/parent/root"),8,16,2)));
        assertFalse(Files.exists(directory.resolve("missing")));
    }
    @Test void resumesAcrossInstancesAndRetriesAcknowledgedChunksWithoutDuplicatingData() throws Exception {
        var store=store();var id=UUID.randomUUID();byte[] bytes=new byte[2*InstanceArtifacts.CHUNK_BYTES+7];new Random(473).nextBytes(bytes);
        store.create(id,bytes.length,digest(bytes));
        byte[] first=Arrays.copyOf(bytes,InstanceArtifacts.CHUNK_BYTES);
        assertEquals(first.length,store.append(id,0,first).received());
        store=store();assertEquals(first.length,store.append(id,0,first).received());
        byte[] changed=first.clone();changed[0]^=1;
        assertEquals("INSTANCE_ARTIFACT_CONFLICT",assertThrows(IOException.class,()->store().append(id,0,changed)).getMessage());
        for(int offset=first.length;offset<bytes.length;offset+=InstanceArtifacts.CHUNK_BYTES)store.append(id,offset,Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+InstanceArtifacts.CHUNK_BYTES)));
        assertTrue(store.complete(id).complete());assertTrue(store().complete(id).complete());
        assertArrayEquals(bytes,store().read(id,(input,length)->{assertEquals(bytes.length,length);return input.readAllBytes();}));
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory.resolve("private").resolve(id.toString())));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(part(id).resolveSibling("archive.tki")));
    }
    @Test void unacknowledgedTailIsDiscardedAndRenameBeforeReceiptCommitCanBeRecovered() throws Exception {
        var store=store();var id=UUID.randomUUID();byte[] bytes={1,2,3,4,5,6};store.create(id,bytes.length,digest(bytes));store.append(id,0,new byte[]{1,2,3});
        Files.write(part(id),new byte[]{99,98,97},StandardOpenOption.APPEND);
        assertEquals(3,store().status(id).received());store().append(id,3,new byte[]{4,5,6});
        Files.move(part(id),part(id).resolveSibling("archive.tki"),StandardCopyOption.ATOMIC_MOVE);
        assertFalse(store().status(id).complete());assertTrue(store().complete(id).complete());
        assertArrayEquals(bytes,store().read(id,(input,length)->input.readAllBytes()));
    }
    @Test void refusesIncompleteTruncatedAndWrongDigestInputsWithoutPublishing() throws Exception {
        var store=store();var id=UUID.randomUUID();store.create(id,4,digest(new byte[]{1,2,3,4}));store.append(id,0,new byte[]{1,2});
        opaque(assertThrows(IOException.class,()->store.complete(id)));opaque(assertThrows(IOException.class,()->store.read(id,(input,length)->input.readAllBytes())));
        Files.write(part(id),new byte[]{1},StandardOpenOption.TRUNCATE_EXISTING);
        opaque(assertThrows(IOException.class,()->store.append(id,2,new byte[]{3,4})));
        store.remove(id);store.create(id,4,digest(new byte[]{1,2,3,4}));store.append(id,0,new byte[]{4,3,2,1});
        opaque(assertThrows(IOException.class,()->store.complete(id)));assertFalse(store.status(id).complete());assertFalse(Files.exists(part(id).resolveSibling("archive.tki")));
    }
    @Test void quotasReserveWholeDeclaredArchivesAndReleaseOnlyAfterDiscard() throws Exception {
        var store=new InstanceArtifacts(directory.resolve("private"),8,12,2);UUID first=UUID.randomUUID(),second=UUID.randomUUID(),third=UUID.randomUUID();
        store.create(first,8,"a".repeat(64));
        assertEquals("INSTANCE_ARTIFACT_QUOTA",assertThrows(IOException.class,()->store.create(second,5,"b".repeat(64))).getMessage());
        store.create(second,4,"b".repeat(64));
        assertEquals("INSTANCE_ARTIFACT_QUOTA",assertThrows(IOException.class,()->store.create(third,1,"c".repeat(64))).getMessage());
        store.remove(first);store.create(third,8,"c".repeat(64));assertEquals(0,store.status(third).received());store.remove(first);
    }
    @Test void symlinksHardlinksBroadPermissionsAndMalformedReceiptsFailClosed() throws Exception {
        var store=store();var id=UUID.randomUUID();byte[] bytes={1,2};store.create(id,2,digest(bytes));
        Path outside=directory.resolve("outside");Files.write(outside,bytes);Files.setPosixFilePermissions(outside,PosixFilePermissions.fromString("rw-------"));
        Files.delete(part(id));Files.createSymbolicLink(part(id),outside);opaque(assertThrows(IOException.class,()->store.append(id,0,bytes)));assertArrayEquals(bytes,Files.readAllBytes(outside));
        Files.delete(part(id));Files.createLink(part(id),outside);opaque(assertThrows(IOException.class,()->store.append(id,0,bytes)));Files.delete(part(id));
        Files.write(part(id),bytes);Files.setPosixFilePermissions(part(id),PosixFilePermissions.fromString("rw-r--r--"));opaque(assertThrows(IOException.class,()->store.append(id,0,bytes)));
        Files.setPosixFilePermissions(part(id),PosixFilePermissions.fromString("rw-------"));
        Path metadata=part(id).resolveSibling("upload.json");Files.writeString(metadata,"{\"format\":1,\"format\":1}");opaque(assertThrows(IOException.class,()->store.status(id)));
        Files.setPosixFilePermissions(directory.resolve("private"),PosixFilePermissions.fromString("rwxr-xr-x"));opaque(assertThrows(IOException.class,this::store));
    }
    @Test void identityPersistsAndMissingIdentityOrChangedCiphertextFailsClosed() throws Exception {
        var store=store();assertEquals(store.identity(),store().identity());var id=UUID.randomUUID();byte[] bytes={1,2};
        store.create(id,2,digest(bytes));store.append(id,0,bytes);store.complete(id);
        Files.write(part(id).resolveSibling("archive.tki"),new byte[]{2,1},StandardOpenOption.TRUNCATE_EXISTING);
        opaque(assertThrows(IOException.class,()->store.read(id,(input,length)->{fail("Tampered ciphertext must be rejected before the reader");return null;})));
        Files.delete(directory.resolve("private/.identity"));opaque(assertThrows(IOException.class,this::store));
        opaque(assertThrows(IOException.class,()->store.status(id)));
    }
    @Test void downloadLockPreventsConcurrentDiscardAndCleanupIsDurable() throws Exception {
        var store=store();var id=UUID.randomUUID();byte[] bytes={1,2};store.create(id,2,digest(bytes));store.append(id,0,bytes);store.complete(id);
        store.read(id,(input,length)->{
            assertEquals("INSTANCE_ARTIFACT_BUSY",assertThrows(IOException.class,()->store().remove(id)).getMessage());assertArrayEquals(bytes,input.readAllBytes());return null;
        });
        store.remove(id);assertFalse(Files.exists(part(id).getParent()));opaque(assertThrows(IOException.class,()->store().status(id)));
    }
}
