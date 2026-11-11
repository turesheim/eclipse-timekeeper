package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Owner-private checklist identities. All source and task writes share the owner transaction. */
final class NoteTasks {
    static final String DOCUMENT="NOTE_TASK_DOC",LINK="NOTE_TASK_LINK";
    private final ServerStore store;
    NoteTasks(ServerStore store){this.store=store;}
    private static UUID id(String type,UUID source){return UUID.nameUUIDFromBytes(("timekeeper-note-tasks:"+type+":"+source).getBytes(StandardCharsets.UTF_8));}
    private static UUID id(VaultSources.Ref ref){return id(ref.type(),ref.id());}
    private static VaultSources.Ref reference(JsonNode value){return NoteReferences.key(value.path("sourceType").asText(),value.path("sourceKey").asText());}
    private static NoteTaskMarkup.Item item(JsonNode value){return new NoteTaskMarkup.Item(value.path("marker").asInt(),value.path("summary").asText(),value.path("checked").asBoolean(),value.path("depth").asInt());}
    private static boolean completed(Task task){return task.planning().status()==TaskStatus.COMPLETED;}
    private Task task(UUID id){return store.find("TASK",id).map(value->(Task)decode("TASK",value)).orElse(null);}
    private ObjectNode record(VaultSources.Ref ref){return store.find(DOCUMENT,id(ref)).map(value->((ObjectNode)value).deepCopy()).orElseGet(()->{
        var value=object().put("id",id(ref).toString()).put("sourceType",ref.type()).put("sourceKey",ref.key());value.putArray("items");return value;
    });}
    private void save(String type,UUID id,ObjectNode fields){
        var previous=store.find(type,id).orElse(null);long version=previous==null?-1:previous.path("version").asLong();if(version>=9007199254740991L)throw ApiFailure.conflict();
        var value=fields.deepCopy().put("id",id.toString()).put("ownerId",store.owner());value.remove("version");
        if(previous!=null){var comparable=((ObjectNode)previous).deepCopy();comparable.remove("version");if(comparable.equals(value))return;}value.put("version",version+1);
        if(previous==null)store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),type,id,0,value.toString());
        else if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",version+1,value.toString(),store.owner(),type,id,version)!=1)throw new ApiFailure(412,"STALE_VERSION");
    }
    private static String summary(String text){return text.codePointCount(0,text.length())<=1024?text:text.substring(0,text.offsetByCodePoints(0,1023))+"…";}
    private Task planning(Task task,boolean checked,java.time.Instant importedAt){
        if(completed(task)==checked&&(importedAt==null||!checked||task.planning().completedAt().equals(Optional.of(importedAt))))return task;
        return store.service().updateTaskPlanning(new UpdateTaskPlanning(task.id(),task.version(),new TaskPlanning(checked?TaskStatus.COMPLETED:TaskStatus.NOT_STARTED,task.planning().scheduledDate(),checked?Optional.of(importedAt==null?store.now():importedAt):Optional.empty())));
    }
    /** Invoked after a successful canonical Markdown write, including DAV/import writers. */
    void source(VaultSources.Ref ref,byte[] bytes){
        if(store.noteTaskSync()||bytes==null)return;
        store.withNoteTaskSync(()->{reconcile(ref,new String(bytes,StandardCharsets.UTF_8),null,null);return null;});
    }
    void imported(VaultSources.Ref ref,byte[] bytes,NoteTaskImport policy,java.time.Instant completedAt){
        store.withNoteTaskSync(()->{reconcile(ref,new String(bytes,StandardCharsets.UTF_8),policy,completedAt);return null;});
    }
    private void reconcile(VaultSources.Ref ref,String source,NoteTaskImport policy,java.time.Instant importedAt){
        var previous=record(ref);var old=new ArrayList<NoteTaskMarkup.Item>();previous.path("items").forEach(value->old.add(item(value)));
        var next=NoteTaskMarkup.items(source);if(next.size()>1000)throw ApiFailure.validation();
        int[] matches=NoteTaskMatches.previous(old,next);var result=previous.deepCopy();var items=result.putArray("items");var retained=new HashSet<UUID>();String normalized=source;
        var assignments=next.stream().anyMatch(value->!value.assignments().isEmpty())?new NoteTaskAssignments(store):null;
        for(int i=0;i<next.size();i++){
            var current=next.get(i);var before=matches[i]<0?null:previous.path("items").get(matches[i]);UUID taskId=before==null?null:uuid(before.path("taskId"));Task task=taskId==null?null:task(taskId);
            long initialVersion=task==null?-1:task.version();
            boolean edited=before==null||!current.summary().equals(before.path("summary").asText())||current.checked()!=before.path("checked").asBoolean();
            // Deleting a normal task is deliberate. Unrelated prose edits must
            // not resurrect it; editing its checkbox/title can create a new task.
            if(task==null&&(edited||policy!=null)&&policy!=NoteTaskImport.IGNORE){task=store.service().createTask(new CreateTask(Optional.empty(),summary(current.summary()),Optional.empty()));task=planning(task,policy==NoteTaskImport.COMPLETED||current.checked(),importedAt);taskId=task.id().value();}
            else if(task!=null){
                if(before!=null&&!current.summary().equals(before.path("summary").asText())&&!task.summary().equals(summary(current.summary())))task=store.service().updateTask(new UpdateTask(task.id(),task.version(),task.projectId(),task.parentTaskId(),summary(current.summary()),task.url()));
                if(policy==NoteTaskImport.COMPLETED)task=planning(task,true,importedAt);
                else if(before!=null&&current.checked()!=before.path("checked").asBoolean())task=planning(task,current.checked(),importedAt);
            }
            if(task!=null&&completed(task)!=current.checked())normalized=NoteTaskMarkup.checked(normalized,current,completed(task));
            // An ignored item has the same durable suppression as a deliberately
            // deleted task. Maintenance and prose-only edits cannot enroll it.
            if(taskId==null)taskId=UUID.randomUUID();
            if(task!=null&&assignments!=null&&(before==null||!previous.has("contentTag")||!current.summary().equals(before.path("summary").asText()))) {
                var assignment=assignments.resolve(current.assignments()).orElse(null);
                if(assignment!=null){
                    if(!task.projectId().equals(assignment.project()))task=store.service().updateTask(new UpdateTask(task.id(),task.version(),assignment.project(),task.parentTaskId(),task.summary(),task.url()));
                    var priorClient=store.find("TASK",task.id().value()).orElseThrow().get("clientId");
                    if(before!=null&&task.version()==initialVersion&&!Objects.equals(priorClient==null||priorClient.isNull()?null:uuid(priorClient),assignment.client()))
                        task=store.service().updateTask(new UpdateTask(task.id(),task.version(),task.projectId(),task.parentTaskId(),task.summary(),task.url()));
                    var fields=object();if(assignment.client()==null)fields.putNull("clientId");else fields.put("clientId",assignment.client().toString());store.clientDetails("TASK",task.id().value(),fields);
                }
            }
            retained.add(taskId);boolean checked=task==null?current.checked():completed(task);
            items.add(object().put("taskId",taskId.toString()).put("marker",current.marker()).put("summary",current.summary()).put("checked",checked).put("depth",current.depth()));
            save(LINK,taskId,object().put("documentId",id(ref).toString()));
        }
        for(var value:previous.path("items")){UUID taskId=uuid(value.path("taskId"));if(!retained.contains(taskId)){
            var removed=task(taskId);
            // Preserve recorded work and independently managed subtasks.
            if(removed!=null&&!store.related("task","TASK",taskId)&&!store.related("parent","TASK",taskId))store.service().deleteTask(new DeleteTask(removed.id(),removed.version()));
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),LINK,taskId);
        }}
        // A task may have changed while its source was in Trash. Restore its
        // current completion without reformatting the restored document.
        if(!normalized.equals(source))write(ref,normalized);
        result.put("contentTag",new VaultSources(store,store.vaultLimits(),ref).snapshot().tag()).put("sourceVersion",store.resourceVersion(ref.type(),ref.id()));save(DOCUMENT,id(ref),result);
    }
    /** Bounded maintenance enrolls older files without needing a browser read or edit. */
    void existing(String type,UUID sourceId){
        long version=store.resourceVersion(type,sourceId);if(version==0)return;
        var checkpoint=store.find(DOCUMENT,id(type,sourceId)).orElse(null);
        if(checkpoint!=null&&checkpoint.path("sourceVersion").asLong(-1)==version)return;
        VaultSources.Ref ref;
        if(type.equals(VaultContent.TYPE)){
            var entry=new VaultIndex(store).referenced(type,sourceId);
            if(entry==null||entry.path("deleted").asBoolean()||!entry.path("kind").asText().equals("MARKDOWN")||!new VaultSettings(store).allowed(VaultCollection.path(entry)))return;
            ref=VaultSources.fromEntry(entry);
        }else {var stored=store.find(type,sourceId).orElse(null);if(stored==null)return;ref=VaultSources.fromStored(type,stored);}
        var source=new VaultSources(store,store.vaultLimits(),ref);var state=source.snapshot();
        if(state.deleted())return;
        if(checkpoint!=null&&checkpoint.path("contentTag").asText().equals(state.tag()))save(DOCUMENT,id(ref),((ObjectNode)checkpoint).deepCopy().put("sourceVersion",version));
        else source(ref,source.read());
    }
    ObjectNode origin(UUID task){
        var link=store.find(LINK,task).orElseThrow(ApiFailure::notFound);var document=store.find(DOCUMENT,uuid(link.path("documentId"))).orElseThrow(ApiFailure::notFound);var ref=reference(document);
        var vault=new VaultCollection(store,store.vaultLimits());vault.initialize();var entry=new VaultIndex(store).referenced(ref.type(),ref.id());
        if(entry==null||entry.path("deleted").asBoolean())throw ApiFailure.notFound();return vault.metadata(vault.get(VaultCollection.id(entry)));
    }
    /** Called by the canonical task repository; guarded against source -> task recursion. */
    void task(Task task){
        if(store.noteTaskSync())return;
        var link=store.find(LINK,task.id().value()).orElse(null);if(link==null)return;
        var document=store.find(DOCUMENT,uuid(link.path("documentId"))).map(value->((ObjectNode)value).deepCopy()).orElse(null);if(document==null)throw new ApiFailure(503,"UNAVAILABLE");
        var ref=reference(document);var source=new VaultSources(store,store.vaultLimits(),ref);if(source.snapshot().deleted())return;
        var item=java.util.stream.StreamSupport.stream(document.path("items").spliterator(),false).filter(value->value.path("taskId").asText().equals(task.id().value().toString())).findFirst().orElse(null);
        if(item==null)throw new ApiFailure(503,"UNAVAILABLE");if(item.path("checked").asBoolean()==completed(task))return;
        String text=new String(source.read(),StandardCharsets.UTF_8);var parsed=NoteTaskMarkup.items(text);var actual=parsed.stream().filter(value->value.marker()==item.path("marker").asInt()&&value.summary().equals(item.path("summary").asText())).findFirst().orElse(null);
        if(actual==null||actual.checked()!=item.path("checked").asBoolean())throw new ApiFailure(412,"STALE_VERSION");
        store.withNoteTaskSync(()->{write(ref,NoteTaskMarkup.checked(text,actual,completed(task)));((ObjectNode)item).put("checked",completed(task));save(DOCUMENT,id(ref),document);return null;});
    }
    private void write(VaultSources.Ref ref,String text){
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);var source=new VaultSources(store,store.vaultLimits(),ref);
        if(ref.type().equals(VaultContent.TYPE)){
            var entry=new VaultIndex(store).referenced(ref.type(),ref.id());if(entry==null||entry.path("deleted").asBoolean()||!entry.path("kind").asText().equals("MARKDOWN"))throw ApiFailure.notFound();
            new VaultCollection(store,store.vaultLimits()).put(VaultCollection.path(entry),source.snapshot().tag(),false,bytes);
        }else source.write(source.snapshot().tag(),bytes,true);
    }
    void install(VaultSources.Ref ref,JsonNode items){
        var previous=record(ref);if(!previous.path("items").isEmpty())throw ApiFailure.conflict();
        var value=previous.deepCopy();value.set("items",items.deepCopy());value.put("contentTag",new VaultSources(store,store.vaultLimits(),ref).snapshot().tag()).put("sourceVersion",store.resourceVersion(ref.type(),ref.id()));save(DOCUMENT,id(ref),value);
        for(var item:items){UUID taskId=uuid(item.path("taskId"));if(store.find(LINK,taskId).isPresent())throw ApiFailure.conflict();save(LINK,taskId,object().put("documentId",id(ref).toString()));}
    }
}
