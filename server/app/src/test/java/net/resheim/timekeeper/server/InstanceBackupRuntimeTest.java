package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.sql.Connection;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(InstanceBackupRuntimeTest.Profile.class)
class InstanceBackupRuntimeTest {
    public static class Profile implements QuarkusTestProfile {
        public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:instance_runtime_tests");}
    }
    @Inject AgroalDataSource datasource;
    @TempDir Path directory;
    @Test void realRuntimeConfigurationAndDatabaseProduceACompleteAuthenticatedArchive() throws Exception {
        char[] passphrase="synthetic runtime backup passphrase".toCharArray();
        try(var configuration=InstanceConfiguration.captureRuntime();var database=datasource.getConnection()) {
            assertEquals("h2",configuration.document().path("properties").path("quarkus.datasource.db-kind").asText());
            assertTrue(configuration.document().path("files").path("timekeeper.auth.tokens-file").size()>0);
            database.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);database.setAutoCommit(false);
            var source=object().put("serverVersion","2.0.0-SNAPSHOT").put("commit","unknown").put("databaseType","h2").put("packageId","standalone").putNull("publicOrigin");
            Path encrypted=InstanceBackupArchive.create(database,configuration,source,passphrase,directory);database.commit();
            try(var verified=InstanceBackupArchive.open(encrypted,passphrase,directory,100_000_000)) {
                assertEquals(23,verified.counts().size());assertTrue(verified.counts().get("tk_owner")>=2);assertEquals(1,verified.counts().get("tk_service_settings"));
                assertEquals(configuration.document(),verified.configuration().document());
            }
        }finally{java.util.Arrays.fill(passphrase,'\0');}
    }
}
