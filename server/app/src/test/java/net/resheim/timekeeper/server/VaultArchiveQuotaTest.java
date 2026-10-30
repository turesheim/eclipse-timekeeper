package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipOutputStream;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultArchiveQuotaTest.Profile.class)
class VaultArchiveQuotaTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_archive_quota","timekeeper.notes.max-file-bytes","1000","timekeeper.notes.max-markdown-bytes","500","timekeeper.notes.max-total-bytes","10000","timekeeper.notes.max-entries","8");}
    }
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Test void portableImportCannotUseHistoricalBackfillToBypassDestinationQuotas()throws Exception{
        var document=object().put("format",1);var settings=document.putObject("settings").put("journalFolder","journal").put("eventsFolder","events").put("assetsFolder","assets").put("syncObsidianConfig",false);document.putArray("files");var entries=document.putArray("entries");
        entries.add(object().put("id",VaultIndex.ROOT.toString()).put("parentId",VaultIndex.ROOT.toString()).put("kind","FOLDER").put("path","").put("deleted",false));
        for(String name:List.of("journal","events","assets","one","two","three","four","five","six"))entries.add(object().put("id",UUID.randomUUID().toString()).put("parentId",VaultIndex.ROOT.toString()).put("kind","FOLDER").put("path",name).put("deleted",false));
        var archive=Files.createTempFile("vault-quota-",".zip");try{
            try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){}
            var failure=assertThrows(ApiFailure.class,()->store.transaction("quota-target",()->{VaultArchive.prepare(document,store);VaultArchive.restore(archive,document,store);return null;}));assertEquals(507,failure.status);
            store.transaction("quota-target",()->{assertTrue(store.list(VaultIndex.ENTRY).isEmpty());assertEquals(0,new VaultState(store,limits).get().path("entries").asInt());return null;});
        }finally{Files.deleteIfExists(archive);}
    }
}
