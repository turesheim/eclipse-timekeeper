package net.resheim.timekeeper.server;

import com.google.crypto.tink.AccessesPartialKey;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey;
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;
import com.google.crypto.tink.util.SecretBytes;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Versioned passphrase envelope around Tink's standard streaming AEAD format. */
final class InstanceBackupEncryption {
    private InstanceBackupEncryption() { }
    private static final byte[] MAGIC="TKIBK001".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES=32,ITERATIONS=600_000;
    private static final SecureRandom RANDOM=new SecureRandom();

    static Path privateDirectory() throws IOException {
        return Files.createTempDirectory("timekeeper-instance-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    /** The caller owns the completed encrypted file and must delete it after download. */
    static Path encrypt(Path plaintext,Path privateDirectory,char[] passphrase) throws IOException {
        validatePassphrase(passphrase);
        Path target=privateFile(privateDirectory,".tkbackup");boolean complete=false;
        try {
            byte[] salt=new byte[SALT_BYTES];RANDOM.nextBytes(salt);
            byte[] header=header(salt);
            try(var input=Files.newInputStream(plaintext);var raw=Files.newOutputStream(target)) {
                raw.write(header);
                try(var encrypted=primitive(passphrase,salt).newEncryptingStream(raw,header)) {input.transferTo(encrypted);}
            }
            complete=true;return target;
        }catch(GeneralSecurityException|IOException failure) {
            throw new IOException("INSTANCE_BACKUP_ENCRYPTION_FAILED");
        }finally {if(!complete)removeIncomplete(target);}
    }

    /**
     * Returns a private staging file only after the WHOLE stream authenticates.
     * No restore or ZIP parser may consume partial decrypted segments. The caller
     * must validate the inner archive before changing any installation state.
     */
    static Path decrypt(Path ciphertext,Path privateDirectory,char[] passphrase,long maximumPlaintextBytes) throws IOException {
        validatePassphrase(passphrase);
        if(maximumPlaintextBytes<0)throw new IllegalArgumentException("Invalid backup limit");
        Path target=privateFile(privateDirectory,".verified");boolean complete=false;
        try {
            try(var raw=Files.newInputStream(ciphertext);var output=Files.newOutputStream(target)) {
                byte[] header=raw.readNBytes(MAGIC.length+SALT_BYTES);
                if(header.length!=MAGIC.length+SALT_BYTES||!Arrays.equals(MAGIC,Arrays.copyOf(header,MAGIC.length)))throw new IOException();
                byte[] salt=Arrays.copyOfRange(header,MAGIC.length,header.length);
                try(var decrypted=primitive(passphrase,salt).newDecryptingStream(raw,header)) {
                    byte[] buffer=new byte[64*1024];long count=0;
                    try {
                        for(int size;(size=decrypted.read(buffer))!=-1;) {
                            if(size>maximumPlaintextBytes-count)throw new IOException();
                            output.write(buffer,0,size);count+=size;
                        }
                    }finally {Arrays.fill(buffer,(byte)0);}
                }
            }
            complete=true;return target;
        }catch(GeneralSecurityException|IOException failure) {
            // A wrong passphrase, altered header, reordered segment, truncation
            // or trailing bytes has one opaque outcome. Never retain causes
            // that could expose payload, passphrase or filesystem paths.
            throw new IOException("INVALID_INSTANCE_BACKUP");
        }finally {if(!complete)removeIncomplete(target);}
    }

    private static void removeIncomplete(Path target) throws IOException {
        try {Files.deleteIfExists(target);}
        catch(IOException failure) {throw new IOException("INSTANCE_BACKUP_CLEANUP_FAILED");}
    }
    private static Path privateFile(Path directory,String suffix) throws IOException {
        return Files.createTempFile(directory,".archive-",suffix,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    private static byte[] header(byte[] salt) {
        var value=Arrays.copyOf(MAGIC,MAGIC.length+salt.length);System.arraycopy(salt,0,value,MAGIC.length,salt.length);return value;
    }
    private static void validatePassphrase(char[] value) {
        if(value==null||value.length<12||value.length>1024)throw new IllegalArgumentException("A backup passphrase must contain 12 to 1024 characters");
    }
    @AccessesPartialKey
    private static StreamingAead primitive(char[] passphrase,byte[] salt) throws GeneralSecurityException {
        var specification=new PBEKeySpec(passphrase,salt,ITERATIONS,256);byte[] key=null;
        try {
            key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
            var material=SecretBytes.copyFrom(key,InsecureSecretKeyAccess.get());
            return AesGcmHkdfStreaming.create(AesGcmHkdfStreamingKey.create(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB,material));
        }finally {specification.clearPassword();if(key!=null)Arrays.fill(key,(byte)0);}
    }
}
