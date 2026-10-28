package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceBackupArchiveTest {
    @TempDir Path directory;
    private final char[] passphrase="private archive test passphrase".toCharArray();
    private ObjectNode source(){return object().put("serverVersion","2.0.0").put("commit","0".repeat(40)).put("databaseType","h2").put("packageId","timekeeper").putNull("publicOrigin");}
    private Path archive() throws Exception {
        var properties=InstanceConfigurationTest.base();Path tokens=directory.resolve("source-tokens.json");Files.writeString(tokens,"private token registry bytes");
        properties.put("timekeeper.auth.tokens-file",tokens.toString());properties.put("quarkus.datasource.password","private DB password");properties.put("quarkus.mailer.password","private SMTP password");
        try(var database=InstanceDatabaseImageTest.database();var configuration=InstanceConfiguration.capture(properties,Map.of())) {
            InstanceDatabaseImageTest.seed(database);return InstanceBackupArchive.create(database,configuration,source(),passphrase,directory);
        }
    }
    @Test void oneEncryptedArchiveContainsCompleteDatabaseConfigurationAndPrivateFiles() throws Exception {
        Path encrypted=archive(),stage;
        byte[] ciphertext=Files.readAllBytes(encrypted);assertEquals("TKIBK001",new String(ciphertext,0,8,StandardCharsets.US_ASCII));
        assertFalse(new String(ciphertext,StandardCharsets.ISO_8859_1).contains("private DB password"));
        try(var verified=InstanceBackupArchive.open(encrypted,passphrase,directory,10_000_000)) {
            assertEquals(22,verified.counts().size());assertTrue(verified.counts().values().stream().allMatch(count->count>0));
            assertEquals("private DB password",verified.configuration().document().path("properties").path("quarkus.datasource.password").asText());
            assertEquals("private SMTP password",verified.configuration().document().path("properties").path("quarkus.mailer.password").asText());
            String entry=verified.configuration().document().path("files").path("timekeeper.auth.tokens-file").get(0).asText();
            assertEquals("private token registry bytes",new String(verified.configuration().files().get(entry),StandardCharsets.UTF_8));
            assertEquals(InstanceBackupArchive.FORMAT,verified.manifest().path("format").asText());assertFalse(verified.toString().contains("password"));
            stage=verified.directory();assertTrue(Files.exists(verified.database()));
        }
        assertFalse(Files.exists(stage));assertTrue(Files.exists(encrypted));
    }
    @Test void wrongPassphraseMissingFilesChecksumErrorsUnknownVersionsAndUnsafeNamesAreRejectedWithoutStagingLeaks() throws Exception {
        Path encrypted=archive();rejects(encrypted,"wrong private passphrase".toCharArray(),10_000_000);
        rejects(encrypted,passphrase,100);
        for(Consumer<Map<String,byte[]>> change:List.<Consumer<Map<String,byte[]>>>of(
            entries->entries.remove("database.json"),
            entries->entries.put("configuration.json","{}".getBytes(StandardCharsets.UTF_8)),
            entries->entries.put("../host-file","private".getBytes(StandardCharsets.UTF_8)),
            entries->manifest(entries,value->value.put("format","future-format")),
            entries->manifest(entries,value->value.put("schema",999)),
            entries->manifest(entries,value->value.put("schema",new java.math.BigInteger("18446744073709551624"))),
            entries->manifest(entries,value->value.put("archiveId","0-0-0-0-0")),
            entries->manifest(entries,value->value.withObject("files").withObject("database.json").put("bytes",Long.MAX_VALUE))
        ))rejects(mutate(encrypted,change,false),passphrase,10_000_000);
    }
    @Test void sourcePathsAreMetadataAndCannotBeUsedToReadOrOverwriteHostFiles() throws Exception {
        Path encrypted=archive(),sentry=directory.resolve("host-sentry");Files.writeString(sentry,"host data retained");
        Path changed=mutate(encrypted,entries->{
            try {var configuration=(ObjectNode)MAPPER.readTree(entries.get("configuration.json"));configuration.withObject("properties").put("timekeeper.auth.tokens-file","/source-package-that-does-not-exist/tokens.json");entries.put("configuration.json",MAPPER.writeValueAsBytes(configuration));}
            catch(IOException failure){throw new AssertionError(failure);}
        },true);
        try(var verified=InstanceBackupArchive.open(changed,passphrase,directory,10_000_000)) {assertEquals(22,verified.counts().size());}
        assertEquals("host data retained",Files.readString(sentry));
        Path unsafe=mutate(encrypted,entries->{
            try {var configuration=(ObjectNode)MAPPER.readTree(entries.get("configuration.json"));configuration.withObject("properties").put("timekeeper.new-security-file",sentry.toString());entries.put("configuration.json",MAPPER.writeValueAsBytes(configuration));}
            catch(IOException failure){throw new AssertionError(failure);}
        },true);rejects(unsafe,passphrase,10_000_000);assertEquals("host data retained",Files.readString(sentry));
    }
    @Test void checksummedButIncompleteDatabaseStillFailsFullPreflight() throws Exception {
        Path encrypted=archive();Path changed=mutate(encrypted,entries->{
            try {var image=MAPPER.readTree(entries.get("database.json"));for(var table:image.path("tables"))if(table.path("name").asText().equals("tk_service_settings"))((com.fasterxml.jackson.databind.node.ArrayNode)table.path("rows")).removeAll();entries.put("database.json",MAPPER.writeValueAsBytes(image));}
            catch(IOException failure){throw new AssertionError(failure);}
        },true);rejects(changed,passphrase,10_000_000);
    }
    @Test void compressedSizeCannotBypassTheUncompressedArchiveBudget() throws Exception {
        Path encrypted=archive(),plain=InstanceBackupEncryption.decrypt(encrypted,directory,passphrase,10_000_000);long size=Files.size(plain),expanded=0;
        try(var zip=new ZipFile(plain.toFile())) {var entries=zip.entries();while(entries.hasMoreElements())expanded+=entries.nextElement().getSize();}
        assertTrue(expanded>size);Files.delete(plain);rejects(encrypted,passphrase,size);
    }
    @Test void duplicateManifestFieldsAndDuplicateZipNamesCannotSelectDifferentState() throws Exception {
        Path encrypted=archive();Path duplicateFields=mutate(encrypted,entries->{
            String manifest=new String(entries.get("manifest.json"),StandardCharsets.UTF_8);entries.put("manifest.json",("{\"format\":\""+InstanceBackupArchive.FORMAT+"\","+manifest.substring(1)).getBytes(StandardCharsets.UTF_8));
        },false);rejects(duplicateFields,passphrase,10_000_000);
        Path extra=mutate(encrypted,entries->entries.put("xatabase.json",entries.get("database.json")),false);
        Path plain=InstanceBackupEncryption.decrypt(extra,directory,passphrase,10_000_000);byte[] bytes=Files.readAllBytes(plain),name="xatabase.json".getBytes(StandardCharsets.US_ASCII);int replaced=0;
        for(int index=0;index<=bytes.length-name.length;index++)if(Arrays.equals(Arrays.copyOfRange(bytes,index,index+name.length),name)){bytes[index]='d';replaced++;}
        assertEquals(2,replaced);Files.write(plain,bytes);Path duplicateNames=InstanceBackupEncryption.encrypt(plain,directory,passphrase);Files.delete(plain);rejects(duplicateNames,passphrase,10_000_000);
    }
    private Path mutate(Path encrypted,Consumer<Map<String,byte[]>> change,boolean refreshIndex) throws Exception {
        Path plain=InstanceBackupEncryption.decrypt(encrypted,directory,passphrase,10_000_000);var entries=new LinkedHashMap<String,byte[]>();
        try(var zip=new ZipFile(plain.toFile())) {var iterator=zip.entries();while(iterator.hasMoreElements()){var entry=iterator.nextElement();try(var input=zip.getInputStream(entry)){entries.put(entry.getName(),input.readAllBytes());}}}
        change.accept(entries);
        if(refreshIndex)manifest(entries,value->{
            var index=value.withObject("files");for(var file:entries.entrySet())if(!file.getKey().equals("manifest.json")) {
                try {index.set(file.getKey(),object().put("bytes",file.getValue().length).put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getValue()))));}
                catch(Exception failure){throw new AssertionError(failure);}
            }
        });
        try(var zip=new ZipOutputStream(Files.newOutputStream(plain))) {for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        Path result=InstanceBackupEncryption.encrypt(plain,directory,passphrase);Files.delete(plain);return result;
    }
    private static void manifest(Map<String,byte[]> entries,Consumer<ObjectNode> change) {
        try {var manifest=(ObjectNode)MAPPER.readTree(entries.get("manifest.json"));change.accept(manifest);entries.put("manifest.json",MAPPER.writeValueAsBytes(manifest));}
        catch(IOException failure){throw new AssertionError(failure);}
    }
    private void rejects(Path encrypted,char[] phrase,long limit) throws Exception {
        List<Path> before;try(var files=Files.list(directory)){before=files.sorted().toList();}
        var failure=assertThrows(IOException.class,()->InstanceBackupArchive.open(encrypted,phrase,directory,limit));assertEquals("INVALID_INSTANCE_BACKUP",failure.getMessage());assertNull(failure.getCause());
        try(var files=Files.list(directory)){assertEquals(before,files.sorted().toList());}
    }
}
