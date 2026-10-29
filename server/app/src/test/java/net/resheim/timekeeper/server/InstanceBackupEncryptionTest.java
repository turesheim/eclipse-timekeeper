package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceBackupEncryptionTest {
    @TempDir Path directory;
    private final char[] passphrase="synthetic backup passphrase".toCharArray();

    @Test void invalidPassphrasesAndFailedSourceReadsDoNotLeaveArtifacts() throws Exception {
        Path missing=directory.resolve("missing");
        for(char[] invalid:new char[][]{null,"short".toCharArray(),new char[1025]}) {
            assertThrows(IllegalArgumentException.class,()->InstanceBackupEncryption.encrypt(missing,directory,invalid));
            assertThrows(IllegalArgumentException.class,()->InstanceBackupEncryption.decrypt(missing,directory,invalid,100));
        }
        var failure=assertThrows(IOException.class,()->InstanceBackupEncryption.encrypt(missing,directory,passphrase));
        assertEquals("INSTANCE_BACKUP_ENCRYPTION_FAILED",failure.getMessage());assertNull(failure.getCause());
        try(var files=Files.list(directory)) {assertEquals(0,files.count());}
    }

    @Test void emptyAndMultiSegmentArchivesRoundTripWithPrivateRandomizedCiphertext() throws Exception {
        Path source=directory.resolve("source");
        for(int size:new int[]{0,1_048_576,2_100_003}) {
            byte[] bytes=new byte[size];new Random(42).nextBytes(bytes);Files.write(source,bytes);
            Path encrypted=InstanceBackupEncryption.encrypt(source,directory,passphrase);
            Path second=InstanceBackupEncryption.encrypt(source,directory,passphrase);
            assertNotEquals(-1,Files.mismatch(encrypted,second));
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(encrypted));
            Path restored=InstanceBackupEncryption.decrypt(encrypted,directory,passphrase,size);
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(restored));
            assertEquals(-1,Files.mismatch(source,restored));
        }
        Path privateDirectory=InstanceBackupEncryption.privateDirectory();
        try {assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(privateDirectory));}
        finally {Files.delete(privateDirectory);}
    }

    @Test void wrongPassphraseTamperingTruncationReorderingAndTrailingBytesLeaveNoPlaintext() throws Exception {
        Path source=directory.resolve("source");byte[] payload=new byte[2_200_000];new Random(7).nextBytes(payload);Files.write(source,payload);
        Path encrypted=InstanceBackupEncryption.encrypt(source,directory,passphrase);byte[] original=Files.readAllBytes(encrypted);
        rejects(encrypted,"different test passphrase".toCharArray(),payload.length);
        for(int index:new int[]{0,8,40,100,1_100_000,original.length-1}) {
            byte[] changed=original.clone();changed[index]^=1;Files.write(encrypted,changed);rejects(encrypted,passphrase,payload.length);
        }
        for(int size:new int[]{0,20,40,1_100_000,original.length-1}) {
            Files.write(encrypted,Arrays.copyOf(original,size));rejects(encrypted,passphrase,payload.length);
        }
        byte[] reordered=original.clone();
        // Swap full authenticated ciphertext segments, preserving the header.
        System.arraycopy(original,40+1_048_576,reordered,40,1_048_576);
        System.arraycopy(original,40,reordered,40+1_048_576,1_048_576);
        Files.write(encrypted,reordered);rejects(encrypted,passphrase,payload.length);
        Files.write(encrypted,Arrays.copyOf(original,original.length+1));rejects(encrypted,passphrase,payload.length);
        Files.write(encrypted,original);rejects(encrypted,passphrase,payload.length-1);
    }

    private void rejects(Path encrypted,char[] phrase,long limit) throws Exception {
        try(var before=Files.list(directory)) {
            var expected=before.sorted().toList();
            var failure=assertThrows(IOException.class,()->InstanceBackupEncryption.decrypt(encrypted,directory,phrase,limit));
            assertEquals("INVALID_INSTANCE_BACKUP",failure.getMessage());assertNull(failure.getCause());
            try(var after=Files.list(directory)) {assertEquals(expected,after.sorted().toList());}
        }
    }
}
