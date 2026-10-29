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

/** Partial provider observations cannot erase reminder kinds they cannot read. */
@QuarkusTest @TestProfile(TaskSyncRemindersHttpTest.Profile.class)
class TaskSyncRemindersHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:task_sync_reminders");}
    }
    private Response call(String method,String path,ObjectNode body,String match) {
        var request=given().header("Authorization","Bearer test-a").contentType("application/json");
        if(body!=null)request.body(body.toString());
        if(match!=null)request.header("If-Match",match);
        return request.request(method,"/api/v1"+path);
    }
    private ObjectNode json(String value){return (ObjectNode)JsonValues.parse(value);}
    private ObjectNode create(String path,ObjectNode value) {
        var reply=call("POST",path,value,null);assertEquals(201,reply.statusCode(),reply.asString());return json(reply.asString());
    }
    @Test void timedAlertPullPreservesOtherKindsAndUsesIndependentBaselines() {
        var connection=create("/task-connections",json("""
            {"providerId":"microsoft","remoteAccountId":"alerts-account","name":"Alerts account","readable":true,"writable":true,"enabled":true}
            """));
        String connectionPath="/task-connections/"+connection.path("id").asText();
        var capability=json("""
            {"version":1,"providerApiVersion":"graph-v1.0","fields":{
              "reminders.ABSOLUTE":{"readable":true,"writable":true,"deletable":true,"note":null}}}
            """);
        assertEquals(200,call("PUT",connectionPath+"/capabilities",capability,call("GET",connectionPath,null,null).header("ETag")).statusCode());
        var listInput=json("""
            {"remoteListId":"alerts-list","name":"Alerts list","kind":"STANDARD","readable":true,"writable":true,"shared":false,"owner":true,"present":true,"position":0}
            """);listInput.set("connectionId",connection.get("id"));var list=create("/task-lists",listInput);
        var input=json("""
            {"projectId":null,"parentTaskId":null,"summary":"Mixed reminders","url":null,"reminders":[
              {"id":"00000000-0000-4000-8000-000000000001","kind":"ABSOLUTE","at":{"kind":"DATE_TIME","date":"2026-10-29","time":"09:00","timeZone":"Europe/Oslo"}},
              {"id":"00000000-0000-4000-8000-000000000002","kind":"LOCATION","latitude":63.0,"longitude":10.0,"radiusMeters":100.0,"transition":"ARRIVE"},
              {"id":"00000000-0000-4000-8000-000000000003","kind":"CAR","event":"CONNECT"},
              {"id":"00000000-0000-4000-8000-000000000004","kind":"MESSAGE","contactKey":"local-contact"}]}
            """);
        var task=create("/tasks",input);String path="/tasks/"+task.path("id").asText();
        var reference=json("""
            {"providerId":"microsoft","repositoryId":"alerts-account/alerts-list","externalId":"alerts-task","externalUrl":null,
             "baseline":{"version":1,"capturedAt":"2026-10-28T10:00:00Z","fields":{}}}
            """);reference.set("taskListId",list.get("id"));
        // Existing full-array baselines remain usable by a newly granular connector.
        ((ObjectNode)reference.path("baseline").path("fields")).set("reminders",input.path("reminders").deepCopy());
        var linked=call("PUT",path+"/external-references",reference,call("GET",path,null,null).header("ETag"));assertEquals(200,linked.statusCode(),linked.asString());
        var item=json("""
            {"providerId":"microsoft","repositoryId":"alerts-account/alerts-list","externalId":"alerts-task","deleted":false,
             "fields":{"reminders":{"ABSOLUTE":[],"LOCATION":[]}}}
            """).put("operationId",UUID.randomUUID().toString());
        var request=JsonValues.object();request.set("connectionId",connection.get("id"));request.set("listId",list.get("id"));request.put("sourceState","HEALTHY");
        var preview=request.deepCopy();preview.put("scanComplete",true);preview.putArray("items").add(item);
        var response=call("POST","/task-sync/preview",preview,null);assertEquals(200,response.statusCode(),response.asString());
        assertEquals("UPDATE",json(response.asString()).path("items").get(0).path("status").asText());
        request.set("item",item);
        var updated=call("POST","/task-sync/apply",request,linked.header("ETag"));assertEquals(200,updated.statusCode(),updated.asString());
        var result=json(updated.asString());assertEquals(3,result.path("reminders").size());
        for(int i=0;i<3;i++)assertEquals(input.path("reminders").get(i+1),result.path("reminders").get(i));
        var retry=call("POST","/task-sync/apply",request,linked.header("ETag"));assertEquals(200,retry.statusCode());
        assertEquals(result.path("version"),json(retry.asString()).path("version"));
        // A local absolute alert is the only outbound component; the other kinds
        // remain local and do not require a writable capability.
        var local=json("{}");local.set("reminders",input.path("reminders"));
        var edited=call("PUT",path+"/reminders",local,updated.header("ETag"));assertEquals(200,edited.statusCode(),edited.asString());
        var export=JsonValues.object();export.set("connectionId",connection.get("id"));export.set("listId",list.get("id"));export.set("taskId",task.get("id"));
        for(String key:java.util.List.of("providerId","repositoryId","externalId"))export.set(key,reference.get(key));
        var outbound=call("POST","/task-sync/export-preview",export,null);assertEquals(200,outbound.statusCode(),outbound.asString());
        var expected=json("{\"reminders\":{\"ABSOLUTE\":[]}}");
        ((com.fasterxml.jackson.databind.node.ArrayNode)expected.path("reminders").path("ABSOLUTE")).add(input.path("reminders").get(0));
        assertEquals(expected,json(outbound.asString()).path("writeFields"));
        // Competing edits of the same kind remain conflicts, rather than last-write-wins.
        var conflict=item.deepCopy().put("operationId",UUID.randomUUID().toString());
        var remoteAlert=input.path("reminders").get(0).deepCopy();
        ((ObjectNode)remoteAlert.path("at")).put("time","10:00");
        ((ObjectNode)conflict.path("fields").path("reminders")).putArray("ABSOLUTE").add(remoteAlert);
        request.set("item",conflict);
        assertEquals(409,call("POST","/task-sync/apply",request,edited.header("ETag")).statusCode());
        // A supplied group is checked against retained groups for ID collisions.
        var duplicate=item.deepCopy().put("operationId",UUID.randomUUID().toString());
        var alert=input.path("reminders").get(0).deepCopy();
        ((ObjectNode)alert).put("id",input.path("reminders").get(1).path("id").asText());
        ((ObjectNode)duplicate.path("fields").path("reminders")).putArray("ABSOLUTE").add(alert);
        // Reset only the local absolute alert so the proposal reaches domain validation.
        var reset=json("{}");reset.set("reminders",result.path("reminders"));
        var resetReply=call("PUT",path+"/reminders",reset,edited.header("ETag"));assertEquals(200,resetReply.statusCode());
        request.set("item",duplicate);
        assertEquals(422,call("POST","/task-sync/apply",request,resetReply.header("ETag")).statusCode());
        assertEquals(result.path("reminders"),json(call("GET",path,null,null).asString()).path("reminders"));
        // An empty object observes nothing and must not increment the task version.
        var empty=item.deepCopy().put("operationId",UUID.randomUUID().toString());
        ((ObjectNode)empty.path("fields")).set("reminders",JsonValues.object());request.set("item",empty);
        var noop=call("POST","/task-sync/apply",request,resetReply.header("ETag"));assertEquals(200,noop.statusCode());
        assertEquals(json(resetReply.asString()).path("version"),json(noop.asString()).path("version"));
        // A legacy full-array export must not sneak a prohibited clear into an
        // otherwise writable group in the same collection.
        var legacyCapability=json("""
            {"version":1,"providerApiVersion":"legacy","fields":{
              "reminders":{"readable":true,"writable":true,"deletable":false,"note":null}}}
            """);
        assertEquals(200,call("PUT",connectionPath+"/capabilities",legacyCapability,call("GET",connectionPath,null,null).header("ETag")).statusCode());
        var rebaseline=call("PUT",path+"/external-references",reference,noop.header("ETag"));assertEquals(200,rebaseline.statusCode());
        var more=reset.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)more.path("reminders")).add(json("""
            {"kind":"RELATIVE","anchor":"DUE","offsetMinutes":-15}
            """).put("id",UUID.randomUUID().toString()));
        var mixed=call("PUT",path+"/reminders",more,rebaseline.header("ETag"));assertEquals(200,mixed.statusCode());
        var guarded=call("POST","/task-sync/export-preview",export,null);assertEquals(200,guarded.statusCode());
        assertFalse(json(guarded.asString()).path("writeFields").has("reminders"));
        assertTrue(java.util.stream.StreamSupport.stream(json(guarded.asString()).path("warnings").spliterator(),false)
                .anyMatch(row -> row.path("field").asText().equals("reminders.ABSOLUTE") && row.path("reason").asText().equals("CLEAR_UNSUPPORTED")));
        assertTrue(java.util.stream.StreamSupport.stream(json(guarded.asString()).path("warnings").spliterator(),false)
                .anyMatch(row -> row.path("field").asText().equals("reminders") && row.path("reason").asText().equals("INCOMPLETE_GROUP")));
        // When clearing is supported the complete legacy baseline still exports
        // the original array representation.
        ((ObjectNode)legacyCapability.path("fields").path("reminders")).put("deletable",true);
        assertEquals(200,call("PUT",connectionPath+"/capabilities",legacyCapability,call("GET",connectionPath,null,null).header("ETag")).statusCode());
        var legacyExport=call("POST","/task-sync/export-preview",export,null);assertEquals(200,legacyExport.statusCode());
        assertEquals(more.path("reminders"),json(legacyExport.asString()).path("writeFields").path("reminders"));
    }
}
