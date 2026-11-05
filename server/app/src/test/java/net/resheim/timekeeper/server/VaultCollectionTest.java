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
    @Test void storageReadsArePrivateDoNotInitializeAndIncludeLegacyContent(){
        store.transaction("storage-empty",()->{
            long revision=store.revision();var usage=new VaultStorage(store,limits).get();
            assertEquals(0,usage.path("usage").path("bytes").asLong());assertEquals(0,usage.path("usage").path("entries").asInt());
            assertTrue(store.list(VaultIndex.ENTRY).isEmpty());assertFalse(new VaultState(store,limits).get().path("initialized").asBoolean());assertEquals(revision,store.revision());return null;
        });
        store.transaction("storage-legacy",()->{
            new JournalAssets(store).save("legacy.png",null,new byte[]{1,2,3});long revision=store.revision();
            var usage=new VaultStorage(store,limits).get();assertEquals(3,usage.path("usage").path("bytes").asLong());assertEquals(1,usage.path("usage").path("entries").asInt());assertEquals(3,usage.path("largestAttachmentBytes").asInt());
            assertTrue(store.list(VaultIndex.ENTRY).isEmpty());assertEquals(revision,store.revision());return null;
        });
        store.transaction("storage-other",()->{assertEquals(0,new VaultStorage(store,limits).get().path("usage").path("bytes").asLong());return null;});
    }
    @Test void storageCategoriesAndTrashMatchQuotaCountersWithoutChangingVersions(){
        store.transaction("storage-categories",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();
            var note=vault.put(new VaultPath("ordinary.md"),null,true,bytes("note"));
            vault.put(new VaultPath("image.bin"),null,true,new byte[7]);
            vault.put(new VaultPath("journal/2026-11-05.md"),null,true,bytes("daily"));
            vault.put(new VaultPath("journal/2026-W45.md"),null,true,bytes("weekly"));
            var before=new VaultState(store,limits).get();long revision=store.revision();var report=new VaultStorage(store,limits).get();
            assertEquals(before.path("totalBytes").asLong(),report.path("usage").path("bytes").asLong());assertEquals(before.path("entries").asLong(),report.path("usage").path("entries").asLong());
            var categories=new HashMap<String,Long>();report.path("categories").forEach(row->categories.put(row.path("kind").asText(),row.path("bytes").asLong()));
            assertEquals(4L,categories.get("NOTES"));assertEquals(7L,categories.get("ATTACHMENTS"));assertEquals(6L,categories.get("WEEK"));assertTrue(categories.get("DAY")>=5);
            assertEquals(report.path("usage").path("bytes").asLong(),categories.values().stream().mapToLong(Long::longValue).sum());
            assertEquals(before,new VaultState(store,limits).get());assertEquals(revision,store.revision());assertEquals(4,report.path("usage").path("files").asInt());assertEquals(3,report.path("usage").path("folders").asInt());
            vault.delete(note,vault.tag(note));var deleted=new VaultStorage(store,limits).get();assertEquals(report.path("usage"),deleted.path("usage"));
            assertEquals(4,deleted.path("categories").get(5).path("bytes").asInt());assertEquals(0,deleted.path("categories").get(0).path("bytes").asInt());return null;
        });
    }
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
    @Test void defaultAssetsAreNestedAndLegacyDefaultMovesWithoutRewritingContent(){
        store.transaction("nested-default",()->{
            var assets=new JournalAssets(store);var asset=assets.save("pixel.png",null,new byte[]{1,2,3});
            var vault=new VaultCollection(store,limits);vault.initialize();
            var entry=vault.get(new VaultPath("journal/assets/pixel.png"));var id=VaultCollection.id(entry);
            assertEquals("journal/assets",new VaultSettings(store).get().path("assetsFolder").asText());
            assertEquals(etag(asset),vault.tag(entry));assertArrayEquals(new byte[]{1,2,3},vault.read(entry));
            vault.initialize();assertEquals(id,VaultCollection.id(vault.get(new VaultPath("journal/assets/pixel.png"))));
            return null;
        });
        store.transaction("legacy-default",()->{
            // Changing an unrelated setting does not make the old assets root
            // a custom folder choice.
            var settings=new VaultSettings(store);settings.save(etag(settings.get()),object().put("syncObsidianConfig",true));
            var assets=new JournalAssets(store);var asset=assets.save("old.png",null,new byte[]{4,5,6});
            var index=new VaultIndex(store);index.root();var catalog=new VaultCatalog(store,limits);
            for(String root:List.of("journal","events","assets"))catalog.folders(new VaultPath(root),true);
            var ref=VaultSources.fromStored(JournalAssets.TYPE,asset);
            var legacy=index.create(new VaultPath("assets/old.png"),VaultIndex.Kind.ASSET,ref.type(),ref.id(),ref.key());
            index.content(VaultCollection.id(legacy),new VaultSources(store,limits,ref).snapshot(),3);
            new VaultState(store,limits).changed(3,1,true);new VaultState(store,limits).initialized();
            var vault=new VaultCollection(store,limits);vault.initialize();
            assertEquals(VaultCollection.id(legacy),VaultCollection.id(vault.get(new VaultPath("journal/assets/old.png"))));
            assertArrayEquals(new byte[]{4,5,6},vault.read(vault.get(new VaultPath("journal/assets/old.png"))));
            assertEquals(etag(asset),vault.tag(vault.get(new VaultPath("journal/assets/old.png"))));
            assertTrue(settings.get().path("syncObsidianConfig").asBoolean());
            // A later explicit choice remains authoritative after the upgrade.
            vault.configure(etag(settings.get()),object().put("assetsFolder","assets"));vault.initialize();
            assertEquals("assets",settings.get().path("assetsFolder").asText());return null;
        });
    }
    @Test void explicitAssetFoldersAndExistingDestinationsRemainUntouched(){
        store.transaction("explicit-assets",()->{
            var settings=new VaultSettings(store);settings.save(etag(settings.get()),object().put("assetsFolder","Shared"));
            var vault=new VaultCollection(store,limits);vault.initialize();assertEquals("Shared",settings.get().path("assetsFolder").asText());return null;
        });
        for(String journal:List.of(String.join("/",Collections.nCopies(VaultPath.MAX_DEPTH,"x")),String.join("/",Collections.nCopies(4,"ø".repeat(127)))))
            store.transaction("bounded-assets-"+journal.length(),()->{
                var settings=new VaultSettings(store);settings.save(etag(settings.get()),object().put("journalFolder",journal));
                new VaultCollection(store,limits).initialize();assertEquals("assets",settings.get().path("assetsFolder").asText());return null;
            });
        store.transaction("occupied-assets",()->{
            var index=new VaultIndex(store);index.root();var catalog=new VaultCatalog(store,limits);
            for(String root:List.of("journal/assets","events","assets"))catalog.folders(new VaultPath(root),true);
            new VaultState(store,limits).initialized();new VaultCollection(store,limits).initialize();
            assertEquals("assets",new VaultSettings(store).get().path("assetsFolder").asText());return null;
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
