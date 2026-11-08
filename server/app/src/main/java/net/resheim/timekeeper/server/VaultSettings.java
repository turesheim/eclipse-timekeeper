package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Settings for the account's collection, independent of canonical note bodies. */
final class VaultSettings {
    static final int RECENT_DAYS=7, MAX_RECENT_DAYS=3650;
    static final Set<String> CURSORS=Set.of("default","line","underscore","solid");
    static final String TYPE="VAULT_SETTINGS";
    static final UUID ID=ImportLedger.identifier("vault","settings");
    private static final List<String> ROOTS=List.of("journalFolder","eventsFolder","assetsFolder");
    private static final Set<String> FIELDS=Set.of("journalFolder","eventsFolder","assetsFolder","syncObsidianConfig","hideJournalFolder","recentNotesDays","editorCursor");
    private final ServerStore store;
    VaultSettings(ServerStore store){this.store=store;}
    ObjectNode get(){return store.find(TYPE,ID).map(value->{var result=((ObjectNode)value).deepCopy();if(!result.has("editorCursor"))result.put("editorCursor","default");if(!result.has("recentNotesDays"))result.put("recentNotesDays",RECENT_DAYS);if(!result.has("hideJournalFolder"))result.put("hideJournalFolder",false);return result;}).orElseGet(()->object()
            .put("id",ID.toString()).put("ownerId",store.owner()).put("version",0)
            .put("journalFolder","journal").put("eventsFolder","events").put("assetsFolder","assets").put("syncObsidianConfig",false).put("hideJournalFolder",false).put("recentNotesDays",RECENT_DAYS).put("editorCursor","default"));}
    static boolean configurationPath(VaultPath path){return path.segments().stream().anyMatch(segment->VaultPath.searchKey(segment).equals(".obsidian"));}
    boolean allowed(VaultPath path){return !configurationPath(path)||get().path("syncObsidianConfig").asBoolean();}
    /** The service moves indexed roots in the same owner transaction as this write. */
    ObjectNode save(String expected,JsonNode input){
        var previous=get();ApiReplies.match(previous,expected);
        if(input==null||!input.isObject())throw ApiFailure.validation();
        input.fieldNames().forEachRemaining(field->{if(!FIELDS.contains(field))throw ApiFailure.validation();});
        var value=previous.deepCopy();boolean changed=false;
        for(String field:ROOTS)if(input.has(field)){
            if(!input.path(field).isTextual())throw ApiFailure.validation();
            var root=new VaultPath(input.path(field).asText());if(root.isRoot()||configurationPath(root))throw ApiFailure.validation();
            changed|=!value.path(field).asText().equals(root.value());value.put(field,root.value());
        }
        for(String field:List.of("syncObsidianConfig","hideJournalFolder"))if(input.has(field)){
            if(!input.path(field).isBoolean())throw ApiFailure.validation();
            changed|=value.path(field).asBoolean()!=input.path(field).asBoolean();
            value.put(field,input.path(field).asBoolean());
        }
        if(input.has("recentNotesDays")){
            var days=input.path("recentNotesDays");
            if(!days.isIntegralNumber()||!days.canConvertToInt()||days.asInt()<1||days.asInt()>MAX_RECENT_DAYS)throw ApiFailure.validation();
            changed|=value.path("recentNotesDays").asInt()!=days.asInt();value.put("recentNotesDays",days.asInt());
        }
        if(input.has("editorCursor")){
            var cursor=input.path("editorCursor");if(!cursor.isTextual()||!CURSORS.contains(cursor.asText()))throw ApiFailure.validation();
            changed|=!value.path("editorCursor").asText().equals(cursor.asText());value.put("editorCursor",cursor.asText());
        }
        var journal=new VaultPath(value.path("journalFolder").asText());var events=new VaultPath(value.path("eventsFolder").asText());
        if(journal.beneath(events)||events.beneath(journal))throw ApiFailure.validation();
        var assets=new VaultPath(value.path("assetsFolder").asText());
        if(journal.beneath(assets)||events.beneath(assets))throw ApiFailure.validation();
        if(!changed)return previous;
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        value.put("version",version+1).put("modifiedAt",store.now().toString());
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),TYPE,ID,version+1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),TYPE,ID,version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        if(List.of("journalFolder","eventsFolder","assetsFolder","syncObsidianConfig","hideJournalFolder").stream().anyMatch(field->!Objects.equals(previous.get(field),value.get(field))))new NotesSearchChanges(store).scopeChanged();
        return value;
    }
}
