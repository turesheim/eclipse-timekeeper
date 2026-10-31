package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Owner-private presentation preferences, independent of work and holiday settings. */
final class FocusAreas {
    static final String TYPE="FOCUS_SETTINGS";
    static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final ServerStore store;
    FocusAreas(ServerStore store){this.store=store;}
    ObjectNode get(){
        return store.find(TYPE,ID).map(value->((ObjectNode)value).deepCopy()).orElseGet(()->{
            var value=object().put("id",ID.toString()).put("ownerId",store.owner()).put("version",0);
            value.putArray("areas");return value;
        });
    }
    ObjectNode save(String match,JsonNode input){
        var previous=get();ApiReplies.match(previous,match);
        var ids=new HashSet<UUID>();var names=new HashSet<String>();
        var areas=MAPPER.createArrayNode();
        for(var area:input.path("areas")){
            UUID id=uuid(area.get("id"));String name=area.path("name").asText().strip();
            if(!ids.add(id)||name.isEmpty()||!names.add(name.toLowerCase(Locale.ROOT))||name.codePoints().anyMatch(Character::isISOControl))throw ApiFailure.validation();
            var copy=object().put("id",id.toString()).put("name",name);var clients=copy.putArray("clientIds");
            var seen=new HashSet<UUID>();
            for(var client:area.path("clientIds")){
                UUID clientId=uuid(client);if(!seen.add(clientId))throw ApiFailure.validation();
                store.find("CLIENT",clientId).orElseThrow(ApiFailure::notFound);clients.add(clientId.toString());
            }
            areas.add(copy);
        }
        if(previous.path("areas").equals(areas))return previous;
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=previous.deepCopy().put("version",version+1);value.set("areas",areas);
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,1,?)",store.owner(),TYPE,ID,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),TYPE,ID,version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        store.change("PROFILE","UPDATED",store.owner());return value;
    }
    /** Additive account restores use newly imported client identities, never source-owner IDs. */
    void restore(JsonNode source,JsonNode mappings,ContractInputs inputs){
        if(source.isMissingNode())return;
        var clients=new HashMap<String,String>();
        for(var mapping:mappings)if(mapping.path("entityType").asText().equals("CLIENT"))clients.put(mapping.path("sourceId").asText(),mapping.path("id").asText());
        var previous=get();var body=object();var areas=body.putArray("areas");
        for(var area:previous.path("areas")){
            var copy=((ObjectNode)area).deepCopy();var members=copy.putArray("clientIds");
            for(var client:area.path("clientIds"))if(store.find("CLIENT",uuid(client)).isPresent())members.add(client);
            areas.add(copy);
        }
        var names=new HashSet<String>();areas.forEach(area->names.add(area.path("name").asText().toLowerCase(Locale.ROOT)));
        var sourceIds=new HashSet<String>();
        for(var area:source){
            if(!sourceIds.add(area.path("id").asText()))throw ApiFailure.validation();
            String name=area.path("name").asText().strip();
            if(!names.add(name.toLowerCase(Locale.ROOT)))throw ApiFailure.conflict();
            var copy=object().put("id",UUID.randomUUID().toString()).put("name",name);var members=copy.putArray("clientIds");
            for(var client:area.path("clientIds")){
                String mapped=clients.get(client.asText());if(mapped==null)throw ApiFailure.validation();members.add(mapped);
            }
            areas.add(copy);
        }
        inputs.validate("FocusAreasInput",body);save(etag(previous),body);
    }
}
