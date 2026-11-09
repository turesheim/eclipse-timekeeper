package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Portable checklist bindings refer to the work graph, never to another account's authority. */
final class NoteTaskArchive {
    private NoteTaskArchive(){}
    @FunctionalInterface interface Item {void accept(ObjectNode value)throws IOException;}
    static int archive(Connection connection,String owner,Item output)throws SQLException,IOException{
        var available=new HashSet<String>();var tasks=new HashSet<String>();
        AccountArchive.records(connection,owner,"TASK",value->tasks.add(value.path("id").asText()));
        AccountArchive.records(connection,owner,VaultIndex.ENTRY,value->{if(Set.of("MARKDOWN","DAY","WEEK","EVENT").contains(value.path("kind").asText()))available.add(value.path("sourceType").asText()+":"+value.path("sourceKey").asText());});
        var deletedDays=new HashMap<String,Long>();var deletedWeeks=new HashMap<String,Long>();
        AccountArchive.records(connection,owner,JournalState.Kind.JOURNAL_DELETE.name(),value->deletedDays.put(value.path("id").asText(),value.path("noteVersion").asLong()));
        AccountArchive.records(connection,owner,JournalState.Kind.WEEK_NOTE_DELETE.name(),value->deletedWeeks.put(value.path("id").asText(),value.path("noteVersion").asLong()));
        AccountArchive.records(connection,owner,DayNotes.TYPE,value->{if(AccountArchive.presentNote(value,deletedDays))available.add(DayNotes.TYPE+":"+value.path("date").asText());});
        AccountArchive.records(connection,owner,WeekNotes.TYPE,value->{if(AccountArchive.presentWeek(value,deletedWeeks))available.add(WeekNotes.TYPE+":"+value.path("week").asText());});
        AccountArchive.records(connection,owner,EventNotes.TYPE,value->{if(EventNotes.present(value))available.add(EventNotes.TYPE+":"+new EventNotes.Key(uuid(value.path("subscriptionId")),value.path("eventKey").asText()).filename());});
        int[] count={0};AccountArchive.records(connection,owner,NoteTasks.DOCUMENT,value->{
            if(value.path("items").isEmpty()||!available.contains(value.path("sourceType").asText()+":"+value.path("sourceKey").asText()))return;
            var copy=object().put("sourceType",value.path("sourceType").asText()).put("sourceKey",value.path("sourceKey").asText());var items=copy.putArray("items");
            for(var item:value.path("items")){var next=((ObjectNode)item).deepCopy();if(!tasks.contains(item.path("taskId").asText()))next.putNull("taskId");items.add(next);count[0]++;}output.accept(copy);
        });return count[0];
    }
    static void archive(Connection connection,String owner,JsonGenerator json)throws SQLException,IOException{
        json.writeArrayFieldStart("noteTasks");archive(connection,owner,value->json.writeTree(value));json.writeEndArray();
    }
    static void validate(JsonNode values,JsonNode work){
        if(values.isMissingNode())return;if(!values.isArray()||values.size()>250000)throw ApiFailure.validation();var sources=new HashSet<String>();var linked=new HashSet<UUID>();var tasks=new HashSet<UUID>();for(var task:work.path("tasks"))tasks.add(identifier(task.path("id")));long count=0;
        for(var value:values){
            if(!names(value).equals(Set.of("sourceType","sourceKey","items"))||!value.path("sourceType").isTextual()||!value.path("sourceKey").isTextual()||!value.path("items").isArray()||value.path("items").isEmpty()||value.path("items").size()>1000)throw ApiFailure.validation();
            var ref=NoteBookmarks.key(value.path("sourceType").asText(),value.path("sourceKey").asText());if(!sources.add(ref.type()+":"+ref.id()))throw ApiFailure.validation();
            for(var item:value.path("items")){
                if(!names(item).equals(Set.of("taskId","marker","summary","checked","depth"))||!item.path("marker").isIntegralNumber()||!item.path("marker").canConvertToInt()||item.path("marker").asInt()<0||!item.path("summary").isTextual()||item.path("summary").asText().isBlank()||!item.path("checked").isBoolean()||!item.path("depth").isIntegralNumber()||!item.path("depth").canConvertToInt()||item.path("depth").asInt()<0||++count>250000)throw ApiFailure.validation();
                if(!item.path("taskId").isNull()){if(!item.path("taskId").isTextual())throw ApiFailure.validation();UUID id=identifier(item.path("taskId"));if(!tasks.contains(id)||!linked.add(id))throw ApiFailure.validation();}
            }
        }
    }
    private static UUID identifier(JsonNode value){try{if(!value.isTextual())throw ApiFailure.validation();UUID id=UUID.fromString(value.asText());if(!id.toString().equalsIgnoreCase(value.asText()))throw ApiFailure.validation();return id;}catch(IllegalArgumentException invalid){throw ApiFailure.validation();}}
    private static Set<String> names(JsonNode value){var result=new HashSet<String>();value.fieldNames().forEachRemaining(result::add);return result;}
    static void restore(JsonNode values,JsonNode mappings,ServerStore store){
        if(values.isMissingNode())return;var tasks=new HashMap<UUID,UUID>();for(var mapping:mappings)if(mapping.path("entityType").asText().equals("TASK"))tasks.put(uuid(mapping.path("sourceId")),uuid(mapping.path("id")));
        for(var value:values){
            var ref=NoteBookmarks.key(value.path("sourceType").asText(),value.path("sourceKey").asText());byte[] bytes=source(store,ref);var actual=NoteTaskMarkup.items(new String(bytes,StandardCharsets.UTF_8));
            if(actual.size()!=value.path("items").size())throw ApiFailure.validation();var items=array();int index=0;
            for(var item:value.path("items")){
                var parsed=actual.get(index++);if(parsed.marker()!=item.path("marker").asInt()||parsed.checked()!=item.path("checked").asBoolean()||parsed.depth()!=item.path("depth").asInt()||!parsed.summary().equals(item.path("summary").asText()))throw ApiFailure.validation();
                UUID taskId=item.path("taskId").isNull()?UUID.randomUUID():tasks.get(uuid(item.path("taskId")));if(taskId==null||!item.path("taskId").isNull()&&store.find("TASK",taskId).isEmpty())throw ApiFailure.validation();var next=((ObjectNode)item).deepCopy().put("taskId",taskId.toString());items.add(next);
            }
            new NoteTasks(store).install(ref,items);
        }
    }
    private static byte[] source(ServerStore store,VaultSources.Ref ref){
        if(ref.type().equals(VaultContent.TYPE)){var entry=new VaultIndex(store).referenced(ref.type(),ref.id());if(entry==null||!entry.path("kind").asText().equals("MARKDOWN"))throw ApiFailure.validation();}
        var source=new VaultSources(store,store.vaultLimits(),ref);var state=source.snapshot();if(!state.deleted())return source.read();
        var entry=new VaultIndex(store).referenced(ref.type(),ref.id());if(entry==null||!entry.path("deleted").asBoolean())throw ApiFailure.validation();
        if(ref.type().equals(VaultContent.TYPE)){if(!entry.path("kind").asText().equals("MARKDOWN"))throw ApiFailure.validation();return VaultContent.bytes((ObjectNode)store.find(VaultContent.TYPE,ref.id()).orElseThrow(ApiFailure::validation));}
        return new VaultRecycle(store).read(VaultCollection.id(entry),state.version());
    }
}
