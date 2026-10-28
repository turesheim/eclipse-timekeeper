package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceConfigurationTest {
    @TempDir Path directory;
    static Map<String,String> base() {
        return new HashMap<>(Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:private-source","quarkus.datasource.db-kind","h2","quarkus.datasource.username","sa",
            "timekeeper.auth.tokens-file","NONE","timekeeper.auth.setup-file","NONE"));
    }
    @Test void environmentAliasesAreCapturedThroughTheirCanonicalPropertiesOnly() throws Exception {
        Path token=directory.resolve("tokens.json");Files.writeString(token,"private configured hashes");
        var properties=base();properties.put("timekeeper.auth.tokens-file","${TIMEKEEPER_SERVER_TOKENS_FILE:NONE}");
        var environment=new io.smallrye.config.EnvConfigSource(Map.of("TIMEKEEPER_SERVER_TOKENS_FILE",token.toString()),300);
        var config=new io.smallrye.config.SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new io.smallrye.config.PropertiesConfigSource(properties,"application",100),environment).build();
        try(var snapshot=InstanceConfiguration.capture(config)) {
            assertEquals(1,snapshot.files().size());assertEquals(token.toString(),snapshot.document().path("properties").path("timekeeper.auth.tokens-file").asText());
            assertFalse(snapshot.document().path("properties").has("timekeeper.server.tokens.file"));
        }
        // Explicitly declared application inputs still require classification.
        properties.put("timekeeper.server.tokens.file",token.toString());
        var declared=new io.smallrye.config.SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new io.smallrye.config.PropertiesConfigSource(properties,"application",100),environment).build();
        rejects(()->InstanceConfiguration.capture(declared));
    }
    @Test void credentialsAndConfiguredFilesIncludingNamedTlsKeysAreCapturedExactly() throws Exception {
        Path token=directory.resolve("tokens.json"),key=directory.resolve("server.key"),certificate=directory.resolve("server.crt");
        Files.writeString(token,"private configured token hashes");Files.write(key,new byte[]{0,1,2,-1});Files.writeString(certificate,"private certificate bytes");
        var properties=base();properties.put("timekeeper.auth.tokens-file",token.toString());properties.put("quarkus.datasource.password","private DB password");properties.put("quarkus.mailer.password","private SMTP password");
        properties.put("quarkus.tls.\"server\".key-store.pem.\"pair\".key",key.toString());properties.put("quarkus.tls.\"server\".key-store.pem.\"pair\".cert",certificate.toString());
        var snapshot=InstanceConfiguration.capture(properties,Map.of());assertEquals(3,snapshot.files().size());
        assertEquals("private DB password",snapshot.document().path("properties").path("quarkus.datasource.password").asText());
        assertEquals("private SMTP password",snapshot.document().path("properties").path("quarkus.mailer.password").asText());
        var references=snapshot.document().path("files");
        assertArrayEquals(Files.readAllBytes(token),snapshot.files().get(references.path("timekeeper.auth.tokens-file").get(0).asText()));
        assertArrayEquals(Files.readAllBytes(key),snapshot.files().get(references.path("quarkus.tls.\"server\".key-store.pem.\"pair\".key").get(0).asText()));
        assertFalse(snapshot.toString().contains("password"));InstanceConfiguration.validate(snapshot.document(),snapshot.files().keySet());
    }
    @Test void certificateListsAndTrustDirectoriesHaveCompleteNonrecursiveInventory() throws Exception {
        Path trust=Files.createDirectory(directory.resolve("trust")),first=trust.resolve("first.crt"),second=trust.resolve("second.pem");
        Files.writeString(first,"first");Files.writeString(second,"second");var properties=base();
        properties.put("quarkus.http.ssl.certificate.files",first+","+second);properties.put("quarkus.tls.trust-store.pem.cert-dirs",trust.toString());
        var lists=Map.of("quarkus.http.ssl.certificate.files",List.of(first.toString(),second.toString()),"quarkus.tls.trust-store.pem.cert-dirs",List.of(trust.toString()));
        var snapshot=InstanceConfiguration.capture(properties,lists);assertEquals(4,snapshot.files().size());
        Files.createDirectory(trust.resolve("unclassified-subdirectory"));rejects(()->InstanceConfiguration.capture(properties,lists));
    }
    @Test void absentMissingUnknownOrOversizedInputsAreNeverSilentlyOmitted() throws Exception {
        var properties=base();properties.put("timekeeper.auth.tokens-file",directory.resolve("missing-private-file").toString());rejects(()->InstanceConfiguration.capture(properties,Map.of()));
        properties.put("timekeeper.auth.tokens-file","NONE");properties.put("timekeeper.new-security-file",directory.resolve("new-secret").toString());rejects(()->InstanceConfiguration.capture(properties,Map.of()));
        properties.remove("timekeeper.new-security-file");properties.remove("quarkus.datasource.jdbc.url");rejects(()->InstanceConfiguration.capture(properties,Map.of()));
        properties.put("quarkus.datasource.jdbc.url","jdbc:h2:mem:private-source");Path large=directory.resolve("too-large");
        try(var channel=Files.newByteChannel(large,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){channel.position(InstanceConfiguration.MAX_FILE_BYTES);channel.write(ByteBuffer.wrap(new byte[]{1}));}
        properties.put("timekeeper.auth.tokens-file",large.toString());rejects(()->InstanceConfiguration.capture(properties,Map.of()));
    }
    @Test void metadataCannotReferenceMissingDuplicateUnexpectedOrUnsafeArchiveFiles() throws Exception {
        Path token=directory.resolve("tokens.json");Files.writeString(token,"private");var properties=base();properties.put("timekeeper.auth.tokens-file",token.toString());
        var snapshot=InstanceConfiguration.capture(properties,Map.of());rejects(()->{InstanceConfiguration.validate(snapshot.document(),Set.of());return null;});
        rejects(()->{InstanceConfiguration.validate(snapshot.document(),Set.of("configuration/0000.json","configuration/extra.bin"));return null;});
        var altered=snapshot.document().deepCopy();altered.withObject("files").putArray("timekeeper.auth.tokens-file").add("../private-file");
        rejects(()->{InstanceConfiguration.validate(altered,Set.of("../private-file"));return null;});
    }
    @Test void externalConfigurationAndConfigurationEncryptionMaterialAreIncluded() throws Exception {
        Path external=directory.resolve("mail.properties");Files.writeString(external,"quarkus.mailer.password=private SMTP configuration\n");
        var properties=base();properties.put("quarkus.config.locations",external.toUri().toString());properties.put("smallrye.config.secret-handler.aes-gcm-nopadding.encryption-key","private configuration encryption key");
        var snapshot=InstanceConfiguration.capture(properties,Map.of("quarkus.config.locations",List.of(external.toUri().toString())));
        assertEquals(1,snapshot.files().size());assertArrayEquals(Files.readAllBytes(external),snapshot.files().values().iterator().next());
        assertEquals("private configuration encryption key",snapshot.document().path("properties").path("smallrye.config.secret-handler.aes-gcm-nopadding.encryption-key").asText());
    }
    @Test void newlyDeclaredApplicationInputsMustHaveBackupCoverage() throws Exception {
        var declaration=java.util.regex.Pattern.compile("@(?:org\\.eclipse\\.microprofile\\.config\\.inject\\.)?ConfigProperty\\([^;]*?name\\s*=\\s*\"([^\"]+)\"");int found=0;
        try(var files=Files.walk(Path.of("src/main/java/net/resheim/timekeeper/server"))) {
            for(Path file:files.filter(path->path.toString().endsWith(".java")).toList()) {
                var matcher=declaration.matcher(Files.readString(file));while(matcher.find()){found++;String name=matcher.group(1);assertTrue(InstanceConfiguration.coversDeclaredProperty(name),"Classify new persisted configuration input: "+name);}
            }
        }
        assertTrue(found>10);assertFalse(InstanceConfiguration.coversDeclaredProperty("timekeeper.new-security-directory"));
    }
    @Test void disabledOptionalInputPathsRemainDisabledWhenConfigConversionRemovesEmptyValues() throws Exception {
        var values=base();values.remove("timekeeper.auth.tokens-file");values.remove("timekeeper.auth.setup-file");
        var config=(org.eclipse.microprofile.config.Config)java.lang.reflect.Proxy.newProxyInstance(org.eclipse.microprofile.config.Config.class.getClassLoader(),new Class<?>[]{org.eclipse.microprofile.config.Config.class},(proxy,method,args)->{
            if(method.getName().equals("getConfigSources"))return java.util.List.of();
            if(method.getName().equals("getPropertyNames"))return values.keySet();
            if(method.getName().equals("getOptionalValue"))return Optional.ofNullable(values.get(args[0]));
            throw new UnsupportedOperationException();
        });
        try(var snapshot=InstanceConfiguration.capture(config)) {
            assertEquals("NONE",snapshot.document().path("properties").path("timekeeper.auth.tokens-file").asText());
            assertEquals("NONE",snapshot.document().path("properties").path("timekeeper.auth.setup-file").asText());assertTrue(snapshot.files().isEmpty());
        }
    }
    @Test void entrypointSecretFilesAndTheirResolvedValuesAreBothInventoried() throws Exception {
        Path database=directory.resolve("database-password"),smtp=directory.resolve("smtp-password");Files.writeString(database,"private DB secret\n");Files.writeString(smtp,"private SMTP secret\n");
        var values=base();values.put("quarkus.datasource.password","private DB secret");values.put("quarkus.mailer.password","private SMTP secret");
        var env=new io.smallrye.config.EnvConfigSource(Map.of("TIMEKEEPER_SERVER_DB_PASSWORD_FILE",database.toString(),"TIMEKEEPER_SMTP_PASSWORD_FILE",smtp.toString()),300);
        var config=new io.smallrye.config.SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new io.smallrye.config.PropertiesConfigSource(values,"application",100),env).build();
        try(var snapshot=InstanceConfiguration.capture(config)) {
            assertEquals(2,snapshot.files().size());assertEquals("private DB secret",snapshot.document().path("properties").path("quarkus.datasource.password").asText());
            assertTrue(snapshot.files().values().stream().anyMatch(bytes->Arrays.equals(bytes,"private DB secret\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            assertTrue(snapshot.files().values().stream().anyMatch(bytes->Arrays.equals(bytes,"private SMTP secret\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
    }
    @Test void nativeMaintenanceMarkerIsRuntimeGuardRatherThanRequiredArchiveInput() throws Exception {
        var env=new io.smallrye.config.EnvConfigSource(Map.of("TIMEKEEPER_MAINTENANCE_FILE",directory.resolve("absent-pending.json").toString()),300);
        var config=new io.smallrye.config.SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new io.smallrye.config.PropertiesConfigSource(base(),"application",100),env).build();
        try(var snapshot=InstanceConfiguration.capture(config)) {
            assertTrue(snapshot.files().isEmpty());
            assertEquals(directory.resolve("absent-pending.json").toString(),snapshot.document().path("properties").path("timekeeper.maintenance.file").asText());
        }
    }
    @FunctionalInterface private interface Attempt {Object run() throws Exception;}
    private void rejects(Attempt action){var failure=assertThrows(IOException.class,action::run);assertEquals("INVALID_INSTANCE_CONFIGURATION",failure.getMessage());assertNull(failure.getCause());assertFalse(failure.toString().contains(directory.toString()));}
}
