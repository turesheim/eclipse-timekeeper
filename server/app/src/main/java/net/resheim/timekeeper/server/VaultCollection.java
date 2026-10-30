package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Collection operations share the existing owner lock with every journal writer. */
final class VaultCollection {
    private final ServerStore store;
    private final VaultLimits limits;
    private final VaultIndex index;
    private final VaultState state;
    private final VaultSettings settings;
    VaultCollection(ServerStore store,VaultLimits limits){this.store=store;this.limits=limits;index=new VaultIndex(store);state=new VaultState(store,limits);settings=new VaultSettings(store);}
    void initialize(){new VaultCatalog(store,limits).initialize();}
    ObjectNode get(VaultPath path){allowed(path);var entry=index.at(path,false);if(entry==null)throw ApiFailure.notFound();return entry;}
    ObjectNode get(UUID id){var entry=index.get(id);allowed(path(entry));return entry;}
    static UUID id(JsonNode entry){return uuid(entry.path("id"));}
    static VaultPath path(JsonNode entry){return new VaultPath(entry.path("path").asText());}
    static boolean folder(JsonNode entry){return entry.path("kind").asText().equals(VaultIndex.Kind.FOLDER.name());}
    private void allowed(VaultPath path){if(!settings.allowed(path))throw new ApiFailure(403,"VAULT_CONFIG_DISABLED");}
    String generation(){return etag(state.get());}
    String tag(JsonNode entry){return folder(entry)?etag(object().put("entry",etag(entry)).put("generation",generation())):entry.path("contentTag").asText();}
    ObjectNode metadata(ObjectNode entry){return entry.deepCopy().put("etag",tag(entry)).put("entryEtag",etag(entry));}
    ObjectNode page(UUID parent,String query,boolean trash,int offset,int count,String snapshot){
        if(offset<0||count<1||count>limits.pageSize())throw ApiFailure.validation();
        String current=generation();if(snapshot!=null&&!current.equals(snapshot))throw new ApiFailure(412,"STALE_VERSION");
        if(offset>0&&snapshot==null)throw new ApiFailure(428,"PRECONDITION_REQUIRED");
        List<ObjectNode> entries;
        if(query!=null||trash)entries=index.search(query==null?"":query,trash);
        else {var directory=get(parent);if(directory.path("deleted").asBoolean()||!folder(directory))throw ApiFailure.notFound();entries=index.children(parent);}
        entries=entries.stream().filter(entry->settings.allowed(path(entry))).toList();
        var result=object().put("snapshot",current);var items=result.putArray("items");
        if(offset>entries.size())throw ApiFailure.validation();int end=(int)Math.min(entries.size(),(long)offset+count);
        for(var entry:entries.subList(offset,end))items.add(metadata(entry));
        if(end<entries.size())result.put("nextOffset",end);return result;
    }
    List<ObjectNode> descendants(ObjectNode entry,boolean recursive){
        var entries=recursive?index.subtree(id(entry)):List.of(entry);
        return entries.stream().filter(value->settings.allowed(path(value))).toList();
    }
    byte[] read(ObjectNode entry){if(folder(entry)||entry.path("deleted").asBoolean())throw ApiFailure.notFound();return source(entry).read();}
    private VaultSources source(JsonNode entry){return new VaultSources(store,limits,VaultSources.fromEntry(entry));}
    static void match(String actual,String expected){if(expected==null||expected.equals("*"))throw new ApiFailure(428,"PRECONDITION_REQUIRED");if(!Arrays.stream(expected.split(",")).map(String::trim).anyMatch(actual::equals))throw new ApiFailure(412,"STALE_VERSION");}
    ObjectNode mkdir(VaultPath target){
        allowed(target);if(index.at(target,false)!=null)throw new ApiFailure(405,"VAULT_EXISTS");state.check(0,1);
        var result=index.create(target,VaultIndex.Kind.FOLDER,null,null,null);state.changed(0,1,false);changed();return result;
    }
    ObjectNode put(VaultPath target,String expected,boolean createOnly,byte[] bytes){
        allowed(target);if(bytes==null)bytes=new byte[0];if(bytes.length>limits.fileBytes())throw new ApiFailure(413,"VAULT_FILE_TOO_LARGE");
        var existing=index.at(target,false);
        if(existing!=null){
            if(folder(existing))throw ApiFailure.conflict();if(createOnly)throw new ApiFailure(412,"STALE_VERSION");
            if(expected==null){if(Arrays.equals(read(existing),bytes))return existing;throw new ApiFailure(428,"PRECONDITION_REQUIRED");}
            match(tag(existing),expected);return update(existing,bytes);
        }
        if(expected!=null)throw new ApiFailure(412,"STALE_VERSION");
        var bound=association(target);
        if(bound!=null){
            // A deleted binding may have moved or its old path may be occupied.
            // Never make two namespace entries point at the same canonical note.
            var old=index.referenced(bound.type(),bound.id());
            if(old!=null&&!path(old).value().equals(target.value()))throw ApiFailure.conflict();
            var src=new VaultSources(store,limits,bound);src.write(src.snapshot().tag(),bytes,true);
            return index.referenced(bound.type(),bound.id());
        }
        boolean markdown=target.name().toLowerCase(Locale.ROOT).endsWith(".md");
        if(markdown)VaultContent.markdown(bytes,limits.markdownBytes());state.check(bytes.length,1);
        UUID content=UUID.randomUUID();var storage=new VaultContent(store);
        storage.save(content,etag(storage.metadata(content)),bytes,limits.fileBytes());
        var entry=index.create(target,markdown?VaultIndex.Kind.MARKDOWN:VaultIndex.Kind.BINARY,VaultContent.TYPE,content,content.toString());
        var next=source(entry).snapshot();entry=index.content(id(entry),next,bytes.length);state.changed(bytes.length,1,false);changed();return entry;
    }
    private ObjectNode update(ObjectNode entry,byte[] bytes){
        var ref=VaultSources.fromEntry(entry);var src=source(entry);String before=tag(entry);
        if(!ref.type().equals(VaultContent.TYPE)){src.write(before,bytes,true);return index.get(id(entry));}
        state.check(bytes.length-entry.path("retainedBytes").asLong(),0);
        var next=src.write(before,bytes,entry.path("kind").asText().equals(VaultIndex.Kind.MARKDOWN.name()));
        if(next.tag().equals(before))return entry;
        var updated=index.content(id(entry),next,bytes.length);state.changed(bytes.length-entry.path("retainedBytes").asLong(),0,false);changed();return updated;
    }
    private VaultSources.Ref association(VaultPath target){
        var config=settings.get();String name=target.name();
        if(target.parent().key().equals(new VaultPath(config.path("journalFolder").asText()).key())){
            if(name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")){var date=DayNotes.date(name.substring(0,10));return new VaultSources.Ref(DayNotes.TYPE,DayNotes.id(date),date.toString());}
            if(name.matches("[0-9]{4}-W[0-9]{2}\\.md")){var week=WeekNotes.filename(name);return new VaultSources.Ref(WeekNotes.TYPE,WeekNotes.id(week),week.name());}
        }
        if(target.parent().key().equals(new VaultPath(config.path("eventsFolder").asText()).key())&&name.startsWith("event-")&&name.endsWith(".md")){
            var key=EventNotes.filename(name);return new VaultSources.Ref(EventNotes.TYPE,key.id(),key.filename());
        }
        return null;
    }
    private void protectedRoot(ObjectNode entry){
        if(id(entry).equals(VaultIndex.ROOT))throw ApiFailure.conflict();
        var target=path(entry);var config=settings.get();
        for(String field:List.of("journalFolder","eventsFolder","assetsFolder"))if(new VaultPath(config.path(field).asText()).beneath(target))throw new ApiFailure(409,"VAULT_SPECIAL_FOLDER");
    }
    ObjectNode move(ObjectNode entry,VaultPath target,String expected){
        match(tag(entry),expected);allowed(target);protectedRoot(entry);
        if(entry.path("deleted").asBoolean())throw ApiFailure.notFound();
        for(var descendant:index.subtree(id(entry)))if(!folder(descendant)&&!descendant.path("sourceType").asText().equals(VaultContent.TYPE))throw new ApiFailure(409,"VAULT_ASSOCIATION_MOVE");
        // Moving an ordinary file into a reserved name is an association change,
        // requiring a deliberate import rather than a misleading path-only rename.
        var descendants=index.subtree(id(entry));
        for(var descendant:descendants){var next=path(descendant).rebase(path(entry),target);allowed(next);if(!folder(descendant)&&association(next)!=null)throw new ApiFailure(409,"VAULT_ASSOCIATION_MOVE");}
        var moved=index.move(id(entry),target);if(!path(moved).equals(path(entry))){state.changed(0,0,false);changed();}return moved;
    }
    void delete(ObjectNode entry,String expected){
        match(tag(entry),expected);protectedRoot(entry);if(entry.path("deleted").asBoolean())throw ApiFailure.notFound();
        var entries=new ArrayList<>(index.subtree(id(entry)));Collections.reverse(entries);UUID batch=UUID.randomUUID();
        for(var item:entries){
            if(folder(item)){index.tombstone(id(item),batch);state.changed(0,0,false);}
            else{
                var next=source(item).delete(tag(item));
                if(item.path("sourceType").asText().equals(VaultContent.TYPE)){index.tombstone(id(item),batch);index.content(id(item),next,item.path("retainedBytes").asLong());state.changed(0,0,false);}
                else index.batch(id(item),batch);
            }
        }
        changed();
    }
    ObjectNode restore(ObjectNode entry,String expected){
        match(tag(entry),expected);if(!entry.path("deleted").asBoolean())return entry;
        String batch=entry.path("deletionBatch").asText();
        var entries=index.search("",true).stream().filter(value->value.path("deletionBatch").asText().equals(batch)).sorted(Comparator.comparingInt(value->path(value).segments().size())).toList();
        for(var item:entries){
            allowed(path(item));var restored=index.restore(id(item));
            if(folder(item)){state.changed(0,0,false);continue;}
            var ref=VaultSources.fromEntry(item);var src=source(item);var current=src.snapshot();
            if(!current.deleted()||current.version()!=item.path("contentVersion").asLong())throw ApiFailure.conflict();
            if(ref.type().equals(VaultContent.TYPE)){
                new VaultContent(store).restore(ref.id(),current.tag());var next=src.snapshot();index.content(id(item),next,item.path("retainedBytes").asLong());state.changed(0,0,false);
            }else{
                byte[] bytes=new VaultRecycle(store).read(id(item),current.version());src.write(current.tag(),bytes,true);
            }
        }
        changed();return index.get(id(entry));
    }
    ObjectNode configure(String expected,JsonNode input){
        var previous=settings.get();var effective=input==null?null:input.deepCopy();
        if(effective!=null&&effective.isObject()&&!effective.has("assetsFolder")){
            var oldAssets=new VaultPath(previous.path("assetsFolder").asText());
            for(String field:List.of("journalFolder","eventsFolder"))if(effective.has(field)&&effective.path(field).isTextual()){
                var oldRoot=new VaultPath(previous.path(field).asText());
                if(oldAssets.beneath(oldRoot))((ObjectNode)effective).put("assetsFolder",oldAssets.rebase(oldRoot,new VaultPath(effective.path(field).asText())).value());
            }
        }
        if(previous.path("syncObsidianConfig").asBoolean()&&effective!=null&&effective.has("syncObsidianConfig")&&!effective.path("syncObsidianConfig").asBoolean()
                &&index.search("",false).stream().anyMatch(entry->VaultSettings.configurationPath(path(entry))))throw new ApiFailure(409,"VAULT_CONFIG_PRESENT");
        var roots=new LinkedHashMap<String,UUID>();
        for(String field:List.of("journalFolder","eventsFolder","assetsFolder"))roots.put(field,id(get(new VaultPath(previous.path(field).asText()))));
        var next=settings.save(expected,effective);if(etag(previous).equals(etag(next)))return next;
        for(var root:roots.entrySet()){
            var entry=index.get(root.getValue());var target=new VaultPath(next.path(root.getKey()).asText());
            if(path(entry).equals(target))continue;
            new VaultCatalog(store,limits).folders(target.parent(),false);index.move(id(entry),target);
        }
        state.changed(0,0,false);changed();return next;
    }
    private void changed(){store.change("PROFILE","UPDATED",store.owner());}
}
