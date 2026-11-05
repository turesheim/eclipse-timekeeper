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
        store.transaction("defaults",()->{var settings=new VaultSettings(store);assertEquals(7,settings.get().path("recentNotesDays").asInt());assertEquals("journal",settings.get().path("journalFolder").asText());assertEquals("events",settings.get().path("eventsFolder").asText());assertFalse(settings.get().path("syncObsidianConfig").asBoolean());return null;});
        assertTrue(limits.markdownBytes()<=limits.fileBytes());assertTrue(limits.fileBytes()<=limits.totalBytes());assertTrue(limits.entries()>=4);assertTrue(limits.pageSize()>0);
    }
    @Test void recentRangeIsTypedBoundedOwnerPrivateAndBackwardsCompatible(){
        store.transaction("recent-settings",()->{
            var settings=new VaultSettings(store);var current=settings.save(etag(settings.get()),object().put("recentNotesDays",1));
            assertEquals(1,current.path("recentNotesDays").asInt());assertEquals(etag(current),etag(settings.save(etag(current),object().put("recentNotesDays",1))));
            for(var invalid:List.of(object().put("recentNotesDays",0),object().put("recentNotesDays",3651),object().put("recentNotesDays",1.5),object().put("recentNotesDays","7"),object().putNull("recentNotesDays")))
                assertEquals(422,assertThrows(ApiFailure.class,()->settings.save(etag(current),invalid)).status);
            assertEquals(412,assertThrows(ApiFailure.class,()->settings.save("\"stale\"",object().put("recentNotesDays",2))).status);
            // A pre-feature settings row receives the default without a data migration.
            var old=current.deepCopy();old.remove("recentNotesDays");store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=?",old.toString(),store.owner(),VaultSettings.TYPE,VaultSettings.ID);
            assertEquals(7,settings.get().path("recentNotesDays").asInt());return null;
        });
        store.transaction("recent-settings-other",()->{assertEquals(7,new VaultSettings(store).get().path("recentNotesDays").asInt());return null;});
    }
    @Test void cursorIsTypedGuardedOwnerPrivateAndDefaultsForOlderSettings(){
        store.transaction("cursor-settings",()->{
            var settings=new VaultSettings(store);assertEquals("default",settings.get().path("editorCursor").asText());
            for(String cursor:List.of("line","underscore","solid","default")){
                var value=settings.save(etag(settings.get()),object().put("editorCursor",cursor));assertEquals(cursor,value.path("editorCursor").asText());assertEquals(etag(value),etag(settings.save(etag(value),object().put("editorCursor",cursor))));
            }
            for(var input:List.of(object().put("editorCursor","block"),object().put("editorCursor",1),object().putNull("editorCursor")))assertEquals(422,assertThrows(ApiFailure.class,()->settings.save(etag(settings.get()),input)).status);
            var old=settings.get();old.remove("editorCursor");store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=?",old.toString(),store.owner(),VaultSettings.TYPE,VaultSettings.ID);assertEquals("default",settings.get().path("editorCursor").asText());return null;
        });
        store.transaction("other-cursor",()->{assertEquals("default",new VaultSettings(store).get().path("editorCursor").asText());return null;});
    }
    @Test void journalVisibilityIsAnOwnerPrivateGuardedBooleanPreference(){
        var tag=store.transaction("visibility",()->{
            var settings=new VaultSettings(store);assertFalse(settings.get().path("hideJournalFolder").asBoolean());
            return etag(settings.save(etag(settings.get()),object().put("hideJournalFolder",true)));
        });
        store.transaction("visibility",()->{
            var settings=new VaultSettings(store);assertTrue(settings.get().path("hideJournalFolder").asBoolean());
            assertEquals(tag,etag(settings.save(tag,object().put("hideJournalFolder",true))));
            assertThrows(ApiFailure.class,()->settings.save(tag,object().put("hideJournalFolder","true")));
            assertThrows(ApiFailure.class,()->settings.save("\"stale\"",object().put("hideJournalFolder",false)));return null;
        });
        store.transaction("visibility-other",()->{assertFalse(new VaultSettings(store).get().path("hideJournalFolder").asBoolean());return null;});
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
