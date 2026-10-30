package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultIndexTest.Profile.class)
class VaultIndexTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_index_test");}
    }
    @Inject ServerStore store;
    private static VaultPath path(String value){return new VaultPath(value);}
    private static UUID id(com.fasterxml.jackson.databind.JsonNode value){return uuid(value.path("id"));}

    @Test void ownerPrivateIndexedTreesMoveWithoutChangingDocumentIdentity(){
        UUID note=store.transaction("a",()->{
            var index=new VaultIndex(store);index.root();
            var folder=index.create(path("Clients"),VaultIndex.Kind.FOLDER,null,null,null);
            index.create(path("Clients/Blåbær"),VaultIndex.Kind.FOLDER,null,null,null);
            var file=index.create(path("Clients/Blåbær/Plans.md"),VaultIndex.Kind.MARKDOWN,null,null,null);
            index.create(path("Archive"),VaultIndex.Kind.FOLDER,null,null,null);
            assertEquals(1,index.children(id(folder)).size());
            var moved=index.move(id(folder),path("Archive/Clients"));
            assertEquals(id(folder),id(moved));assertEquals(3,index.subtree(id(folder)).size());
            assertNull(index.at(path("Clients/Blåbær/Plans.md"),false));
            assertEquals(id(file),id(index.at(path("Archive/Clients/Blåbær/Plans.md"),false)));
            index.move(id(folder),path("Archive/CLIENTS"));
            assertEquals("Archive/CLIENTS/Blåbær/Plans.md",index.get(id(file)).path("path").asText());
            return id(file);
        });
        store.transaction("b",()->{
            var index=new VaultIndex(store);index.root();assertTrue(index.children(VaultIndex.ROOT).isEmpty());
            assertThrows(ApiFailure.class,()->index.get(note));
            index.create(path("Clients"),VaultIndex.Kind.FOLDER,null,null,null);return null;
        });
    }
    @Test void caseUnicodeAndOccupiedDestinationsNeverOverwriteExistingEntries(){
        UUID source=store.transaction("a",()->{
            var index=new VaultIndex(store);index.root();index.create(path("Collision"),VaultIndex.Kind.FOLDER,null,null,null);
            var original=index.create(path("Collision/Blåbær.md"),VaultIndex.Kind.MARKDOWN,null,null,null);
            return id(original);
        });
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultIndex(store).create(path("collision/Bla\u030abær.MD"),VaultIndex.Kind.MARKDOWN,null,null,null)));
        UUID other=store.transaction("a",()->id(new VaultIndex(store).create(path("Collision/Other.md"),VaultIndex.Kind.MARKDOWN,null,null,null)));
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultIndex(store).move(source,path("Collision/Other.md"))));
        store.transaction("a",()->{
            var index=new VaultIndex(store);assertEquals(source,id(index.at(path("Collision/Blåbær.md"),false)));
            assertEquals(other,id(index.at(path("Collision/Other.md"),false)));assertEquals(2,index.children(id(index.at(path("Collision"),false))).size());return null;
        });
    }
    @Test void deletedEntriesRecoverByIdentityButCannotReplaceNewFilesAtTheirOldPaths(){
        UUID file=store.transaction("a",()->{
            var index=new VaultIndex(store);index.root();var entry=index.create(path("Recover.md"),VaultIndex.Kind.MARKDOWN,null,null,null);
            var deleted=index.tombstone(id(entry),UUID.randomUUID());assertTrue(deleted.path("deleted").asBoolean());
            assertNull(index.at(path("Recover.md"),false));assertFalse(index.restore(id(entry)).path("deleted").asBoolean());
            index.tombstone(id(entry),UUID.randomUUID());index.create(path("Recover.md"),VaultIndex.Kind.MARKDOWN,null,null,null);return id(entry);
        });
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultIndex(store).restore(file)));
        store.transaction("a",()->{
            var index=new VaultIndex(store);assertNotEquals(file,id(index.at(path("Recover.md"),false)));
            assertTrue(index.get(file).path("deleted").asBoolean());return null;
        });
    }
    @Test void journalReferencesAreUniqueAndIndexedSeparatelyFromDisplayPaths(){
        UUID source=UUID.randomUUID();
        UUID entry=store.transaction("a",()->{
            var index=new VaultIndex(store);index.root();index.create(path("Journal"),VaultIndex.Kind.FOLDER,null,null,null);
            var note=index.create(path("Journal/2026-10-30.md"),VaultIndex.Kind.DAY,"DAY_NOTE",source,"2026-10-30");
            assertEquals(id(note),id(index.referenced("DAY_NOTE",source)));assertEquals(1,index.search("2026-10-30.",false).size());return id(note);
        });
        assertThrows(ApiFailure.class,()->store.transaction("a",()->new VaultIndex(store).create(path("Duplicate.md"),VaultIndex.Kind.DAY,"DAY_NOTE",source,"2026-10-30")));
        store.transaction("a",()->{assertEquals(entry,id(new VaultIndex(store).referenced("DAY_NOTE",source)));return null;});
        store.transaction("b",()->{assertNull(new VaultIndex(store).referenced("DAY_NOTE",source));return null;});
    }
    @Test void aFailedMoveTransactionRestoresEveryPathAndParentEdge(){
        UUID folder=store.transaction("a",()->{
            var index=new VaultIndex(store);index.root();var entry=index.create(path("Atomic"),VaultIndex.Kind.FOLDER,null,null,null);
            index.create(path("Atomic/Child.md"),VaultIndex.Kind.MARKDOWN,null,null,null);return id(entry);
        });
        assertThrows(IllegalStateException.class,()->store.transaction("a",()->{
            new VaultIndex(store).move(folder,path("Moved"));throw new IllegalStateException("Synthetic rollback");
        }));
        store.transaction("a",()->{
            var index=new VaultIndex(store);assertEquals(folder,id(index.at(path("Atomic"),false)));
            assertNotNull(index.at(path("Atomic/Child.md"),false));assertNull(index.at(path("Moved"),false));
            assertEquals(1,index.children(folder).size());return null;
        });
    }
}
