package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.io.IOException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Client choices have independent versions and never rewrite note content. */
final class NoteClients {
    static final Map<String,String> TYPES=Map.of(DayNotes.TYPE,"DAY_CLIENT",WeekNotes.TYPE,"WEEK_CLIENT",EventNotes.TYPE,"EVENT_CLIENT",VaultContent.TYPE,"VAULT_CLIENT");
    private final ServerStore store;
    NoteClients(ServerStore store){this.store=store;}
    VaultSources.Ref reference(String type,String key){return new NoteReferences(store).reference(type,key);}
    ObjectNode get(VaultSources.Ref ref){
        var stored=store.find(TYPES.get(ref.type()),ref.id()).orElse(null);
        if(stored!=null&&(!stored.path("sourceType").asText().equals(ref.type())||!stored.path("sourceKey").asText().equals(ref.key())||stored.path("version").asLong()<1||!stored.has("clientId")))throw unavailable();
        var result=object().put("sourceType",ref.type()).put("sourceKey",ref.key()).put("version",stored==null?0:stored.path("version").asLong());
        UUID client=stored==null?null:client(stored.get("clientId"));if(client==null)result.putNull("clientId");else result.put("clientId",client.toString());return result;
    }
    private static ApiFailure unavailable(){return new ApiFailure(503,"UNAVAILABLE");}
    private static UUID client(JsonNode value){
        if(value==null||value.isNull())return null;
        if(!value.isTextual())throw unavailable();
        try{var id=UUID.fromString(value.asText());if(!id.toString().equals(value.asText()))throw unavailable();return id;}
        catch(IllegalArgumentException invalid){throw unavailable();}
    }
    String tag(VaultSources.Ref ref){var value=object().put("owner",store.owner()).put("restore",store.restoreGeneration());value.set("association",get(ref));return etag(value);}
    ObjectNode save(VaultSources.Ref ref,String match,UUID client){
        VaultCollection.match(tag(ref),match);var previous=get(ref);
        if(client!=null)store.find("CLIENT",client).orElseThrow(ApiFailure::notFound);
        if(Objects.equals(client,client(previous.get("clientId"))))return previous;
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        String type=TYPES.get(ref.type());var value=previous.deepCopy().put("id",ref.id().toString()).put("ownerId",store.owner()).put("version",version+1);
        if(client==null)value.putNull("clientId");else value.put("clientId",client.toString());
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),type,ref.id(),1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),type,ref.id(),version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        store.update("DELETE FROM tk_relation WHERE owner_id=? AND from_type=? AND from_id=? AND kind='client'",store.owner(),type,ref.id());
        if(client!=null)store.update("INSERT INTO tk_relation(owner_id,from_type,from_id,kind,to_type,to_id) VALUES(?,?,?,'client','CLIENT',?)",store.owner(),type,ref.id(),client);
        if(new VaultState(store,store.vaultLimits()).get().path("initialized").asBoolean())new VaultState(store,store.vaultLimits()).changed(0,0,false);
        store.change("PROFILE","UPDATED",store.owner());return get(ref);
    }
    @FunctionalInterface interface Item {void accept(ObjectNode value)throws IOException;}
    static int archive(Connection connection,String owner,Item output)throws SQLException,IOException{
        int[] count={0};for(String source:new TreeSet<>(TYPES.keySet()))AccountArchive.records(connection,owner,TYPES.get(source),value->{
            if(value.hasNonNull("clientId")){count[0]++;output.accept(object().put("sourceType",source).put("sourceKey",value.path("sourceKey").asText()).put("clientId",client(value.get("clientId")).toString()));}
        });return count[0];
    }
    static void archive(Connection connection,String owner,JsonGenerator json)throws SQLException,IOException{
        json.writeArrayFieldStart("noteClients");archive(connection,owner,value->json.writeTree(value));json.writeEndArray();
    }
    static void validate(JsonNode archive){
        var choices=archive.path("noteClients");if(choices.isMissingNode())return;
        if(!choices.isArray()||choices.size()>250000)throw ApiFailure.validation();
        var clients=new HashSet<String>();for(var value:archive.path("work").path("clients"))clients.add(value.path("id").asText());
        var files=new HashSet<String>();var events=new HashSet<String>();
        for(var entry:archive.path("vault").path("entries")){
            if(entry.path("kind").asText().equals("MARKDOWN")&&entry.path("sourceType").asText().equals(VaultContent.TYPE))files.add(entry.path("sourceId").asText());
            if(entry.path("kind").asText().equals("EVENT"))events.add(entry.path("sourceKey").asText());
        }
        for(var file:archive.path("journalFiles"))if(file.path("path").asText().startsWith("event-"))events.add(file.path("path").asText());
        var seen=new HashSet<String>();for(var value:choices){
            var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);
            if(!value.isObject()||!fields.equals(Set.of("sourceType","sourceKey","clientId"))||!value.path("sourceType").isTextual()||!value.path("sourceKey").isTextual()||!value.path("clientId").isTextual())throw ApiFailure.validation();
            var ref=NoteReferences.key(value.path("sourceType").asText(),value.path("sourceKey").asText());
            String id=ContractInputs.pathId(value.path("clientId").asText()).toString();
            if(!clients.contains(id)||!seen.add(ref.type()+":"+ref.key()))throw ApiFailure.validation();
            if(ref.type().equals(VaultContent.TYPE)&&!files.contains(ref.key())||ref.type().equals(EventNotes.TYPE)&&!events.contains(ref.key()))throw ApiFailure.validation();
        }
    }
    void restore(JsonNode choices,JsonNode mappings){
        if(choices.isMissingNode())return;var clients=new HashMap<UUID,UUID>();
        for(var value:mappings)if(value.path("entityType").asText().equals("CLIENT"))clients.put(uuid(value.path("sourceId")),uuid(value.path("id")));
        for(var value:choices){
            var ref=NoteReferences.key(value.path("sourceType").asText(),value.path("sourceKey").asText());UUID id=clients.get(uuid(value.path("clientId")));
            if(id==null)throw ApiFailure.validation();var previous=client(get(ref).get("clientId"));if(previous!=null&&!previous.equals(id))throw ApiFailure.conflict();
            save(ref,tag(ref),id);
        }
    }
}
