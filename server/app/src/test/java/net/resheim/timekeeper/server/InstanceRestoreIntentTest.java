package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceRestoreIntentTest {
    @TempDir Path directory;
    private final char[] phrase="Private transient restore passphrase".toCharArray();
    private final UUID generation=UUID.randomUUID();
    private InstanceRestoreIntent.Spec spec(){return new InstanceRestoreIntent.Spec(generation,UUID.randomUUID(),5,"bootstrap",UUID.randomUUID(),null,new InstanceRestoreDatabase.Deployment("dagsverk","https://destination.example:8443","AUTO",true));}
    InstanceBackupArchive.Verified source() throws Exception {
        var values=InstanceConfigurationTest.base();Path input=directory.resolve("source-token.json");Files.writeString(input,"private recovered file bytes");values.put("timekeeper.auth.tokens-file",input.toString());
        try(var c=InstanceDatabaseImageTest.database();var config=InstanceConfiguration.capture(values,Map.of())) {
            InstanceAuthenticationPreflightTest.seed(c);
            Path archive=InstanceBackupArchive.create(c,config,object().put("serverVersion","2.0.0").put("commit","0".repeat(40)).put("databaseType","h2").put("packageId","timekeeper").put("publicOrigin","https://source.example"),phrase,directory);
            return InstanceBackupArchive.open(archive,phrase,directory,10_000_000);
        }
    }
    private InstanceConfiguration.Snapshot target() throws Exception {var values=InstanceConfigurationTest.base();values.put("quarkus.datasource.password","private destination DB password");return InstanceConfiguration.capture(values,Map.of());}
    @Test void durablePrivateIntentSurvivesClosingOriginalDecryptionWithoutSavingPassphrase() throws Exception {
        var intent=new InstanceRestoreIntent(directory.resolve("intents"),10_000_000);byte[] database;
        try(var source=source();var target=target();var prepared=intent.prepare(spec(),source,target,Instant.now())) {
            database=Files.readAllBytes(source.database());assertEquals(generation,prepared.spec().generation());assertFalse(prepared.toString().contains("password"));
            assertEquals("private destination DB password",prepared.target().document().path("properties").path("quarkus.datasource.password").asText());
        }
        try(var prepared=intent.open()) {
            assertArrayEquals(database,Files.readAllBytes(prepared.archive().database()));assertEquals(23,prepared.archive().counts().size());
            assertEquals("private recovered file bytes",new String(prepared.archive().configuration().files().values().iterator().next(),java.nio.charset.StandardCharsets.UTF_8));
            try(var c=DriverManager.getConnection("jdbc:h2:mem:unconfirmed")){ServerSchema.initializeOrValidate(c);assertEquals("INSTANCE_RESTORE_NOT_AUTHORIZED",assertThrows(ApiFailure.class,()->prepared.phase(c)).code);}
        }
        try(var files=Files.walk(directory.resolve("intents"))) {
            for(Path path:files.toList()) {
                assertEquals(PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"),Files.getPosixFilePermissions(path));
                if(Files.isRegularFile(path))assertFalse(Files.readString(path).contains(new String(phrase)),"Passphrase was persisted");
            }
        }
    }
    @Test void uncommittedIntentCannotBeOverwrittenAndLockRecoversAfterAnotherProcessHalts() throws Exception {
        var intent=new InstanceRestoreIntent(directory.resolve("locks"),10_000_000);
        try(var source=source();var target=target();var prepared=intent.prepare(spec(),source,target,Instant.now())) {
            assertEquals("INSTANCE_RESTORE_BUSY",assertThrows(java.io.IOException.class,intent::open).getMessage());
            assertEquals("INSTANCE_RESTORE_BUSY",assertThrows(java.io.IOException.class,()->intent.prepare(spec(),source,target,Instant.now())).getMessage());
        }
        Path ready=directory.resolve("ready"),release=directory.resolve("release"),log=directory.resolve("peer.log");
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),InstanceRestoreIntentPeer.class.getName(),directory.resolve("locks").toString(),ready.toString(),release.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(!Files.exists(ready)&&process.isAlive()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(Files.exists(ready),Files.readString(log));assertEquals("INSTANCE_RESTORE_BUSY",assertThrows(java.io.IOException.class,intent::open).getMessage());
            Files.writeString(release,"release");assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));
        }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
        try(var prepared=intent.open()){assertEquals(generation,prepared.spec().generation());}
    }
    @Test void corruptFilesSymlinksHardlinksAndInsecurePermissionsCannotBeLoaded() throws Exception {
        Path root=directory.resolve("tamper");var intent=new InstanceRestoreIntent(root,10_000_000);
        try(var source=source();var target=target();var prepared=intent.prepare(spec(),source,target,Instant.now())) { }
        Path database=root.resolve(generation.toString()).resolve("database.json");byte[] original=Files.readAllBytes(database);
        Files.writeString(database,"corrupt");assertThrows(java.io.IOException.class,intent::open);Files.write(database,original);
        Path outside=directory.resolve("outside");Files.move(database,outside);Files.createSymbolicLink(database,outside);assertThrows(java.io.IOException.class,intent::open);Files.delete(database);Files.move(outside,database);
        Files.createLink(outside,database);assertThrows(java.io.IOException.class,intent::open);Files.delete(outside);
        Files.setPosixFilePermissions(database,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(java.io.IOException.class,intent::open);Files.setPosixFilePermissions(database,PosixFilePermissions.fromString("rw-------"));
        try(var prepared=intent.open()){assertEquals(generation,prepared.spec().generation());}
    }
    @Test void quotasAndIncompletePointersFailWithoutPublishingAnIntent() throws Exception {
        Path root=directory.resolve("quota");var intent=new InstanceRestoreIntent(root,100);
        try(var source=source();var target=target()){assertThrows(java.io.IOException.class,()->intent.prepare(spec(),source,target,Instant.now()));}
        assertFalse(Files.exists(root.resolve("prepared.json")));try(var paths=Files.list(root)){assertEquals(List.of(".lock"),paths.map(path->path.getFileName().toString()).toList());}
        Path invalid=directory.resolve("invalid");Files.createDirectory(invalid);Files.setPosixFilePermissions(invalid,PosixFilePermissions.fromString("rwxr-xr-x"));assertThrows(java.io.IOException.class,()->new InstanceRestoreIntent(invalid,100));
    }
}
