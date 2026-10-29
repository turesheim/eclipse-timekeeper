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

/** Portable imports retain the meaning of normalized sync baselines in the new graph. */
@QuarkusTest @TestProfile(TaskSyncImportHttpTest.Profile.class)
class TaskSyncImportHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:task_sync_import");}
    }
    private Response call(String owner,String method,String path,ObjectNode body,String match) {
        var request=given().header("Authorization","Bearer test-"+owner).contentType("application/json");
        if(body!=null)request.body(body.toString());if(match!=null)request.header("If-Match",match);
        if(path.equals("/data/import"))request.header("Idempotency-Key",UUID.randomUUID().toString());
        return request.request(method,"/api/v1"+path);
    }
    private ObjectNode json(String value){return (ObjectNode)JsonValues.parse(value);}
    private ObjectNode create(String path,ObjectNode value) {
        var reply=call("a","POST",path,value,null);assertEquals(201,reply.statusCode(),reply.asString());return json(reply.asString());
    }
    private String mapped(ObjectNode response,String id) {
        for(var item:response.path("ids"))if(item.path("sourceId").asText().equals(id))return item.path("id").asText();
        fail("No mapping for "+id);return null;
    }
    @Test void importedBaselinesUseNewOwnedIdsWithoutRewritingOpaqueProviderValues() {
        var connection=create("/task-connections",json("""
            {"providerId":"fixture","remoteAccountId":"baseline-account","name":"Fixture","readable":true,"writable":true,"enabled":true}
            """));
        var caps=json("""
            {"version":1,"providerApiVersion":"v1","fields":{
              "parentTaskId":{"readable":true,"writable":true,"deletable":true,"note":null},
              "siblingOrder":{"readable":true,"writable":true,"deletable":true,"note":null},
              "taskListId":{"readable":true,"writable":true,"deletable":true,"note":null},
              "sectionId":{"readable":true,"writable":true,"deletable":true,"note":null}}}
            """);
        String cp="/task-connections/"+connection.path("id").asText();
        assertEquals(200,call("a","PUT",cp+"/capabilities",caps,call("a","GET",cp,null,null).header("ETag")).statusCode());
        var listInput=json("""
            {"remoteListId":"baseline-list","name":"List","kind":"STANDARD","readable":true,"writable":true,"shared":false,"owner":true,"present":true,"position":0}
            """);listInput.set("connectionId",connection.get("id"));var list=create("/task-lists",listInput);
        var sectionInput=json("""
            {"remoteSectionId":"baseline-section","name":"Section","kind":"STANDARD","position":0,"present":true,"writable":true}
            """);sectionInput.set("taskListId",list.get("id"));var section=create("/task-list-sections",sectionInput);
        var project=create("/projects",json("{\"name\":\"Baseline project\"}"));
        var parentInput=json("{\"projectId\":null,\"parentTaskId\":null,\"summary\":\"Parent\",\"url\":null}");parentInput.set("projectId",project.get("id"));var parent=create("/tasks",parentInput);
        var childInput=parentInput.deepCopy().put("summary","Child").put("siblingOrder",2);childInput.set("parentTaskId",parent.get("id"));var child=create("/tasks",childInput);
        var fields=JsonValues.object();fields.set("parentTaskId",parent.get("id"));fields.put("siblingOrder",2);fields.set("taskListId",list.get("id"));fields.set("sectionId",section.get("id"));
        var baseline=json("{\"version\":1,\"capturedAt\":\"2026-10-28T10:00:00Z\",\"fields\":{}}");baseline.set("fields",fields);
        var data=json("{\"namespace\":\"fixture.tasks\",\"version\":2,\"payload\":{}}");((ObjectNode)data.get("payload")).setAll(fields.deepCopy());
        var reference=json("{\"providerId\":\"fixture\",\"repositoryId\":\"baseline-account/baseline-list\",\"externalId\":\"child\",\"externalUrl\":null}");
        reference.set("taskListId",list.get("id"));reference.set("sectionId",section.get("id"));reference.set("baseline",baseline);reference.set("providerData",data);
        String path="/tasks/"+child.path("id").asText();
        var linked=call("a","PUT",path+"/external-references",reference,call("a","GET",path,null,null).header("ETag"));assertEquals(200,linked.statusCode(),linked.asString());
        var future=create("/tasks",parentInput.deepCopy().put("summary","Historical parent created later"));
        var forward=reference.deepCopy().put("externalId","child-forward");
        ((ObjectNode)forward.path("baseline").path("fields")).set("parentTaskId",future.get("id"));
        var forwardReply=call("a","PUT",path+"/external-references",forward,linked.header("ETag"));assertEquals(200,forwardReply.statusCode(),forwardReply.asString());
        var unresolved=reference.deepCopy().put("externalId","child-unknown");
        var oldFields=(ObjectNode)unresolved.path("baseline").path("fields");
        String missingParent=UUID.randomUUID().toString(),missingList=UUID.randomUUID().toString();
        oldFields.put("parentTaskId",missingParent);oldFields.put("taskListId",missingList);
        oldFields.put("summary","Keep historical comparison");oldFields.set("body",json("{\"format\":\"TEXT\",\"content\":\""+missingParent+"\"}"));
        var unresolvedReply=call("a","PUT",path+"/external-references",unresolved,forwardReply.header("ETag"));assertEquals(200,unresolvedReply.statusCode(),unresolvedReply.asString());
        var exported=json(call("a","GET","/data/export",null,null).asString());
        // Make the historical baseline parent a genuine forward reference at creation.
        var ordered=JsonValues.array();
        for(var id:java.util.List.of(parent.get("id"),child.get("id"),future.get("id")))
            for(var task:exported.path("tasks"))if(task.path("id").equals(id))ordered.add(task.deepCopy());
        exported.set("tasks",ordered);
        var imported=call("b","POST","/data/import",exported,null);assertEquals(201,imported.statusCode(),imported.asString());var mappings=json(imported.asString());
        String targetId=mapped(mappings,child.path("id").asText());
        var copied=json(call("b","GET","/tasks/"+targetId,null,null).asString());var copiedRef=copied.path("externalReferences").get(0);
        var expected=fields.deepCopy();expected.put("parentTaskId",mapped(mappings,parent.path("id").asText()));expected.put("taskListId",mapped(mappings,list.path("id").asText()));expected.put("sectionId",mapped(mappings,section.path("id").asText()));
        assertEquals(expected,copiedRef.path("baseline").path("fields"));
        assertEquals(baseline.path("capturedAt"),copiedRef.path("baseline").path("capturedAt"));
        assertEquals(data,copiedRef.path("providerData"));
        for(var value:copied.path("externalReferences")) {
            assertEquals(data,value.path("providerData"));
            if(value.path("externalId").asText().equals("child-forward"))
                assertEquals(mapped(mappings,future.path("id").asText()),value.path("baseline").path("fields").path("parentTaskId").asText());
            if(value.path("externalId").asText().equals("child-unknown")) {
                var unknown=value.path("baseline").path("fields");
                assertFalse(unknown.has("parentTaskId"));assertFalse(unknown.has("siblingOrder"));
                assertFalse(unknown.has("taskListId"));assertFalse(unknown.has("sectionId"));
                assertEquals(oldFields.path("summary"),unknown.path("summary"));assertEquals(oldFields.path("body"),unknown.path("body"));
            }
        }
        var export=JsonValues.object();export.put("taskId",targetId);export.put("connectionId",mapped(mappings,connection.path("id").asText()));export.put("listId",mapped(mappings,list.path("id").asText()));
        for(String key:java.util.List.of("providerId","repositoryId","externalId"))export.set(key,reference.get(key));
        var proposal=call("b","POST","/task-sync/export-preview",export,null);assertEquals(200,proposal.statusCode(),proposal.asString());
        assertTrue(json(proposal.asString()).path("writeFields").isEmpty());
        assertFalse(proposal.asString().contains("IDENTITY_RECONCILIATION_REQUIRED"));
    }
    @Test void unassignedSubtasksAreValidPortableGraphs() {
        String parent=UUID.randomUUID().toString(),child=UUID.randomUUID().toString();
        var input=json("""
            {"format":"timekeeper-domain-v3","zoneId":"UTC","clients":[],"projects":[],"tasks":[],"labels":[],"activities":[],"workSessions":[]}
            """);
        var first=json("{\"projectId\":null,\"parentTaskId\":null,\"summary\":\"Unassigned parent\",\"url\":null,\"externalReferences\":[]}").put("id",parent);
        var second=first.deepCopy().put("id",child).put("parentTaskId",parent).put("summary","Unassigned child");
        input.putArray("tasks").add(second).add(first);
        var imported=call("b","POST","/data/import",input,null);assertEquals(201,imported.statusCode(),imported.asString());var mappings=json(imported.asString());
        var copied=json(call("b","GET","/tasks/"+mapped(mappings,child),null,null).asString());
        assertEquals(mapped(mappings,parent),copied.path("parentTaskId").asText());assertTrue(copied.path("projectId").isNull());
        var before=json(call("b","GET","/data/export",null,null).asString());
        var cyclic=input.deepCopy();((ObjectNode)cyclic.path("tasks").get(0)).put("parentTaskId",child);
        assertEquals(422,call("b","POST","/data/import",cyclic,null).statusCode());
        String client=UUID.randomUUID().toString(),firstProject=UUID.randomUUID().toString(),secondProject=UUID.randomUUID().toString();
        var mismatched=input.deepCopy();mismatched.putArray("clients").add(json("{\"name\":\"Guard client\",\"color\":\"#123456\"}").put("id",client));
        mismatched.putArray("projects").add(json("{\"name\":\"First\"}").put("id",firstProject).put("clientId",client))
                .add(json("{\"name\":\"Second\"}").put("id",secondProject).put("clientId",client));
        ((ObjectNode)mismatched.path("tasks").get(0)).put("projectId",secondProject);
        ((ObjectNode)mismatched.path("tasks").get(1)).put("projectId",firstProject);
        assertEquals(422,call("b","POST","/data/import",mismatched,null).statusCode());
        assertEquals(before,json(call("b","GET","/data/export",null,null).asString()));
    }
}
