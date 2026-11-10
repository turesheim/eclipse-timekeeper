package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(TaskImprovementsTest.Profile.class)
class TaskImprovementsTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("taskimprovements","jdbc:h2:mem:task_improvements");}}
    @Inject ServerStore store;
    @Inject NoteTaskRefresh refresh;
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private JsonNode client(String name){return store.createClient(object().put("name",name).put("color","#123456"));}
    private VaultCollection vault(){var vault=new VaultCollection(store,store.vaultLimits());vault.initialize();return vault;}
    @Test void markdownNamesResolveOwnedClientsAndProjectsAndKeepSourceBytes(){
        store.transaction("assignments",()->{
            var client=client("Client With Spaces");var project=store.withCreationClient(uuid(client.get("id")),()->store.service().createProject(new CreateProject("Project With Spaces")));
            String source="- [ ] Direct @Client_With_Spaces\n- [ ] Project @Project_With_Spaces\n- [ ] Literal `@Client_With_Spaces`\n- [ ] Escaped \\@Client_With_Spaces\n";
            var entry=vault().put(new VaultPath("Assignments.md"),null,true,bytes(source));var tasks=store.list("TASK");assertEquals(4,tasks.size());
            var direct=tasks.stream().filter(task->task.path("summary").asText().startsWith("Direct")).findFirst().orElseThrow();assertEquals(client.get("id"),direct.get("clientId"));assertTrue(direct.path("projectId").isNull());
            var assigned=tasks.stream().filter(task->task.path("summary").asText().startsWith("Project")).findFirst().orElseThrow();assertEquals(project.id().value().toString(),assigned.path("projectId").asText());
            assertTrue(tasks.stream().filter(task->!task.equals(direct)&&!task.equals(assigned)).allMatch(task->task.path("clientId").isNull()&&task.path("projectId").isNull()));assertArrayEquals(bytes(source),vault().read(entry));
            entry=vault().move(entry,new VaultPath("Renamed.md"),vault().tag(entry));assertEquals("Renamed.md",new NoteTasks(store).origin(uuid(direct.get("id"))).path("path").asText());return null;
        });
        store.transaction("assignment-foreign",()->{vault().put(new VaultPath("Foreign.md"),null,true,bytes("- [ ] Private @Client_With_Spaces\n"));assertTrue(store.list("TASK").getFirst().path("clientId").isNull());return null;});
    }
    @Test void ambiguousNamesStayUnassignedAndExplicitKindsDisambiguate(){
        store.transaction("assignment-ambiguous",()->{
            var client=client("Same");var project=store.withCreationClient(uuid(client.get("id")),()->store.service().createProject(new CreateProject("Same")));
            vault().put(new VaultPath("Ambiguous.md"),null,true,bytes("- [ ] Ambiguous @Same\n- [ ] Explicit @project:Same\n"));
            assertTrue(store.list("TASK").stream().filter(task->task.path("summary").asText().startsWith("Ambiguous")).findFirst().orElseThrow().path("projectId").isNull());
            assertEquals(project.id().value().toString(),store.list("TASK").stream().filter(task->task.path("summary").asText().startsWith("Explicit")).findFirst().orElseThrow().path("projectId").asText());return null;
        });
    }
    @Test void removingAnItemPreservesRecordedWorkAndUnlinksItsTask(){
        store.transaction("removal-history",()->{
            var entry=vault().put(new VaultPath("History.md"),null,true,bytes("- [ ] Recorded work\n"));var task=store.service().tasks().getFirst();
            store.service().createActivity(new CreateActivity(task.id(),new OwnerId(store.owner()),java.time.Instant.parse("2026-11-10T08:00:00Z"),Optional.of(java.time.Instant.parse("2026-11-10T09:00:00Z")),"Retain history",Set.of(),Optional.empty(),Optional.empty()));
            vault().put(new VaultPath("History.md"),vault().tag(entry),false,bytes("Prose\n"));assertTrue(store.service().task(task.id()).isPresent());assertEquals(1,store.list("ACTIVITY").size());assertEquals(404,assertThrows(ApiFailure.class,()->new NoteTasks(store).origin(task.id().value())).status);return null;
        });
    }
    @Test void maintenanceEnrollsOlderNativeAndFileSourcesOnceWithoutBrowserReads(){
        store.transaction("older-tasks",()->{
            store.withNoteTaskSync(()->{vault().put(new VaultPath("Older.md"),null,true,bytes("- [ ] Older file\n"));var days=new DayNotes(store);var day=DayNotes.date("2026-11-10");days.saveContent(day,etag(days.getContent(day)),object().put("body","- [ ] Older daily\n"));return null;});assertTrue(store.service().tasks().isEmpty());return null;
        });refresh.sweep();
        var before=store.transaction("older-tasks",()->{assertEquals(2,store.service().tasks().size());return store.service().tasks();});refresh.sweep();
        store.transaction("older-tasks",()->{assertEquals(before,store.service().tasks());return null;});
    }
    @Test void sourceEndpointChecksOwnershipAndResolvesEveryNativeNoteKind(){
        var ids=store.transaction("a",()->{
            var day=DayNotes.date("2026-11-12");var days=new DayNotes(store);days.saveContent(day,etag(days.getContent(day)),object().put("body","- [ ] Origin daily\n"));
            var week=WeekNotes.week("2026-W47");var weeks=new WeekNotes(store);weeks.saveDocument(week,etag(weeks.get(week)),bytes("- [ ] Origin weekly\n"));
            var key=new EventNotes.Key(UUID.randomUUID(),"cd".repeat(32));var events=new EventNotes(store);var event=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event()).put("body","- [ ] Origin event\n");event.set("event",object().put("uid","origin-event").put("recurrenceId","").put("title","Origin meeting"));events.saveDocument(key,etag(events.get(key)),EventNotes.document(event));
            vault().put(new VaultPath("Origin.md"),null,true,bytes("- [ ] Origin ordinary\n"));var result=new HashMap<String,String>();for(var task:store.service().tasks())if(task.summary().startsWith("Origin "))result.put(task.summary(),task.id().value().toString());return result;
        });
        for(var entry:Map.of("Origin daily","DAY","Origin weekly","WEEK","Origin event","EVENT","Origin ordinary","MARKDOWN").entrySet()){
            String path="/tasks/"+ids.get(entry.getKey())+"/note";var reply=call("test-a","GET",path,null,null);assertEquals(200,reply.statusCode(),reply.asString());assertEquals(entry.getValue(),reply.jsonPath().getString("kind"));assertEquals(200,call("test-read","GET",path,null,null).statusCode());assertEquals(404,call("test-b","GET",path,null,null).statusCode());
        }
    }
    private io.restassured.response.Response call(String token,String method,String path,JsonNode body,String match){
        var request=given().header("Authorization","Bearer "+token).contentType("application/json");if(body!=null)request.body(body.toString());if(match!=null)request.header("If-Match",match);return request.request(method,"/api/v1"+path);
    }
    @Test void taskEditorWritesAreAtomicOwnedAndPreserveUneditedTimingAndImportance(){
        var client=call("test-a","POST","/clients",object().put("name","Direct client").put("color","#123456"),null);assertEquals(201,client.statusCode());String clientId=client.jsonPath().getString("id");
        var input=object().put("summary","Planned :smiley:").putNull("projectId").putNull("parentTaskId").putNull("url").put("clientId",clientId).put("priority","HIGH").put("flagged",true).put("urgent",true);
        input.set("start",object().put("kind","DATE_TIME").put("date","2026-03-29").put("time","02:30:00").put("timeZone","Europe/Oslo"));input.set("due",object().put("kind","DATE").put("date","2026-11-15"));
        var created=call("test-a","POST","/tasks",input,null);assertEquals(201,created.statusCode(),created.asString());String path="/tasks/"+created.jsonPath().getString("id");
        var edit=object().put("summary","Updated").putNull("projectId").putNull("parentTaskId").putNull("url").put("priority","LOW");edit.set("due",object().put("kind","DATE_TIME").put("date","2026-11-16").put("time","12:30:00").put("timeZone","Europe/Oslo"));
        assertEquals(403,call("test-read","PUT",path,edit,created.header("ETag")).statusCode());assertEquals(404,call("test-b","PUT",path,edit,created.header("ETag")).statusCode());
        var updated=call("test-a","PUT",path,edit,created.header("ETag"));assertEquals(200,updated.statusCode(),updated.asString());assertEquals(412,call("test-a","PUT",path,edit,created.header("ETag")).statusCode());
        var actual=parse(updated.asString());assertEquals(clientId,actual.path("clientId").asText());assertEquals(parse(created.asString()).get("start"),actual.get("start"));assertTrue(actual.path("flagged").asBoolean());assertTrue(actual.path("urgent").asBoolean());
        var foreign=call("test-b","POST","/clients",object().put("name","Foreign").put("color","#654321"),null);edit.put("clientId",foreign.jsonPath().getString("id"));assertEquals(404,call("test-a","PUT",path,edit,updated.header("ETag")).statusCode());
        assertEquals(updated.asString(),call("test-a","GET",path,null,null).asString());
        edit.remove(List.of("clientId","priority","due"));var legacy=call("test-a","PUT",path,edit,updated.header("ETag"));assertEquals(200,legacy.statusCode());actual=parse(legacy.asString());assertEquals(clientId,actual.path("clientId").asText());assertEquals("LOW",actual.path("priority").asText());assertEquals("DATE_TIME",actual.path("due").path("kind").asText());
    }
    @Test void portableTransfersRemapDirectClientsWithoutInventingAProject(){
        var exported=store.transaction("portable-clients",()->{
            var client=client("Portable direct client");var task=store.service().createTask(new CreateTask(Optional.empty(),"Portable task",Optional.empty()));store.clientDetails("TASK",task.id().value(),object().set("clientId",client.get("id")));
            var value=object().put("format","timekeeper-domain-v3").put("zoneId","UTC");DomainTransfer.TYPES.forEach((name,type)->{var array=value.putArray(name);for(var item:store.list(type)){var copy=((ObjectNode)item).deepCopy();copy.remove(List.of("ownerId","version"));array.add(copy);}});return value;
        });
        store.transaction("portable-destination",()->{new DomainTransfer(store).importRecords(exported);var client=store.list("CLIENT").getFirst();var task=store.list("TASK").getFirst();assertEquals(client.get("id"),task.get("clientId"));assertTrue(task.path("projectId").isNull());assertNotEquals(exported.path("clients").get(0).get("id"),client.get("id"));return null;});
    }
}
