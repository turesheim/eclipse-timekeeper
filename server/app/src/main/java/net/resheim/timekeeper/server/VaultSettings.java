package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Settings for the account's collection, independent of canonical note bodies. */
final class VaultSettings {
    static final String TYPE="VAULT_SETTINGS";
    static final UUID ID=ImportLedger.identifier("vault","settings");
    private static final List<String> ROOTS=List.of("journalFolder","eventsFolder","assetsFolder");
    private static final Set<String> FIELDS=Set.of("journalFolder","eventsFolder","assetsFolder","syncObsidianConfig");
    private final ServerStore store;
    VaultSettings(ServerStore store){this.store=store;}
    ObjectNode get(){return store.find(TYPE,ID).map(value->((ObjectNode)value).deepCopy()).orElseGet(()->object()
            .put("id",ID.toString()).put("ownerId",store.owner()).put("version",0)
            .put("journalFolder","journal").put("eventsFolder","events").put("assetsFolder","assets").put("syncObsidianConfig",false));}
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
        if(input.has("syncObsidianConfig")){
            if(!input.path("syncObsidianConfig").isBoolean())throw ApiFailure.validation();
            changed|=value.path("syncObsidianConfig").asBoolean()!=input.path("syncObsidianConfig").asBoolean();
            value.put("syncObsidianConfig",input.path("syncObsidianConfig").asBoolean());
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
        return value;
    }
}
