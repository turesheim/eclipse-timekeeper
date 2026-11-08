package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(NotesSearchSourcesTest.Profile.class)
class NotesSearchServiceTest {
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @TempDir Path temporary;
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private NotesSearchSources sources(){return new NotesSearchSources(store);}
    private UUID create(String owner,String text){return store.transaction(owner,()->{var vault=new VaultCollection(store,limits);vault.initialize();new NotesSearchChanges(store).snapshot();return VaultCollection.id(vault.put(new VaultPath("note.md"),null,true,bytes(text)));});}
    private NotesLuceneIndex.Page waitFor(NotesSearchService service,NotesSearchSources.State state,String text)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);IOException last=null;
        do{try{return service.search(state,NotesSearchQuery.parse(text),50,null,500);}catch(IOException unavailable){last=unavailable;Thread.sleep(10);}}while(System.nanoTime()<deadline);
        throw new AssertionError("Search cache did not become ready",last);
    }
    @Test void independentReplicasCatchUpAfterSavesAndKeepOwnerResultsPrivate()throws Exception{
        String owner="search-runtime-owner";UUID id=create(owner,"needle original");create("search-runtime-other","foreignneedle");var initial=sources().state(owner);
        try(var a=new NotesSearchService(store,temporary.resolve("a"));var b=new NotesSearchService(store,temporary.resolve("b"))){
            assertEquals(id,waitFor(a,initial,"needle").items().getFirst().id());assertEquals(id,waitFor(b,initial,"needle").items().getFirst().id());
            assertTrue(a.search(initial,NotesSearchQuery.parse("foreignneedle"),50,null,500).items().isEmpty());
            store.transaction(owner,()->{var vault=new VaultCollection(store,limits);var entry=vault.get(id);vault.put(VaultCollection.path(entry),vault.tag(entry),false,bytes("replacement text"));return null;});
            var changed=sources().state(owner);assertEquals(1,waitFor(a,changed,"replacement").items().size());assertEquals(1,waitFor(b,changed,"replacement").items().size());
            assertTrue(a.search(changed,NotesSearchQuery.parse("needle"),50,null,500).items().isEmpty());assertEquals("READY",a.status(changed).state());
        }
    }
    @Test void persistentCachesResumeAndMissingOrCorruptCachesRebuild()throws Exception{
        String owner="search-runtime-recovery";create(owner,"needle recoverable");var state=sources().state(owner);Path cache=temporary.resolve("persistent");
        try(var service=new NotesSearchService(store,cache)){assertEquals(1,waitFor(service,state,"needle").items().size());}
        try(var service=new NotesSearchService(store,cache)){assertEquals(1,waitFor(service,state,"needle").items().size());}
        try(var files=Files.walk(cache)){var segment=files.filter(path->path.getFileName().toString().startsWith("segments_")).findFirst().orElseThrow();Files.writeString(segment,"corrupt");}
        try(var service=new NotesSearchService(store,cache)){assertEquals(1,waitFor(service,state,"needle").items().size());}
        try(var files=Files.walk(cache)){for(var path:files.filter(path->path.getFileName().toString().startsWith("index-")).toList())try(var contents=Files.walk(path)){for(var file:contents.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        try(var service=new NotesSearchService(store,cache)){assertEquals(1,waitFor(service,state,"needle").items().size());}
    }
    @Test void sharedWritableDirectoriesAreRefusedWithoutDisruptingTheirOwner()throws Exception{
        String owner="search-runtime-lock";create(owner,"needle locked");var state=sources().state(owner);Path cache=temporary.resolve("locked");
        try(var first=new NotesSearchService(store,cache);var second=new NotesSearchService(store,cache)){
            assertEquals(1,waitFor(first,state,"needle").items().size());assertEquals("UNAVAILABLE",second.status(state).state());
            assertEquals(1,first.search(state,NotesSearchQuery.parse("needle"),50,null,500).items().size());first.close();
            assertEquals(1,waitFor(second,state,"needle").items().size());
        }
    }
    @Test void unusableStorageDoesNotBreakCanonicalSavingOrExistingFiles()throws Exception{
        String owner="search-runtime-bad-storage";UUID id=create(owner,"before");var state=sources().state(owner);Path cache=temporary.resolve("not-a-cache");Files.createDirectories(cache);Files.writeString(cache.resolve("keep.txt"),"existing data");
        try(var service=new NotesSearchService(store,cache)){
            assertEquals("UNAVAILABLE",service.status(state).state());assertEquals("existing data",Files.readString(cache.resolve("keep.txt")));
            store.transaction(owner,()->{var vault=new VaultCollection(store,limits);var entry=vault.get(id);var saved=vault.put(VaultCollection.path(entry),vault.tag(entry),false,bytes("saved despite cache failure"));assertArrayEquals(bytes("saved despite cache failure"),vault.read(saved));return null;});
        }
    }
    @Test void capacityFailureKeepsCanonicalSavesAvailableAndCanRecover()throws Exception{
        String owner="search-runtime-capacity";UUID id=create(owner,"before");var state=sources().state(owner);
        try(var service=new NotesSearchService(store,temporary.resolve("capacity"))){
            service.minimumFreeBytes=Long.MAX_VALUE;service.status(state);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!"CACHE_CAPACITY".equals(service.status(state).error())&&System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals("CACHE_CAPACITY",service.status(state).error());
            assertThrows(NotesLuceneIndex.Unavailable.class,()->service.search(state,NotesSearchQuery.parse("before"),50,null,0));
            store.transaction(owner,()->{var vault=new VaultCollection(store,limits);var entry=vault.get(id);vault.put(VaultCollection.path(entry),vault.tag(entry),false,bytes("saved despite full cache"));return null;});
            service.minimumFreeBytes=0;var changed=sources().state(owner);service.rebuild(changed);
            assertEquals(1,waitFor(service,changed,"saved").items().size());
        }
    }
    @Test void aLockedIndexIsNotDeletedAndSearchRecoversAfterItsWriterCloses()throws Exception{
        String owner="search-runtime-writer-lock";create(owner,"needle protected");var state=sources().state(owner);Path cache=temporary.resolve("writer-lock");
        try(var initial=new NotesSearchService(store,cache)){assertEquals(1,waitFor(initial,state,"needle").items().size());}
        Path index;try(var files=Files.walk(cache)){index=files.filter(path->path.getFileName().toString().startsWith("index-")).findFirst().orElseThrow();}
        try(var writer=new NotesLuceneIndex(index,state.identity());var service=new NotesSearchService(store,cache)){
            service.status(state);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(service.status(state).error()==null&&System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals("CACHE_UNAVAILABLE",service.status(state).error());assertTrue(Files.isDirectory(index));
            try(var files=Files.list(index)){assertTrue(files.anyMatch(path->path.getFileName().toString().startsWith("segments_")));}
            writer.close();service.rebuild(state);assertEquals(1,waitFor(service,state,"needle").items().size());
        }
    }
    @Test void repeatedOwnerEvictionKeepsEachAccountsCacheReadable()throws Exception{
        var states=new ArrayList<NotesSearchSources.State>();var ids=new ArrayList<UUID>();
        for(int i=0;i<4;i++){String owner="search-runtime-eviction-"+i;ids.add(create(owner,"needle "+i));states.add(sources().state(owner));}
        try(var service=new NotesSearchService(store,temporary.resolve("eviction"))){
            service.maximumOwners=1;
            for(int round=0;round<12;round++)for(var state:states)service.status(state);
            for(int i=0;i<states.size();i++)assertEquals(ids.get(i),waitFor(service,states.get(i),"needle").items().getFirst().id());
        }
    }
    @Test void deletedOwnersArePurgedAndCachePermissionsArePrivate()throws Exception{
        String owner="search-runtime-delete";create(owner,"needle private");var state=sources().state(owner);Path cache=temporary.resolve("deleted");
        try(var service=new NotesSearchService(store,cache)){
            assertEquals(1,waitFor(service,state,"needle").items().size());Path folder=cache.resolve(ArchiveJournalFiles.digest(owner.getBytes(StandardCharsets.UTF_8)));assertTrue(Files.isDirectory(folder));
            if(Files.getFileStore(cache).supportsFileAttributeView("posix"))try(var paths=Files.walk(cache)){for(var path:paths.toList())assertEquals(PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"),Files.getPosixFilePermissions(path));}
            store.transaction(owner,()->{
                for(String table:List.of("tk_relation","tk_external_reference","tk_active","tk_resource","tk_receipt","tk_change","tk_locale","tk_account"))store.update("DELETE FROM "+table+" WHERE owner_id=?",owner);
                store.update("DELETE FROM tk_owner WHERE id=?",owner);return null;
            });long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(Files.exists(folder)&&System.nanoTime()<deadline)Thread.sleep(20);assertFalse(Files.exists(folder));
        }
    }
}
