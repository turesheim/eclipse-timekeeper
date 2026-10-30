package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultSettingsTest.Profile.class)
class VaultSettingsTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("settings",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_settings_test"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Test void defaultsAndDeploymentLimitsAreExplicit(){
        store.transaction("defaults",()->{var settings=new VaultSettings(store);assertEquals("journal",settings.get().path("journalFolder").asText());assertEquals("events",settings.get().path("eventsFolder").asText());assertFalse(settings.get().path("syncObsidianConfig").asBoolean());return null;});
        assertTrue(limits.markdownBytes()<=limits.fileBytes());assertTrue(limits.fileBytes()<=limits.totalBytes());assertTrue(limits.entries()>=4);assertTrue(limits.pageSize()>0);
    }
    @Test void configurationIsOptInOwnerPrivateAndGuarded(){
        String initial=store.transaction("a",()->etag(new VaultSettings(store).get()));
        String updated=store.transaction("a",()->{
            var settings=new VaultSettings(store);assertFalse(settings.allowed(new VaultPath("Projects/.OBSIDIAN/plugins/demo.js")));
            var saved=settings.save(initial,object().put("syncObsidianConfig",true));
            assertTrue(settings.allowed(new VaultPath(".obsidian/workspace.json")));assertEquals("journal",saved.path("journalFolder").asText());
            assertEquals(etag(saved),etag(settings.save(etag(saved),object().put("syncObsidianConfig",true))));return etag(saved);
        });
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultSettings(store).save(initial,object().put("syncObsidianConfig",false))));
        store.transaction("a",()->{assertEquals(updated,etag(new VaultSettings(store).get()));return null;});
        store.transaction("b",()->{assertFalse(new VaultSettings(store).get().path("syncObsidianConfig").asBoolean());return null;});
    }
    @Test void overlappingUnsafeOrPrivilegedConfigurationNeverCommits(){
        String tag=store.transaction("c",()->etag(new VaultSettings(store).get()));
        for(var input:List.of(object().put("journalFolder","../escape"),object().put("journalFolder","events/child"),
                object().put("eventsFolder","JOURNAL"),object().put("assetsFolder",".obsidian"),object().put("syncObsidianConfig","yes"),object().put("maxFileBytes",999999999))){
            assertThrows(ApiFailure.class,()->store.transaction("c",()->new VaultSettings(store).save(tag,input)));
        }
        store.transaction("c",()->{assertEquals(tag,etag(new VaultSettings(store).get()));return null;});
    }
    @Test void attachmentsMayRemainInsideTheJournalWithoutOverlappingEventAndJournalRoots(){
        store.transaction("nested-assets",()->{
            var settings=new VaultSettings(store);var saved=settings.save(etag(settings.get()),object().put("assetsFolder","journal/assets"));
            assertEquals("journal/assets",saved.path("assetsFolder").asText());return null;
        });
    }
}
