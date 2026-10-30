package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Owner-transactional metadata indexes; canonical document bytes live elsewhere. */
final class VaultIndex {
    static final String ENTRY="VAULT_ENTRY",PATH="VAULT_PATH",REFERENCE="VAULT_REF";
    private static final String CHILD="vault_child";
    static final UUID ROOT=ImportLedger.identifier("vault","root");
    enum Kind { FOLDER, MARKDOWN, BINARY, DAY, WEEK, EVENT, ASSET }
    private final ServerStore store;
    VaultIndex(ServerStore store){this.store=store;}
    private static UUID pathId(VaultPath path){return ImportLedger.identifier("vault-path",path.key());}
    private static UUID refId(String type,UUID id){return ImportLedger.identifier("vault-reference",type,id.toString());}
    private static VaultPath path(JsonNode entry){return new VaultPath(entry.path("path").asText());}
    private static UUID id(JsonNode entry){return uuid(entry.path("id"));}

    ObjectNode root(){
        var current=store.find(ENTRY,ROOT);
        if(current.isPresent())return copy(current.get());
        var value=object().put("path","").put("kind",Kind.FOLDER.name()).put("parentId",ROOT.toString()).put("deleted",false);
        var saved=write(ENTRY,ROOT,value);point(pathId(VaultPath.root()),PATH,object().put("key","").put("entryId",ROOT.toString()).put("active",true));return saved;
    }
    ObjectNode get(UUID id){return copy(store.find(ENTRY,id).orElseThrow(ApiFailure::notFound));}
    ObjectNode at(VaultPath path,boolean deleted){
        var pointer=store.find(PATH,pathId(path)).orElse(null);if(pointer==null)return null;
        if(!pointer.path("key").asText().equals(path.key()))throw ApiFailure.conflict();
        var entry=store.find(ENTRY,uuid(pointer.path("entryId"))).orElseThrow(()->new IllegalStateException("Broken vault path index"));
        if(!path(entry).key().equals(path.key())||!deleted&&entry.path("deleted").asBoolean())return null;
        return copy(entry);
    }
    ObjectNode referenced(String type,UUID source){
        var pointer=store.find(REFERENCE,refId(type,source)).orElse(null);if(pointer==null)return null;
        if(!type.equals(pointer.path("sourceType").asText())||!source.toString().equals(pointer.path("sourceId").asText()))throw ApiFailure.conflict();
        return get(uuid(pointer.path("entryId")));
    }
    ObjectNode create(VaultPath target,Kind kind,String sourceType,UUID sourceId,String sourceKey){
        if(target.isRoot())throw ApiFailure.conflict();
        var existing=at(target,true);
        if(existing!=null&&!existing.path("deleted").asBoolean())throw ApiFailure.conflict();
        var parent=at(target.parent(),false);if(parent==null||!parent.path("kind").asText().equals(Kind.FOLDER.name()))throw ApiFailure.conflict();
        UUID entryId=UUID.randomUUID();
        if(sourceType!=null&&sourceId!=null&&referenced(sourceType,sourceId)!=null)throw ApiFailure.conflict();
        var value=object().put("path",target.value()).put("kind",kind.name()).put("parentId",id(parent).toString()).put("deleted",false);
        if(sourceType!=null){value.put("sourceType",sourceType).put("sourceId",Objects.requireNonNull(sourceId).toString()).put("sourceKey",Objects.requireNonNull(sourceKey));}
        var saved=write(ENTRY,entryId,value);link(saved);
        if(sourceType!=null)point(refId(sourceType,sourceId),REFERENCE,object().put("sourceType",sourceType).put("sourceId",sourceId.toString()).put("entryId",entryId.toString()));
        return saved;
    }
    List<ObjectNode> children(UUID parent){
        var result=new ArrayList<ObjectNode>();
        for(var value:store.children(ENTRY,parent,CHILD)){
            var entry=copy(value);
            if(!entry.path("deleted").asBoolean()&&parent.toString().equals(entry.path("parentId").asText()))result.add(entry);
        }
        result.sort(Comparator.comparing(value->path(value).key()));return List.copyOf(result);
    }
    List<ObjectNode> subtree(UUID root){
        var result=new ArrayList<ObjectNode>();var pending=new ArrayDeque<ObjectNode>();var seen=new HashSet<UUID>();pending.add(get(root));
        while(!pending.isEmpty()){
            var entry=pending.removeFirst();if(!seen.add(id(entry)))throw new IllegalStateException("Cyclic vault parent index");
            result.add(entry);if(entry.path("kind").asText().equals(Kind.FOLDER.name()))pending.addAll(children(id(entry)));
        }
        return List.copyOf(result);
    }
    /** Caller checks the complete source snapshot's ETag before an owner-atomic move. */
    ObjectNode move(UUID source,VaultPath destination){
        var original=get(source);if(original.path("deleted").asBoolean()||source.equals(ROOT)||destination.isRoot())throw ApiFailure.conflict();
        if(path(original).value().equals(destination.value()))return original;
        var parent=at(destination.parent(),false);if(parent==null||!parent.path("kind").asText().equals(Kind.FOLDER.name()))throw ApiFailure.conflict();
        var entries=subtree(source);var ids=new HashSet<UUID>();entries.forEach(value->ids.add(id(value)));
        if(ids.contains(id(parent)))throw ApiFailure.conflict();
        var targets=new LinkedHashMap<UUID,VaultPath>();
        for(var entry:entries){
            var next=path(entry).rebase(path(original),destination);var conflict=at(next,false);
            if(conflict!=null&&!ids.contains(id(conflict)))throw ApiFailure.conflict();targets.put(id(entry),next);
        }
        for(var entry:entries)retirePath(entry);
        if(!original.path("parentId").asText().equals(parent.path("id").asText()))unlinkChild(original);
        ObjectNode moved=null;
        for(var entry:entries){
            entry.put("path",targets.get(id(entry)).value());if(id(entry).equals(source))entry.put("parentId",id(parent).toString());
            var saved=write(ENTRY,id(entry),entry);link(saved);if(id(entry).equals(source))moved=saved;
        }
        return moved;
    }
    ObjectNode tombstone(UUID entryId,UUID batch){
        var entry=get(entryId);if(entryId.equals(ROOT))throw ApiFailure.conflict();
        if(entry.path("deleted").asBoolean())return entry;
        if(!children(entryId).isEmpty())throw ApiFailure.conflict();
        retirePath(entry);unlinkChild(entry);entry.put("deleted",true).put("deletionBatch",batch.toString());return write(ENTRY,entryId,entry);
    }
    ObjectNode restore(UUID entryId){
        var entry=get(entryId);if(!entry.path("deleted").asBoolean())return entry;
        var parent=get(uuid(entry.path("parentId")));if(parent.path("deleted").asBoolean())throw ApiFailure.conflict();
        var target=path(parent).resolve(path(entry).name());var conflict=at(target,false);
        if(conflict!=null&&!id(conflict).equals(entryId))throw ApiFailure.conflict();
        entry.put("path",target.value()).put("deleted",false);entry.remove("deletionBatch");var saved=write(ENTRY,entryId,entry);link(saved);return saved;
    }
    /** Metadata is small; search never loads document or attachment payloads. */
    List<ObjectNode> search(String query,boolean deleted){
        String key=VaultPath.searchKey(query);
        return store.list(ENTRY).stream().filter(value->value.path("deleted").asBoolean()==deleted&&path(value).key().contains(key))
                .map(VaultIndex::copy).sorted(Comparator.comparing(value->path(value).key())).toList();
    }
    private void link(ObjectNode entry){
        var target=path(entry);var previous=at(target,false);
        if(previous!=null&&!id(previous).equals(id(entry)))throw ApiFailure.conflict();
        point(pathId(target),PATH,object().put("key",target.key()).put("entryId",entry.path("id").asText()).put("active",!entry.path("deleted").asBoolean()));
        if(target.isRoot()||entry.path("deleted").asBoolean())return;
        unlinkChild(entry);
        store.update("INSERT INTO tk_relation(owner_id,from_type,from_id,kind,to_type,to_id) VALUES(?,?,?,?,?,?)",store.owner(),ENTRY,uuid(entry.path("parentId")),CHILD,ENTRY,id(entry));
    }
    private void retirePath(JsonNode entry){
        var path=path(entry);var pointer=store.find(PATH,pathId(path)).orElseThrow(()->new IllegalStateException("Missing vault path index"));
        if(!pointer.path("entryId").asText().equals(entry.path("id").asText()))throw ApiFailure.conflict();
        var value=copy(pointer).put("active",false);write(PATH,pathId(path),value);
    }
    private void unlinkChild(JsonNode entry){
        store.update("DELETE FROM tk_relation WHERE owner_id=? AND from_type=? AND from_id=? AND kind=? AND to_type=? AND to_id=?",store.owner(),ENTRY,uuid(entry.path("parentId")),CHILD,ENTRY,id(entry));
    }
    private void point(UUID id,String type,ObjectNode data){write(type,id,data);}
    private ObjectNode write(String type,UUID id,ObjectNode data){
        var previous=store.find(type,id).orElse(null);long version=previous==null?0:previous.path("version").asLong();
        if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=data.deepCopy().put("id",id.toString()).put("ownerId",store.owner()).put("version",version+1);
        if(previous==null)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),type,id,version+1,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),type,id,version)!=1)throw new ApiFailure(412,"STALE_VERSION");
        return value;
    }
    private static ObjectNode copy(JsonNode value){return ((ObjectNode)value).deepCopy();}
}
