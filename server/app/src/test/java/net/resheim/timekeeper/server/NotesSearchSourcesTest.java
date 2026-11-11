package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(NotesSearchSourcesTest.Profile.class)
class NotesSearchSourcesTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("searchsources",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:notes_search_sources_test"));}
    }
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @jakarta.enterprise.inject.Vetoed
    private static final class MetadataStore extends ServerStore {
        private final ServerStore delegate;int canonicalPayloads;
        MetadataStore(ServerStore delegate){this.delegate=delegate;}
        @Override public Optional<JsonNode> find(String type,UUID id){if(Set.of(VaultContent.TYPE,DayNotes.TYPE,WeekNotes.TYPE,EventNotes.TYPE).contains(type))canonicalPayloads++;return delegate.find(type,id);}
        @Override long resourceVersion(String type,UUID id){return delegate.resourceVersion(type,id);}
        @Override String restoreGeneration(){return delegate.restoreGeneration();}
    }
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private VaultCollection vault(){var vault=new VaultCollection(store,limits);vault.initialize();new NotesSearchChanges(store).snapshot();return vault;}
    private static NotesLuceneIndex.Hit hit(NotesLuceneIndex.Source source){return new NotesLuceneIndex.Hit(source.id(),source.path(),source.kind(),source.title(),source.sourceType(),source.sourceId(),source.sourceVersion(),source.sourceTag(),source.sourceHash(),new NotesLuceneIndex.Excerpt("",List.of()),new NotesLuceneIndex.Position(1,VaultPath.searchKey(source.path()),source.id()));}

    @Test void allCanonicalNoteKindsUseTheirOwnTitlesAndExcludeFrontmatter(){
        String owner="search-canonical-sources";
        var ids=store.transaction(owner,()->{
            var vault=vault();var result=new ArrayList<UUID>();
            result.add(VaultCollection.id(vault.put(new VaultPath("ordinary.md"),null,true,bytes("---\nprivate: excludedmetadata\n---\n\n# Ordinary title\n\nOwner-only body"))));
            result.add(VaultCollection.id(vault.put(new VaultPath("journal/2026-11-08.md"),null,true,bytes("# Journal heading\n\nDaily body"))));
            result.add(VaultCollection.id(vault.put(new VaultPath("journal/2026-W45.md"),null,true,bytes("# Week heading\n\nWeekly body"))));
            var key=new EventNotes.Key(UUID.randomUUID(),"12".repeat(32));var event=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event());
            event.set("event",object().put("uid","canonical-search").put("recurrenceId","").put("title","Event title"));
            result.add(VaultCollection.id(vault.put(new VaultPath("events/"+key.filename()),null,true,bytes("---\ntimekeeper-event: "+canonical(event)+"\n---\n# Other heading\n\nEvent body"))));
            return result;
        });
        var sources=new NotesSearchSources(store);var titles=new ArrayList<String>();
        for(UUID id:ids){
            var source=sources.load(owner,id);titles.add(source.title());assertFalse(source.text().body().contains("excludedmetadata"));assertFalse(source.text().body().contains("canonical-search"));
            store.transaction(owner,()->{var counted=new MetadataStore(store);var current=new NotesSearchSources(counted).current(hit(source));assertEquals(0,counted.canonicalPayloads);assertEquals(id.toString(),current.path("id").asText());assertFalse(current.has("body"));assertFalse(current.has("data"));return null;});
        }
        assertEquals(List.of("ordinary","","","Event title"),titles);
        assertFalse(sources.state(owner).toString().contains(owner));assertNull(store.currentConnection());
    }
    @Test void finalValidationDetectsCanonicalWritersThatMissedTheDerivedCatalogHook(){
        String owner="search-untracked-canonical";
        UUID id=store.transaction(owner,()->VaultCollection.id(vault().put(new VaultPath("note.md"),null,true,bytes("# Old private title\n\nOld text"))));
        var sources=new NotesSearchSources(store);var source=sources.load(owner,id);var before=sources.state(owner);
        store.transaction(owner,()->{
            var content=new VaultContent(store);content.save(source.sourceId(),etag(content.metadata(source.sourceId())),bytes("# New title\n\nNew text"),limits.markdownBytes());
            assertEquals(before.changes(),new NotesSearchChanges(store).snapshot());
            assertEquals(503,assertThrows(ApiFailure.class,()->sources.current(hit(source))).status);return null;
        });
        assertEquals(503,assertThrows(ApiFailure.class,()->sources.load(owner,id)).status);
    }
    @Test void repeatableReadsDoNotAcquireTheOwnerWriteLockAndCannotWrite(){
        String owner="search-read-snapshot";UUID id=store.transaction(owner,()->VaultCollection.id(vault().put(new VaultPath("note.md"),null,true,bytes("before"))));
        store.readSnapshot(owner,()->{
            var before=store.find(VaultIndex.ENTRY,id).orElseThrow();long sourceVersion=store.resourceVersion(before.path("sourceType").asText(),uuid(before.path("sourceId")));
            var changed=CompletableFuture.supplyAsync(()->store.transaction(owner,()->{
                var vault=vault();var entry=vault.get(id);return vault.put(VaultCollection.path(entry),vault.tag(entry),false,bytes("after"));
            }));
            try{assertEquals(sourceVersion+1,changed.get(5,TimeUnit.SECONDS).path("contentVersion").asLong());}catch(Exception failure){throw new AssertionError("Canonical writes must not wait for a search snapshot",failure);}
            assertEquals(sourceVersion,store.resourceVersion(before.path("sourceType").asText(),uuid(before.path("sourceId"))));
            assertThrows(IllegalStateException.class,()->store.transaction(owner,()->null));
            assertThrows(IllegalStateException.class,()->store.update("DELETE FROM tk_resource WHERE owner_id=?",owner));
            return null;
        });
        assertNull(store.currentConnection());assertEquals("after",new NotesSearchSources(store).load(owner,id).text().body());
    }
    @Test void boundedCatalogPagesIncludeAllUuidRangesAndNeverCrossOwnersOrTrash(){
        String owner="search-source-pages";var identifiers=store.transaction(owner,()->{
            var vault=vault();var result=new HashSet<UUID>();for(int i=0;i<270;i++)result.add(VaultCollection.id(vault.put(new VaultPath("note-"+i+".md"),null,true,bytes("body"))));return result;
        });
        var sources=new NotesSearchSources(store);var seen=new HashSet<UUID>();UUID after=null;
        while(true){var page=sources.entries(owner,after,31);if(page.isEmpty())break;assertTrue(page.size()<=31);for(JsonNode entry:page)if(NotesSearchChanges.searchable(entry))assertTrue(seen.add(uuid(entry.path("id"))));after=uuid(page.getLast().path("id"));}
        assertEquals(identifiers,seen);UUID selected=identifiers.iterator().next();
        store.transaction("search-source-other",()->{vault();return null;});assertNull(sources.load("search-source-other",selected));
        store.transaction(owner,()->{var vault=vault();var entry=vault.get(selected);vault.delete(entry,vault.tag(entry));return null;});assertNull(sources.load(owner,selected));
        assertEquals(404,assertThrows(ApiFailure.class,()->sources.state("search-never-created")).status);assertNull(store.currentConnection());
    }
}
