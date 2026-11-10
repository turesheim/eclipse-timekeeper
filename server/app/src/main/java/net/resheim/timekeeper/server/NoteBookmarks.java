package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.LocalDate;
import java.sql.Connection;
import java.sql.SQLException;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Owner-private page metadata, independent from source bytes and edit history. */
final class NoteBookmarks {
    static final Map<String,String> TYPES=Map.of(DayNotes.TYPE,"DAY_BOOKMARK",WeekNotes.TYPE,"WEEK_BOOKMARK",EventNotes.TYPE,"EVENT_BOOKMARK",VaultContent.TYPE,"VAULT_BOOKMARK");
    private final ServerStore store;
    NoteBookmarks(ServerStore store){this.store=store;}
    static VaultSources.Ref key(String type,String key){return NoteReferences.key(type,key);}
    VaultSources.Ref reference(String type,String key){return new NoteReferences(store).reference(type,key);}
    ObjectNode get(VaultSources.Ref ref){
        var stored=store.find(TYPES.get(ref.type()),ref.id()).orElse(null);
        if(stored!=null&&(!stored.path("sourceType").asText().equals(ref.type())||!stored.path("sourceKey").asText().equals(ref.key())||!stored.path("bookmarked").isBoolean()||stored.path("version").asLong()<1))throw new ApiFailure(503,"UNAVAILABLE");
        return object().put("sourceType",ref.type()).put("sourceKey",ref.key()).put("bookmarked",stored!=null&&stored.path("bookmarked").asBoolean()).put("version",stored==null?0:stored.path("version").asLong());
    }
    String tag(VaultSources.Ref ref){var value=object().put("restore",store.restoreGeneration());value.set("bookmark",get(ref));return etag(value);}
    ObjectNode save(VaultSources.Ref ref,String match,boolean bookmarked){
        VaultCollection.match(tag(ref),match);var previous=get(ref);if(previous.path("bookmarked").asBoolean()==bookmarked)return previous;
        long version=previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=previous.deepCopy().put("id",ref.id().toString()).put("ownerId",store.owner()).put("version",version+1).put("bookmarked",bookmarked);String type=TYPES.get(ref.type());
        if(version==0)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),type,ref.id(),1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),type,ref.id(),version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        // Namespace snapshots include presentation metadata. Content ETags and
        // modification dates deliberately remain owned by the source resource.
        if(new VaultState(store,store.vaultLimits()).get().path("initialized").asBoolean())new VaultState(store,store.vaultLimits()).changed(0,0,false);
        new NotesSearchChanges(store).bookmarkChanged(ref);
        store.change("PROFILE","UPDATED",store.owner());return get(ref);
    }
    ObjectNode annotate(ObjectNode entry){
        var result=entry.deepCopy();if(!Set.of("MARKDOWN","DAY","WEEK","EVENT").contains(entry.path("kind").asText()))return result.put("bookmarked",false);
        var ref=VaultSources.fromEntry(entry);var value=get(ref);return result.put("bookmarked",value.path("bookmarked").asBoolean()).put("bookmarkEtag",tag(ref));
    }
    Set<LocalDate> days(LocalDate first,LocalDate last){
        var result=new TreeSet<LocalDate>();for(var value:store.list(TYPES.get(DayNotes.TYPE),DayNotes.id(first),DayNotes.id(last)))if(value.path("bookmarked").asBoolean())result.add(DayNotes.date(value.path("sourceKey").asText()));return result;
    }
    boolean day(LocalDate date){return get(new VaultSources.Ref(DayNotes.TYPE,DayNotes.id(date),date.toString())).path("bookmarked").asBoolean();}
    Set<UUID> events(){
        var result=new HashSet<UUID>();for(var value:store.list(TYPES.get(EventNotes.TYPE)))if(value.path("bookmarked").asBoolean())result.add(uuid(value.path("id")));return result;
    }
    @FunctionalInterface interface Item {void accept(ObjectNode value)throws IOException;}
    static int archive(Connection connection,String owner,Item output)throws SQLException,IOException{
        int[] count={0};for(String source:new TreeSet<>(TYPES.keySet()))AccountArchive.records(connection,owner,TYPES.get(source),value->{if(value.path("bookmarked").asBoolean()){count[0]++;output.accept(object().put("sourceType",source).put("sourceKey",value.path("sourceKey").asText()));}});return count[0];
    }
    static void archive(Connection connection,String owner,JsonGenerator json)throws SQLException,IOException{
        json.writeArrayFieldStart("bookmarks");archive(connection,owner,value->json.writeTree(value));json.writeEndArray();
    }
    static void validate(JsonNode bookmarks,JsonNode vault){
        if(bookmarks.isMissingNode())return;if(!bookmarks.isArray()||bookmarks.size()>250000)throw ApiFailure.validation();var seen=new HashSet<String>();
        var files=new HashSet<String>();for(var entry:vault.path("entries"))if(entry.path("kind").asText().equals("MARKDOWN")&&entry.path("sourceType").asText().equals(VaultContent.TYPE))files.add(entry.path("sourceId").asText());
        for(var value:bookmarks){
            var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);
            if(!value.isObject()||!fields.equals(Set.of("sourceType","sourceKey"))||!value.path("sourceType").isTextual()||!value.path("sourceKey").isTextual())throw ApiFailure.validation();
            var ref=key(value.path("sourceType").asText(),value.path("sourceKey").asText());if(!seen.add(ref.type()+":"+ref.key()))throw ApiFailure.validation();
            if(ref.type().equals(VaultContent.TYPE)&&!files.contains(ref.id().toString()))throw ApiFailure.validation();
        }
    }
    void restore(JsonNode bookmarks){if(bookmarks.isMissingNode())return;for(var value:bookmarks){var ref=key(value.path("sourceType").asText(),value.path("sourceKey").asText());save(ref,tag(ref),true);}}
}
