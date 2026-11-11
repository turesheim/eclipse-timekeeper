package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Short SQL snapshots copy canonical bytes; parsing and Lucene work follow after close. */
class NotesSearchSources {
    record State(NotesLuceneIndex.Identity identity,NotesSearchChanges.Snapshot changes,boolean configuration,String hiddenJournal){
        @Override public String toString(){return "NotesSearchSources.State[generation="+changes.generation()+"]";}
    }
    private record Raw(ObjectNode entry,long sourceVersion,byte[] bytes,String eventTitle,boolean bookmarked){
        @Override public String toString(){return "NotesSearchSources.Raw[]";}
    }
    private final ServerStore store;
    NotesSearchSources(ServerStore store){this.store=store;}
    State state(){
        var changes=new NotesSearchChanges(store).existing().orElseThrow(NotesSearchSources::unavailable);
        var vault=new VaultState(store,store.vaultLimits()).get();if(!vault.hasNonNull("collectionId")||!vault.path("initialized").asBoolean())throw unavailable();
        var settings=new VaultSettings(store).get();
        return new State(new NotesLuceneIndex.Identity(store.owner(),uuid(vault.path("collectionId")),changes.epoch(),store.restoreGeneration()),changes,
                settings.path("syncObsidianConfig").asBoolean(),settings.path("hideJournalFolder").asBoolean()?settings.path("journalFolder").asText():null);
    }
    State state(String owner){return store.readSnapshot(owner,this::state);}
    List<JsonNode> entries(String owner,UUID after,int limit){return store.readSnapshot(owner,()->store.resourcePage(VaultIndex.ENTRY,after,limit));}
    List<JsonNode> changes(String owner,NotesSearchChanges.Snapshot through,long after,int limit){return store.readSnapshot(owner,()->new NotesSearchChanges(store).page(through,after,limit));}

    NotesLuceneIndex.Source load(String owner,UUID id){
        Raw raw=store.readSnapshot(owner,()->capture(id));if(raw==null)return null;
        var entry=raw.entry();
        if(raw.bytes().length!=entry.path("byteLength").asLong()||!ArchiveJournalFiles.digest(raw.bytes()).equals(entry.path("sha256").asText()))throw unavailable();
        var projection=NotesSearchText.extract(raw.bytes());String title=switch(entry.path("kind").asText()){
            case "MARKDOWN"->NoteTitles.filenameTitle(new VaultPath(entry.path("path").asText()).name());case "EVENT"->raw.eventTitle();default->"";
        };
        return new NotesLuceneIndex.Source(id,entry.path("path").asText(),entry.path("kind").asText(),entry.path("sourceType").asText(),uuid(entry.path("sourceId")),
                raw.sourceVersion(),entry.path("contentTag").asText(),entry.path("sha256").asText(),raw.bytes().length,title,projection,null,raw.bookmarked());
    }
    private Raw capture(UUID id){
        var found=store.find(VaultIndex.ENTRY,id).orElse(null);if(!eligible(found))return null;
        var entry=((ObjectNode)found).deepCopy();var ref=reference(entry);var source=store.find(ref.type(),ref.id()).orElseThrow(NotesSearchSources::unavailable);
        if(source.path("deleted").asBoolean()||source.path("version").asLong()!=entry.path("contentVersion").asLong())throw unavailable();
        if(!ref.type().equals(VaultContent.TYPE)&&!VaultSources.fromStored(ref.type(),source).key().equals(ref.key()))throw unavailable();
        byte[] bytes=switch(ref.type()){
            case VaultContent.TYPE->VaultContent.bytes((ObjectNode)source);
            case DayNotes.TYPE->{if(dayDeleted(ref.id(),source.path("version").asLong()))throw unavailable();yield DayNotes.document(DayNotes.date(ref.key()),(ObjectNode)source);}
            case WeekNotes.TYPE->source.path("body").asText().getBytes(StandardCharsets.UTF_8);
            case EventNotes.TYPE->EventNotes.document(source);
            default->throw unavailable();
        };
        return new Raw(entry,source.path("version").asLong(),bytes,source.path("event").path("title").asText().strip(),new NoteBookmarks(store).get(ref).path("bookmarked").asBoolean());
    }
    /** Caller holds the final authorized owner transaction and rechecks the global generation. */
    ObjectNode current(NotesLuceneIndex.Hit hit){
        var entry=store.find(VaultIndex.ENTRY,hit.id()).orElse(null);if(!eligible(entry))throw unavailable();
        var ref=reference(entry);
        if(!entry.path("path").asText().equals(hit.path())||!entry.path("kind").asText().equals(hit.kind())||!ref.type().equals(hit.sourceType())||!ref.id().equals(hit.sourceId())
                ||entry.path("contentVersion").asLong()!=hit.sourceVersion()||!entry.path("contentTag").asText().equals(hit.sourceTag())||!entry.path("sha256").asText().equals(hit.sourceHash())
                ||store.resourceVersion(ref.type(),ref.id())!=hit.sourceVersion())throw unavailable();
        if(ref.type().equals(DayNotes.TYPE)&&dayDeleted(ref.id(),hit.sourceVersion()))throw unavailable();
        var result=new NoteBookmarks(store).annotate((ObjectNode)entry);String metadata=etag(result);result.put("etag",hit.sourceTag()).put("entryEtag",metadata);if(!hit.title().isBlank())result.put("title",hit.title());return result;
    }
    private boolean dayDeleted(UUID id,long version){
        return store.find(JournalState.Kind.JOURNAL_DELETE.name(),id).map(marker->marker.path("noteVersion").asLong()==version).orElse(false);
    }
    private static boolean eligible(JsonNode entry){return NotesSearchChanges.searchable(entry)&&!entry.path("deleted").asBoolean();}
    private static VaultSources.Ref reference(JsonNode entry){
        var ref=VaultSources.fromEntry(entry);String expected=switch(entry.path("kind").asText()){
            case "MARKDOWN"->VaultContent.TYPE;case "DAY"->DayNotes.TYPE;case "WEEK"->WeekNotes.TYPE;case "EVENT"->EventNotes.TYPE;default->"";
        };
        if(!ref.type().equals(expected))throw unavailable();
        UUID sourceId=switch(ref.type()){
            case VaultContent.TYPE->uuid(entry.path("sourceKey"));case DayNotes.TYPE->DayNotes.id(DayNotes.date(ref.key()));
            case WeekNotes.TYPE->WeekNotes.id(WeekNotes.week(ref.key()));case EventNotes.TYPE->EventNotes.filename(ref.key()).id();default->throw unavailable();
        };
        if(!sourceId.equals(ref.id()))throw unavailable();return ref;
    }
    private static ApiFailure unavailable(){return new ApiFailure(503,"NOTES_SEARCH_INDEXING");}
}
