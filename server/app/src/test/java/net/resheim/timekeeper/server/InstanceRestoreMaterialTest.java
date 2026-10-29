package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceRestoreMaterialTest {
    @TempDir Path directory;
    private static final String TOKEN="[{\"ownerId\":\"a\",\"sha256\":\""+"a".repeat(64)+"\",\"permissions\":[\"read\",\"write\"]}]";
    private final UUID generation=UUID.randomUUID();
    private InstanceRestoreIntent.Prepared prepared(boolean validToken) throws Exception {
        var values=InstanceConfigurationTest.base();
        for(var entry:Map.of("timekeeper.auth.tokens-file",validToken?TOKEN:"broken private registry","timekeeper.auth.setup-file","{\"private\":\"retired setup data\"}","quarkus.http.ssl.certificate.key-store-file","private source TLS bytes").entrySet()) {
            Path file=directory.resolve(entry.getKey().replace('.','-')+".json");Files.writeString(file,entry.getValue());values.put(entry.getKey(),file.toString());
        }
        values.put("quarkus.datasource.password","private source DB password");values.put("timekeeper.calendars.automatic","false");values.put("timekeeper.calendars.trusted-origins","http://calendar.internal:8080");char[] phrase="Synthetic recovered material phrase".toCharArray();Path encrypted;
        try(var c=InstanceDatabaseImageTest.database();var config=InstanceConfiguration.capture(values,Map.of())) {
            InstanceAuthenticationPreflightTest.seed(c);encrypted=InstanceBackupArchive.create(c,config,object().put("serverVersion","2.0.0").put("commit","0".repeat(40)).put("databaseType","h2").put("packageId","timekeeper").put("publicOrigin","https://source.example"),phrase,directory);
        }
        var spec=new InstanceRestoreIntent.Spec(generation,UUID.randomUUID(),4,"bootstrap",UUID.randomUUID(),null,new InstanceRestoreDatabase.Deployment("dagsverk","https://destination.example:8443","AUTO",true));
        try(var source=InstanceBackupArchive.open(encrypted,phrase,directory,10_000_000);var target=InstanceConfiguration.capture(InstanceConfigurationTest.base(),Map.of())) {
            return new InstanceRestoreIntent(directory.resolve("restores, ${missing} café"),10_000_000).prepare(spec,source,target,Instant.now());
        }
    }
    private Map<String,String> overlay(InstanceRestoreMaterial.Installed material) throws Exception {
        var values=new Properties();try(var input=Files.newInputStream(material.configuration())){values.load(input);}var result=new HashMap<String,String>();for(String name:values.stringPropertyNames())result.put(name,values.getProperty(name));return result;
    }
    @Test void immutablePrivateCopyKeepsSourcePathsInactiveAndRebackupIncludesEveryRecoveredByte() throws Exception {
        try(var prepared=prepared(true)) {
            var material=InstanceRestoreMaterial.install(prepared);assertEquals(material,InstanceRestoreMaterial.install(prepared));assertFalse(Files.exists(prepared.storage().resolve("active.json")));
            var destination=InstanceConfigurationTest.base();destination.put("quarkus.datasource.jdbc.url","jdbc:h2:/destination/dagsverk");destination.put("quarkus.datasource.password","destination DB password");
            var config=new io.smallrye.config.SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new io.smallrye.config.PropertiesConfigSource(destination,"destination",100),new io.smallrye.config.PropertiesConfigSource(overlay(material),"recovered",450)).build();
            assertEquals("jdbc:h2:/destination/dagsverk",config.getValue("quarkus.datasource.jdbc.url",String.class));assertEquals("destination DB password",config.getValue("quarkus.datasource.password",String.class));
            assertEquals("NONE",config.getValue("timekeeper.auth.setup-file",String.class));assertEquals("dagsverk",config.getValue("timekeeper.package-id",String.class));assertFalse(config.getValue("timekeeper.calendars.automatic",Boolean.class));assertEquals("http://calendar.internal:8080",config.getValue("timekeeper.calendars.trusted-origins",String.class));assertEquals(prepared.storage().toString(),config.getValue("timekeeper.instance-restore.storage",String.class));
            String tokens=config.getValue("timekeeper.auth.tokens-file",String.class);assertTrue(Path.of(tokens).startsWith(material.directory()));assertEquals(TOKEN,Files.readString(Path.of(tokens)));var registry=new TokenRegistry();registry.tokensFile=tokens;assertEquals("a",registry.load().get(0).owner());
            assertFalse(overlay(material).containsKey("quarkus.http.ssl.certificate.key-store-file"));assertFalse(Files.readString(material.configuration()).contains("private source DB password"));
            try(var backup=InstanceConfiguration.capture(config)) {
                assertTrue(backup.files().values().stream().anyMatch(bytes->new String(bytes,java.nio.charset.StandardCharsets.UTF_8).contains("private source DB password")),"Source configuration remains recoverable");
                for(byte[] source:prepared.archive().configuration().files().values())assertTrue(backup.files().values().stream().anyMatch(bytes->Arrays.equals(source,bytes)),"Every original source material file is re-backed up");
            }
            try(var paths=Files.walk(material.directory())){for(Path path:paths.toList())assertEquals(PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"),Files.getPosixFilePermissions(path));}
        }
    }
    @Test void changedInstalledFilesCannotBeReusedAndNoActivePointerIsInvented() throws Exception {
        try(var prepared=prepared(true)) {
            var material=InstanceRestoreMaterial.install(prepared);Path config=material.configuration();byte[] before=Files.readAllBytes(config);Files.writeString(config,"corrupt");
            assertThrows(java.io.IOException.class,()->InstanceRestoreMaterial.install(prepared));assertFalse(Files.exists(prepared.storage().resolve("active.json")));Files.write(config,before);assertEquals(material,InstanceRestoreMaterial.install(prepared));
        }
    }
    @Test void invalidSourceTokenRegistryCannotBeInstalledOrPublished() throws Exception {
        try(var prepared=prepared(false)) {
            assertEquals("INVALID_RECOVERED_TOKEN_REGISTRY",assertThrows(java.io.IOException.class,()->InstanceRestoreMaterial.install(prepared)).getMessage());
            assertFalse(Files.exists(prepared.storage().resolve("material").resolve(generation.toString())));assertFalse(Files.exists(prepared.storage().resolve("active.json")));
        }
    }
}
