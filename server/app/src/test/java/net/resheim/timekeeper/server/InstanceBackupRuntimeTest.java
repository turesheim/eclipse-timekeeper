package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.AccountStore.*;
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
    @Inject AccountStore accounts;
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @TempDir Path directory;
    @Test void realRuntimeConfigurationAndDatabaseProduceACompleteAuthenticatedArchive() throws Exception {
        String code=AccountPasswords.secret();
        accounts.tx(c->{execute(c,"INSERT INTO tk_auth_challenge VALUES(?,'SETUP','a',?,FALSE)",hash(code),java.time.Instant.now().plusSeconds(600).toString());return null;});
        accounts.complete(code,"runtime-administrator","Synthetic runtime account password","Administrator",false);
        store.transaction("a",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();vault.mkdir(new VaultPath("Notes"));
            vault.put(new VaultPath("Notes/Exact.md"),null,true,"---\r\nunknown: true\r\n---\r\n[[Wiki]]  \r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            vault.put(new VaultPath("Notes/asset.bin"),null,true,new byte[]{0,1,(byte)255});
            var day=vault.put(new VaultPath("journal/2026-10-30.md"),null,true,"Recoverable journal".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var labels=new DayLabels(store);var date=DayNotes.date("2026-10-30");labels.save(date,etag(labels.get(date)),"Independent label");vault.delete(day,vault.tag(day));
            vault.configure(etag(new VaultSettings(store).get()),object().put("journalFolder","Private/Journal"));return null;
        });
        store.transaction("b",()->{var vault=new VaultCollection(store,limits);vault.initialize();vault.put(new VaultPath("Private.md"),null,true,"Other owner's note".getBytes(java.nio.charset.StandardCharsets.UTF_8));return null;});
        char[] passphrase="synthetic runtime backup passphrase".toCharArray();
        try(var configuration=InstanceConfiguration.captureRuntime();var database=datasource.getConnection()) {
            assertEquals("h2",configuration.document().path("properties").path("quarkus.datasource.db-kind").asText());
            assertTrue(configuration.document().path("files").path("timekeeper.auth.tokens-file").size()>0);
            database.setTransactionIsolation(database.getMetaData().getDatabaseProductName().equals("PostgreSQL")?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_SERIALIZABLE);database.setAutoCommit(false);
            var originalNotes=notes(database);assertFalse(originalNotes.isEmpty());
            var source=object().put("serverVersion","2.0.0-SNAPSHOT").put("commit","unknown").put("databaseType","h2").put("packageId","standalone").putNull("publicOrigin");
            Path encrypted=InstanceBackupArchive.create(database,configuration,source,passphrase,directory);database.commit();
            try(var verified=InstanceBackupArchive.open(encrypted,passphrase,directory,100_000_000)) {
                assertEquals(23,verified.counts().size());assertTrue(verified.counts().get("tk_owner")>=2);assertEquals(1,verified.counts().get("tk_service_settings"));
                assertEquals(configuration.document(),verified.configuration().document());
                InstanceDatabaseImage.preflight(verified.database(),directory,restored->{assertEquals(originalNotes,notes(restored));});
            }
        }finally{java.util.Arrays.fill(passphrase,'\0');}
    }
    private static java.util.Map<String,String> notes(Connection connection)throws Exception{
        var result=new java.util.TreeMap<String,String>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT owner_id,entity_type,id,payload FROM tk_resource WHERE entity_type LIKE 'VAULT_%' OR entity_type IN ('DAY_NOTE','DAY_LABEL','DAY_NOTE_HISTORY','WEEK_NOTE','EVENT_NOTE','JOURNAL_ASSET')")){
            while(rows.next())result.put(rows.getString(1)+"/"+rows.getString(2)+"/"+rows.getString(3),rows.getString(4));
        }
        return result;
    }
}
