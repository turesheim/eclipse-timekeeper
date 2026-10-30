package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultCollectionTest.Profile.class)
class VaultCollectionTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("collection",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_collection_test"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    private byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void nestedMovesAndRecoveryPreserveIdentityBytesAndGuardAgainstChangedDescendants(){
        store.transaction("nested",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();var folder=vault.mkdir(new VaultPath("Notes"));
            var file=vault.put(new VaultPath("Notes/Exact.md"),null,true,bytes("---\r\nunknown: x\r\n---\r\n[[Link]]  \r\n![[asset.bin]]"));
            var empty=vault.put(new VaultPath("Notes/empty.md"),null,true,new byte[0]);var binary=vault.put(new VaultPath("Notes/asset.bin"),null,true,new byte[]{0,1,(byte)255});
            UUID folderId=VaultCollection.id(folder),fileId=VaultCollection.id(file);byte[] original=vault.read(file);
            String guard=vault.tag(vault.get(folderId));vault.put(new VaultPath("Notes/empty.md"),vault.tag(empty),false,bytes(" "));
            assertEquals(412,assertThrows(ApiFailure.class,()->vault.move(vault.get(folderId),new VaultPath("Other"),guard)).status);
            folder=vault.move(vault.get(folderId),new VaultPath("Other"),vault.tag(vault.get(folderId)));
            assertEquals(fileId,VaultCollection.id(vault.get(new VaultPath("Other/Exact.md"))));assertArrayEquals(original,vault.read(vault.get(fileId)));
            assertArrayEquals(new byte[]{0,1,(byte)255},vault.read(vault.get(VaultCollection.id(binary))));
            var usage=new VaultState(store,limits).get();vault.delete(folder,vault.tag(folder));
            assertEquals(usage.path("totalBytes").asLong(),new VaultState(store,limits).get().path("totalBytes").asLong());
            var deleted=vault.get(folderId);assertTrue(deleted.path("deleted").asBoolean());vault.restore(deleted,vault.tag(deleted));
            assertArrayEquals(original,vault.read(vault.get(fileId)));assertFalse(vault.get(folderId).path("deleted").asBoolean());return null;
        });
    }
    @Test void settingsMoveCanonicalFoldersAtomicallyWithIndependentLabelsAndNestedAssets(){
        store.transaction("settings",()->{
            var config=new VaultSettings(store);config.save(etag(config.get()),object().put("assetsFolder","journal/assets"));
            var vault=new VaultCollection(store,limits);vault.initialize();
            var day=vault.put(new VaultPath("journal/2026-10-30.md"),null,true,bytes("Journal"));var week=vault.put(new VaultPath("journal/2026-W44.md"),null,true,bytes("Week"));
            UUID assets=VaultCollection.id(vault.get(new VaultPath("journal/assets")));
            var labels=new DayLabels(store);var date=DayNotes.date("2026-10-30");labels.save(date,etag(labels.get(date)),"Independent");
            vault.configure(etag(config.get()),object().put("journalFolder","Private/Journal"));
            assertEquals("Private/Journal/assets",config.get().path("assetsFolder").asText());
            assertEquals(assets,VaultCollection.id(vault.get(new VaultPath("Private/Journal/assets"))));
            assertEquals(VaultCollection.id(day),VaultCollection.id(vault.get(new VaultPath("Private/Journal/2026-10-30.md"))));
            assertEquals(VaultCollection.id(week),VaultCollection.id(vault.get(new VaultPath("Private/Journal/2026-W44.md"))));
            assertEquals("Independent",labels.get(date).path("text").asText());
            assertEquals(409,assertThrows(ApiFailure.class,()->vault.move(vault.get(VaultCollection.id(day)),new VaultPath("Renamed.md"),vault.tag(day))).status);return null;
        });
    }
    @Test void deletedJournalCanBeRecreatedAtItsReconfiguredFolderWithoutTouchingOldPathReuse(){
        store.transaction("moved-trash",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();var entry=vault.put(new VaultPath("journal/2026-10-29.md"),null,true,bytes("Original"));UUID identity=VaultCollection.id(entry);vault.delete(entry,vault.tag(entry));
            var settings=new VaultSettings(store);vault.configure(etag(settings.get()),object().put("journalFolder","PrivateJournal"));vault.mkdir(new VaultPath("journal"));vault.put(new VaultPath("journal/old.md"),null,true,bytes("Unrelated"));
            var recreated=vault.put(new VaultPath("PrivateJournal/2026-10-29.md"),null,true,bytes("Recreated"));assertEquals(identity,VaultCollection.id(recreated));assertArrayEquals(bytes("Recreated"),vault.read(recreated));assertArrayEquals(bytes("Unrelated"),vault.read(vault.get(new VaultPath("journal/old.md"))));return null;
        });
    }
    @Test void configurationRequiresOptInAndCannotDisappearOnOptOut(){
        store.transaction("config",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();
            assertEquals(403,assertThrows(ApiFailure.class,()->vault.mkdir(new VaultPath(".obsidian"))).status);
            var settings=new VaultSettings(store);vault.configure(etag(settings.get()),object().put("syncObsidianConfig",true));
            vault.mkdir(new VaultPath(".obsidian"));var config=vault.put(new VaultPath(".obsidian/settings.json"),null,true,bytes("{\"opaque\":true}"));
            assertEquals(409,assertThrows(ApiFailure.class,()->vault.configure(etag(settings.get()),object().put("syncObsidianConfig",false))).status);
            assertArrayEquals(bytes("{\"opaque\":true}"),vault.read(config));return null;
        });
    }
    @Test void searchPaginationDetectsNamespaceChangesAndDoesNotLoadBodies(){
        store.transaction("search",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();vault.put(new VaultPath("Alpha.md"),null,true,bytes("Secret body"));vault.put(new VaultPath("Beta.md"),null,true,new byte[0]);
            var first=vault.page(VaultIndex.ROOT,".md",false,0,1,null);assertEquals(1,first.path("items").size());assertFalse(first.toString().contains("Secret body"));
            assertEquals(1,vault.page(VaultIndex.ROOT,".md",false,1,1,first.path("snapshot").asText()).path("items").size());
            vault.put(new VaultPath("Gamma.md"),null,true,new byte[0]);assertEquals(412,assertThrows(ApiFailure.class,()->vault.page(VaultIndex.ROOT,".md",false,1,1,first.path("snapshot").asText())).status);return null;
        });
    }
    @Test void caseAliasesKeepCanonicalAncestorsAndCannotBypassJournalClassification(){
        store.transaction("case",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();vault.mkdir(new VaultPath("Notes"));
            var note=vault.put(new VaultPath("NOTES/First.md"),null,true,bytes("Text"));assertEquals("Notes/First.md",note.path("path").asText());
            var day=vault.put(new VaultPath("JOURNAL/2026-10-30.md"),null,true,bytes("Daily"));assertEquals("DAY",day.path("kind").asText());assertEquals("journal/2026-10-30.md",day.path("path").asText());
            assertEquals("Daily",new DayNotes(store).getContent(DayNotes.date("2026-10-30")).path("body").asText());
            var moved=vault.move(note,new VaultPath("nOtEs/Second.md"),vault.tag(note));assertEquals("Notes/Second.md",moved.path("path").asText());return null;
        });
    }
}
