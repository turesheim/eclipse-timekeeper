package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Durable, content-free replay records. Every method runs inside the caller's
 * owner transaction; nothing here opens or publishes a Lucene index.
 */
final class NotesSearchChanges {
    static final String STATE="NOTES_SEARCH",CHANGE="NOTES_SEARCH_LOG";
    static final UUID ID=ImportLedger.identifier("notes-search","state");
    static final int RETAINED=8192,MAX_BATCH=256;
    private static final long SEQUENCE_PREFIX=0x7365617263680000L,MAXIMUM=9007199254740991L;
    private static final Set<String> KINDS=Set.of("MARKDOWN","DAY","WEEK","EVENT");
    private static final List<String> IDENTITY=List.of("id","path","kind","sourceType","sourceId","sourceKey",
            "deleted","contentTag","contentVersion","sha256","byteLength","createdAt","modifiedAt","title");
    private final ServerStore store;
    private final int retained;
    record Snapshot(UUID epoch,long generation,long historyFloor) { }
    static final class HistoryExpired extends RuntimeException {private static final long serialVersionUID=1L;}
    NotesSearchChanges(ServerStore store){this(store,RETAINED);}
    NotesSearchChanges(ServerStore store,int retained){if(retained<1||retained>RETAINED)throw new IllegalArgumentException("Invalid search history bound");this.store=store;this.retained=retained;}

    Snapshot snapshot(){return snapshot(ensure());}
    Optional<Snapshot> existing(){return store.find(STATE,ID).map(NotesSearchChanges::snapshot);}
    private static Snapshot snapshot(JsonNode value){return new Snapshot(uuid(value.path("epoch")),value.path("generation").asLong(),value.path("historyFloor").asLong());}
    private ObjectNode ensure(){
        var found=store.find(STATE,ID);if(found.isPresent())return ((ObjectNode)found.get()).deepCopy();
        var value=object().put("id",ID.toString()).put("ownerId",store.owner()).put("version",1)
                .put("epoch",UUID.randomUUID().toString()).put("generation",0).put("historyFloor",0);
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),STATE,ID,1,value.toString());return value;
    }
    static boolean searchable(JsonNode entry){return entry!=null&&KINDS.contains(entry.path("kind").asText());}
    void changed(JsonNode before,JsonNode after){
        if(!searchable(before)&&!searchable(after))return;
        boolean wasActive=searchable(before)&&!before.path("deleted").asBoolean(),active=searchable(after)&&!after.path("deleted").asBoolean();
        if(!wasActive&&!active)return;
        if(before!=null&&after!=null&&IDENTITY.stream().allMatch(field->Objects.equals(before.get(field),after.get(field))))return;
        if(before!=null&&before.path("kind").asText().equals("EVENT")||after!=null&&after.path("kind").asText().equals("EVENT"))new CalendarSearchState(store).changed();
        append(active?"UPSERT":"DELETE",uuid((after==null?before:after).path("id")));
    }
    void scopeChanged(){append("SCOPE",null);}
    void bookmarkChanged(VaultSources.Ref ref){
        var entry=new VaultIndex(store).referenced(ref.type(),ref.id());
        if(!searchable(entry)||entry.path("deleted").asBoolean())return;
        append("UPSERT",uuid(entry.path("id")));
        if(ref.type().equals(EventNotes.TYPE))new CalendarSearchState(store).changed();
    }
    /** Imports can replace IDs/versions; a new durable epoch fences old caches. */
    void reset(){
        var previous=ensure();long generation=next(previous);
        var value=previous.deepCopy().put("epoch",UUID.randomUUID().toString()).put("generation",generation).put("historyFloor",generation);
        persist(previous,value);
        store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=?",store.owner(),CHANGE);
    }
    private void append(String operation,UUID entry){
        var previous=ensure();long generation=next(previous),floor=Math.max(previous.path("historyFloor").asLong(),Math.max(0,generation-retained));
        var event=object().put("id",sequence(generation).toString()).put("ownerId",store.owner()).put("version",1)
                .put("generation",generation).put("operation",operation);
        if(entry!=null)event.put("entryId",entry.toString());
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),CHANGE,sequence(generation),1,event.toString());
        persist(previous,previous.deepCopy().put("generation",generation).put("historyFloor",floor));
        if(floor>previous.path("historyFloor").asLong())
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id<=?",store.owner(),CHANGE,sequence(floor));
    }
    private static long next(JsonNode previous){long current=previous.path("generation").asLong();if(current>=MAXIMUM)throw ApiFailure.conflict();return current+1;}
    private void persist(ObjectNode previous,ObjectNode value){
        long version=previous.path("version").asLong();if(version>=MAXIMUM)throw ApiFailure.conflict();value.put("version",version+1);
        if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),STATE,ID,version)!=1)throw new ApiFailure(412,"STALE_VERSION");
    }
    List<JsonNode> page(Snapshot expected,long after,int limit){
        var current=snapshot();if(!current.epoch().equals(expected.epoch())||after<current.historyFloor())throw new HistoryExpired();
        if(after<0||after>expected.generation()||expected.generation()>current.generation()||limit<1||limit>MAX_BATCH)throw new IllegalArgumentException("Invalid search replay range");
        if(after==expected.generation())return List.of();
        // The existing owner/type/id primary key is the replay index. Positive
        // sequence values have the same UUID ordering in H2 and PostgreSQL.
        return store.list(CHANGE,sequence(after+1),sequence(expected.generation()),limit);
    }
    static UUID sequence(long generation){if(generation<0||generation>MAXIMUM)throw new IllegalArgumentException("Invalid search generation");return new UUID(SEQUENCE_PREFIX,generation);}
}
