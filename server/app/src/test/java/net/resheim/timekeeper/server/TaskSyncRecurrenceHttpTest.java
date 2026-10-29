package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

/** Series-only providers must preserve unobserved completed and exception history. */
@QuarkusTest @TestProfile(TaskSyncRecurrenceHttpTest.Profile.class)
class TaskSyncRecurrenceHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:task_sync_recurrence_masks");}
    }
    private Response call(String method,String path,ObjectNode body,String match) {
        var request=given().header("Authorization","Bearer test-a").contentType("application/json");
        if(body!=null)request.body(body.toString());if(match!=null)request.header("If-Match",match);
        return request.request(method,"/api/v1"+path);
    }
    private ObjectNode json(String value){return (ObjectNode)JsonValues.parse(value);}
    private ObjectNode create(String path,ObjectNode value) {
        var reply=call("POST",path,value,null);assertEquals(201,reply.statusCode(),reply.asString());return json(reply.asString());
    }
    private ObjectNode cap(){return json("{\"readable\":true,\"writable\":true,\"deletable\":true,\"note\":null}");}
    private void capabilities(ObjectNode connection,ObjectNode fields) {
        var value=json("{\"version\":1,\"providerApiVersion\":\"fixture-v1\",\"fields\":{}}");value.set("fields",fields);
        String path="/task-connections/"+connection.path("id").asText();
        var response=call("PUT",path+"/capabilities",value,call("GET",path,null,null).header("ETag"));assertEquals(200,response.statusCode(),response.asString());
    }
    private record Fixture(ObjectNode connection,ObjectNode list,ObjectNode task,ObjectNode reference,String path) {}
    private Fixture fixture() {
        var connectionInput=json("{\"providerId\":\"fixture\",\"name\":\"Series provider\",\"readable\":true,\"writable\":true,\"enabled\":true}").put("remoteAccountId",UUID.randomUUID().toString());
        var connection=create("/task-connections",connectionInput);
        var caps=JsonValues.object();caps.set("summary",cap());caps.set("recurrence.series",cap());capabilities(connection,caps);
        var listInput=json("{\"remoteListId\":\"list\",\"name\":\"Series list\",\"kind\":\"STANDARD\",\"readable\":true,\"writable\":true,\"shared\":false,\"owner\":true,\"present\":true,\"position\":0}");listInput.set("connectionId",connection.get("id"));var list=create("/task-lists",listInput);
        var input=json("""
            {"projectId":null,"parentTaskId":null,"summary":"Recurring task","url":null,"recurrence":{
              "series":{"rule":"FREQ=WEEKLY;BYDAY=MO","end":"COUNT","until":null,"count":8},
              "occurrences":[{"original":{"kind":"DATE","date":"2026-10-26"},"movedTo":null,"state":"COMPLETED","completedAt":"2026-10-26T12:00:00Z"}]}}
            """);var task=create("/tasks",input);String path="/tasks/"+task.path("id").asText();
        var reference=json("{\"providerId\":\"fixture\",\"externalId\":\"recurring-task\",\"externalUrl\":null}").put("repositoryId",connection.path("remoteAccountId").asText()+"/list");reference.set("taskListId",list.get("id"));
        var baseline=json("{\"version\":1,\"capturedAt\":\"2026-10-28T10:00:00Z\",\"fields\":{}}");((ObjectNode)baseline.get("fields")).set("recurrence",task.get("recurrence"));reference.set("baseline",baseline);
        var reply=call("PUT",path+"/external-references",reference,call("GET",path,null,null).header("ETag"));assertEquals(200,reply.statusCode(),reply.asString());
        return new Fixture(connection,list,task,reference,path);
    }
    private ObjectNode proposal(Fixture f,ObjectNode recurrence) {
        var item=JsonValues.object().put("operationId",UUID.randomUUID().toString()).put("deleted",false);
        for(String key:java.util.List.of("providerId","repositoryId","externalId"))item.set(key,f.reference.get(key));
        item.putObject("fields").set("recurrence",recurrence);
        var input=JsonValues.object().put("sourceState","HEALTHY");input.set("connectionId",f.connection.get("id"));input.set("listId",f.list.get("id"));input.set("item",item);return input;
    }
    private Response preview(ObjectNode input) {
        var value=input.deepCopy();var item=value.remove("item");value.putArray("items").add(item);value.put("scanComplete",false);
        return call("POST","/task-sync/preview",value,null);
    }
    private Response export(Fixture f) {
        var input=JsonValues.object();input.set("connectionId",f.connection.get("id"));input.set("listId",f.list.get("id"));input.set("taskId",f.task.get("id"));
        for(String key:java.util.List.of("providerId","repositoryId","externalId"))input.set(key,f.reference.get(key));
        return call("POST","/task-sync/export-preview",input,null);
    }
    @Test void seriesOnlyUpdatesPreserveUnseenHistoryAndMergeIndependentEdits() {
        var f=fixture();var mask=JsonValues.object();mask.set("series",f.task.path("recurrence").path("series").deepCopy());((ObjectNode)mask.get("series")).put("count",12);
        var input=proposal(f,mask);var preview=preview(input);assertEquals(200,preview.statusCode(),preview.asString());
        assertEquals("UPDATE",json(preview.asString()).path("items").get(0).path("status").asText());
        String oldTag=call("GET",f.path,null,null).header("ETag");
        var applied=call("POST","/task-sync/apply",input,oldTag);assertEquals(200,applied.statusCode(),applied.asString());var result=json(applied.asString());
        assertEquals(f.task.path("recurrence").path("occurrences"),result.path("recurrence").path("occurrences"));
        assertEquals(mask.get("series"),result.path("recurrence").path("series"));
        var replay=call("POST","/task-sync/apply",input,oldTag);assertEquals(200,replay.statusCode());assertEquals(result.path("version"),json(replay.asString()).path("version"));
        var local=json("{\"occurrences\":[]}");local.set("occurrences",result.path("recurrence").path("occurrences").deepCopy());
        ((com.fasterxml.jackson.databind.node.ArrayNode)local.get("occurrences")).add(json("{\"original\":{\"kind\":\"DATE\",\"date\":\"2026-11-02\"},\"movedTo\":null,\"state\":\"SKIPPED\",\"completedAt\":null}"));
        var edited=call("PUT",f.path+"/occurrences",local,applied.header("ETag"));assertEquals(200,edited.statusCode());
        ((ObjectNode)mask.get("series")).put("count",16);input=proposal(f,mask);
        var next=call("POST","/task-sync/apply",input,edited.header("ETag"));assertEquals(200,next.statusCode(),next.asString());
        assertEquals(local.get("occurrences"),json(next.asString()).path("recurrence").path("occurrences"));
        var changed=(ObjectNode)json(next.asString()).path("recurrence").deepCopy();((ObjectNode)changed.get("series")).put("count",20);
        var localSeries=call("PUT",f.path+"/recurrence",changed,next.header("ETag"));assertEquals(200,localSeries.statusCode());
        ((ObjectNode)mask.get("series")).put("count",24);input=proposal(f,mask);
        assertEquals(409,call("POST","/task-sync/apply",input,localSeries.header("ETag")).statusCode());
        var outbound=export(f);assertEquals(200,outbound.statusCode());var expected=JsonValues.object();expected.putObject("recurrence").set("series",changed.get("series"));
        assertEquals(expected,json(outbound.asString()).path("writeFields"));
    }
    @Test void clearsExportsAndInitialImportsRequireConsistentObservedState() {
        var f=fixture();var clear=json("{\"series\":null,\"occurrences\":[]}");
        var input=proposal(f,clear);var blocked=preview(input);assertEquals(200,blocked.statusCode());
        assertEquals("CONFLICT",json(blocked.asString()).path("items").get(0).path("status").asText());
        assertTrue(blocked.asString().contains("INCOMPLETE_GROUP"));
        var before=call("GET",f.path,null,null);
        assertEquals(409,call("POST","/task-sync/apply",input,before.header("ETag")).statusCode());
        assertEquals(json(before.asString()),json(call("GET",f.path,null,null).asString()));
        // An explicit read-only component overrides the complete legacy declaration.
        var caps=JsonValues.object();caps.set("summary",cap());caps.set("recurrence",cap());
        caps.set("recurrence.occurrences",json("{\"readable\":false,\"writable\":false,\"deletable\":false,\"note\":null}"));capabilities(f.connection,caps);
        input=proposal(f,clear);blocked=preview(input);assertEquals("CONFLICT",json(blocked.asString()).path("items").get(0).path("status").asText());
        assertEquals(409,call("POST","/task-sync/apply",input,before.header("ETag")).statusCode());
        var empty=call("PUT",f.path+"/recurrence",clear,before.header("ETag"));assertEquals(200,empty.statusCode());
        var outbound=export(f);assertEquals(200,outbound.statusCode());assertTrue(json(outbound.asString()).path("writeFields").isEmpty());
        assertTrue(outbound.asString().contains("INCOMPLETE_GROUP"));
        // Legacy whole-object export cannot include a prohibited exception clear
        // just because the rule itself is writable.
        caps.remove("recurrence.occurrences");((ObjectNode)caps.get("recurrence")).put("deletable",false);capabilities(f.connection,caps);
        var changed=clear.deepCopy();changed.set("series",f.task.path("recurrence").path("series").deepCopy());((ObjectNode)changed.get("series")).put("count",12);
        var edited=call("PUT",f.path+"/recurrence",changed,empty.header("ETag"));assertEquals(200,edited.statusCode());
        outbound=export(f);assertTrue(json(outbound.asString()).path("writeFields").isEmpty());
        assertTrue(outbound.asString().contains("CLEAR_UNSUPPORTED"));assertTrue(outbound.asString().contains("INCOMPLETE_GROUP"));
        ((ObjectNode)caps.get("recurrence")).put("deletable",true);capabilities(f.connection,caps);
        outbound=export(f);assertEquals(changed,json(outbound.asString()).path("writeFields").path("recurrence"));
        var reset=call("PUT",f.path+"/recurrence",(ObjectNode)f.task.path("recurrence").deepCopy(),edited.header("ETag"));assertEquals(200,reset.statusCode());
        input=proposal(f,clear);var accepted=preview(input);assertEquals("UPDATE",json(accepted.asString()).path("items").get(0).path("status").asText());
        var cleared=call("POST","/task-sync/apply",input,reset.header("ETag"));assertEquals(200,cleared.statusCode(),cleared.asString());
        assertEquals(clear,json(cleared.asString()).path("recurrence"));
        var noop=call("POST","/task-sync/apply",proposal(f,JsonValues.object()),cleared.header("ETag"));assertEquals(200,noop.statusCode());
        assertEquals(json(cleared.asString()).path("version"),json(noop.asString()).path("version"));
        // A series-only initial import does not claim that provider history is empty.
        var seriesMask=JsonValues.object();seriesMask.set("series",f.task.path("recurrence").path("series"));
        var initial=proposal(f,seriesMask);((ObjectNode)initial.path("item")).put("externalId",UUID.randomUUID().toString());
        ((ObjectNode)initial.path("item").path("fields")).put("summary","New recurring item");
        var imported=call("POST","/task-sync/import",initial,null);assertEquals(201,imported.statusCode(),imported.asString());var created=json(imported.asString());
        assertTrue(created.path("recurrence").path("occurrences").isEmpty());
        assertEquals(seriesMask,created.path("externalReferences").get(0).path("baseline").path("fields").path("recurrence"));
        String path="/tasks/"+created.path("id").asText();
        var localClear=call("PUT",path+"/recurrence",clear,imported.header("ETag"));assertEquals(200,localClear.statusCode());
        var newRef=(ObjectNode)created.path("externalReferences").get(0).deepCopy();
        var importedFixture=new Fixture(f.connection,f.list,created,newRef,path);
        outbound=export(importedFixture);assertTrue(json(outbound.asString()).path("writeFields").isEmpty());assertTrue(outbound.asString().contains("INCOMPLETE_GROUP"));
        var observeEmpty=proposal(importedFixture,json("{\"occurrences\":[]}"));
        var learned=call("POST","/task-sync/apply",observeEmpty,localClear.header("ETag"));assertEquals(200,learned.statusCode(),learned.asString());
        outbound=export(importedFixture);assertEquals(clear,json(outbound.asString()).path("writeFields").path("recurrence"));
        // No rule can be inferred from exceptions alone on first import.
        var missingRule=proposal(f,JsonValues.object());((ObjectNode)missingRule.path("item")).put("externalId",UUID.randomUUID().toString());
        ((ObjectNode)missingRule.path("item").path("fields")).put("summary","No rule");
        ((ObjectNode)missingRule.path("item").path("fields").path("recurrence")).set("occurrences",f.task.path("recurrence").path("occurrences"));
        var incomplete=preview(missingRule);assertEquals(200,incomplete.statusCode());
        assertEquals("INCOMPLETE",json(incomplete.asString()).path("items").get(0).path("status").asText());
        assertEquals("RECURRENCE_SERIES_REQUIRED",json(incomplete.asString()).path("items").get(0).path("reason").asText());
        assertEquals(422,call("POST","/task-sync/import",missingRule,null).statusCode());
    }

    @Test void malformedLegacyBaselineDoesNotClaimThatUnobservedHistoryIsEmpty() {
        var f=fixture();var legacy=f.reference.deepCopy();
        ((ObjectNode)legacy.path("baseline").path("fields").path("recurrence")).putNull("occurrences");
        var linked=call("PUT",f.path+"/external-references",legacy,call("GET",f.path,null,null).header("ETag"));assertEquals(200,linked.statusCode());
        var clear=call("PUT",f.path+"/recurrence",json("{\"series\":null,\"occurrences\":[]}"),linked.header("ETag"));assertEquals(200,clear.statusCode());
        var outbound=export(f);assertEquals(200,outbound.statusCode());
        assertTrue(json(outbound.asString()).path("writeFields").isEmpty(),outbound.asString());
        assertTrue(outbound.asString().contains("INCOMPLETE_GROUP"));
        assertTrue(json(call("GET",f.path,null,null).asString()).path("externalReferences").get(0).path("baseline").path("fields").path("recurrence").path("occurrences").isNull());
    }

    @Test void equivalentWallTimeRepresentationsDoNotInventRecurrenceChanges() {
        var f=fixture();var recurrence=json("""
            {"series":{"rule":"FREQ=DAILY","end":"UNTIL","until":{"kind":"DATE_TIME","date":"2026-12-31","time":"09:00:00","timeZone":"Europe/Oslo"},"count":null},
             "occurrences":[{"original":{"kind":"DATE_TIME","date":"2026-03-29","time":"02:30:00","timeZone":"Europe/Oslo"},"movedTo":null,"state":"SKIPPED","completedAt":null}]}
            """);
        var edited=call("PUT",f.path+"/recurrence",recurrence,call("GET",f.path,null,null).header("ETag"));assertEquals(200,edited.statusCode());
        var reference=f.reference.deepCopy();((ObjectNode)reference.path("baseline").path("fields")).set("recurrence",recurrence);
        var linked=call("PUT",f.path+"/external-references",reference,edited.header("ETag"));assertEquals(200,linked.statusCode());
        var caps=JsonValues.object();caps.set("recurrence",cap());capabilities(f.connection,caps);
        var result=preview(proposal(f,recurrence));assertEquals(200,result.statusCode(),result.asString());
        assertEquals("UNCHANGED",json(result.asString()).path("items").get(0).path("status").asText());
        var exported=export(f);assertTrue(json(exported.asString()).path("writeFields").isEmpty());
        var current=json(call("GET",f.path,null,null).asString()).path("recurrence");
        assertEquals("02:30",current.path("occurrences").get(0).path("original").path("time").asText());
        assertEquals("Europe/Oslo",current.path("occurrences").get(0).path("original").path("timeZone").asText());
    }

    @Test void occurrenceExportsNeedAnObservedRuleUnlessTheListIsBeingCleared() {
        var f=fixture();var caps=JsonValues.object();caps.set("recurrence.occurrences",cap());capabilities(f.connection,caps);
        var reference=f.reference.deepCopy();((ObjectNode)reference.path("baseline").path("fields").path("recurrence")).remove("series");
        var linked=call("PUT",f.path+"/external-references",reference,call("GET",f.path,null,null).header("ETag"));assertEquals(200,linked.statusCode());
        var occurrences=json("{\"occurrences\":[]}");occurrences.set("occurrences",f.task.path("recurrence").path("occurrences").deepCopy());
        ((com.fasterxml.jackson.databind.node.ArrayNode)occurrences.get("occurrences")).add(json("{\"original\":{\"kind\":\"DATE\",\"date\":\"2026-11-02\"},\"movedTo\":null,\"state\":\"SKIPPED\",\"completedAt\":null}"));
        var local=call("PUT",f.path+"/occurrences",occurrences,linked.header("ETag"));assertEquals(200,local.statusCode());
        var outbound=export(f);assertTrue(json(outbound.asString()).path("writeFields").isEmpty());assertTrue(outbound.asString().contains("INCOMPLETE_GROUP"));
        var empty=call("PUT",f.path+"/occurrences",json("{\"occurrences\":[]}"),local.header("ETag"));assertEquals(200,empty.statusCode());
        outbound=export(f);assertEquals(json("{\"recurrence\":{\"occurrences\":[]}}"),json(outbound.asString()).path("writeFields"));
        local=call("PUT",f.path+"/occurrences",occurrences,empty.header("ETag"));assertEquals(200,local.statusCode());
        caps.set("recurrence.series",cap());capabilities(f.connection,caps);
        var observed=JsonValues.object();observed.set("series",f.task.path("recurrence").path("series"));
        var learned=call("POST","/task-sync/apply",proposal(f,observed),local.header("ETag"));assertEquals(200,learned.statusCode(),learned.asString());
        outbound=export(f);var expected=JsonValues.object();expected.set("recurrence",occurrences);
        assertEquals(expected,json(outbound.asString()).path("writeFields"));
    }

}
