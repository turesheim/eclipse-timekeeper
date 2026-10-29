package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

/** A provider's observable metadata must not replace unrelated task metadata. */
@QuarkusTest @TestProfile(TaskSyncMetadataHttpTest.Profile.class)
class TaskSyncMetadataHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:task_sync_metadata");}
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
    @Test void microsoftCategoryPullPreservesAppleTagsAndUnrelatedLinks() {
        var connection=create("/task-connections",json("""
            {"providerId":"microsoft","remoteAccountId":"metadata-account","name":"Metadata account","readable":true,"writable":true,"enabled":true}
            """));
        String connectionPath="/task-connections/"+connection.path("id").asText();
        var capability=json("""
            {"version":1,"providerApiVersion":"graph-v1.0","fields":{
              "metadata.categories":{"readable":true,"writable":true,"deletable":true,"note":null}}}
            """);
        assertEquals(200,call("PUT",connectionPath+"/capabilities",capability,call("GET",connectionPath,null,null).header("ETag")).statusCode());
        var listInput=json("""
            {"remoteListId":"metadata-list","name":"Metadata list","kind":"STANDARD","readable":true,"writable":true,"shared":false,"owner":true,"present":true,"position":0}
            """);listInput.set("connectionId",connection.get("id"));var list=create("/task-lists",listInput);
        var input=json("""
            {"projectId":null,"parentTaskId":null,"summary":"Shared task","url":null,"metadata":{
              "tags":["Apple tag"],"categories":["Before"],"links":[]}}
            """);
        String linkId=UUID.randomUUID().toString();
        ((com.fasterxml.jackson.databind.node.ArrayNode)input.path("metadata").path("links")).add(json("""
            {"kind":"LOCAL","url":"https://example.test/local","title":"Local link","providerId":null,"remoteId":null}
            """).put("id",linkId));
        var task=create("/tasks",input);String path="/tasks/"+task.path("id").asText();
        var reference=json("""
            {"providerId":"microsoft","repositoryId":"metadata-account/metadata-list","externalId":"metadata-task","externalUrl":null,
             "baseline":{"version":1,"capturedAt":"2026-10-28T10:00:00Z","fields":{"metadata":{"categories":["Before"]}}}}
            """);reference.set("taskListId",list.get("id"));
        var linked=call("PUT",path+"/external-references",reference,call("GET",path,null,null).header("ETag"));assertEquals(200,linked.statusCode(),linked.asString());
        var item=json("""
            {"providerId":"microsoft","repositoryId":"metadata-account/metadata-list","externalId":"metadata-task","deleted":false,
             "fields":{"metadata":{"categories":["After"]}}}
            """).put("operationId",UUID.randomUUID().toString());
        var request=JsonValues.object();request.set("connectionId",connection.get("id"));request.set("listId",list.get("id"));request.put("sourceState","HEALTHY");
        var preview=request.deepCopy();preview.put("scanComplete",true);preview.putArray("items").add(item);
        var response=call("POST","/task-sync/preview",preview,null);assertEquals(200,response.statusCode(),response.asString());
        var decision=json(response.asString()).path("items").get(0);
        assertEquals("UPDATE",decision.path("status").asText());
        assertEquals("metadata.categories",decision.path("decisions").get(0).path("field").asText());
        request.set("item",item);
        var updated=call("POST","/task-sync/apply",request,linked.header("ETag"));assertEquals(200,updated.statusCode(),updated.asString());
        var result=json(updated.asString());assertEquals(input.path("metadata").path("tags"),result.path("metadata").path("tags"));
        assertEquals(input.path("metadata").path("links"),result.path("metadata").path("links"));
        assertEquals("After",result.path("metadata").path("categories").get(0).asText());
        assertEquals(result.path("metadata").path("categories"),result.path("externalReferences").get(0).path("baseline").path("fields").path("metadata").path("categories"));
        var retry=call("POST","/task-sync/apply",request,linked.header("ETag"));assertEquals(200,retry.statusCode());
        assertEquals(result.path("version"),json(retry.asString()).path("version"));
        // A local tag edit and a provider category edit are independent, while
        // competing edits of the category itself still conflict.
        var localMetadata=(ObjectNode)result.path("metadata").deepCopy();localMetadata.putArray("tags").add("Apple tag").add("New local tag");
        var localEdit=call("PUT",path+"/metadata",localMetadata,updated.header("ETag"));assertEquals(200,localEdit.statusCode());
        var nextItem=item.deepCopy();nextItem.put("operationId",UUID.randomUUID().toString());
        ((ObjectNode)nextItem.path("fields")).set("metadata",json("{\"categories\":[\"Next\"]}"));
        request.set("item",nextItem);
        var next=call("POST","/task-sync/apply",request,localEdit.header("ETag"));assertEquals(200,next.statusCode(),next.asString());
        assertEquals(localMetadata.path("tags"),json(next.asString()).path("metadata").path("tags"));
        var editedMetadata=(ObjectNode)json(next.asString()).path("metadata").deepCopy();editedMetadata.putArray("categories").add("Locally edited category");
        var edited=call("PUT",path+"/metadata",editedMetadata,next.header("ETag"));assertEquals(200,edited.statusCode());
        var conflictItem=nextItem.deepCopy().put("operationId",UUID.randomUUID().toString());
        ((ObjectNode)conflictItem.path("fields")).set("metadata",json("{\"categories\":[\"Other remote category\"]}"));request.set("item",conflictItem);
        assertEquals(409,call("POST","/task-sync/apply",request,edited.header("ETag")).statusCode());
        var export=JsonValues.object();export.set("connectionId",connection.get("id"));export.set("listId",list.get("id"));export.set("taskId",task.get("id"));
        for(String key:java.util.List.of("providerId","repositoryId","externalId"))export.set(key,reference.get(key));
        var outbound=call("POST","/task-sync/export-preview",export,null);assertEquals(200,outbound.statusCode(),outbound.asString());
        assertEquals(json("{\"metadata\":{\"categories\":[\"Locally edited category\"]}}"),json(outbound.asString()).path("writeFields"));
        // Explicit clearing affects only the readable component, even when an
        // unreadable tag clear is included in the provider observation.
        var clear=nextItem.deepCopy().put("operationId",UUID.randomUUID().toString());
        ((ObjectNode)clear.path("fields")).set("metadata",json("{\"categories\":[],\"tags\":[]}"));
        // Use a separate task state matching the remote baseline before clearing.
        editedMetadata.putArray("categories").add("Next");
        var restored=call("PUT",path+"/metadata",editedMetadata,edited.header("ETag"));assertEquals(200,restored.statusCode());
        request.set("item",clear);var cleared=call("POST","/task-sync/apply",request,restored.header("ETag"));assertEquals(200,cleared.statusCode(),cleared.asString());
        assertTrue(json(cleared.asString()).path("metadata").path("categories").isEmpty());
        assertEquals(localMetadata.path("tags"),json(cleared.asString()).path("metadata").path("tags"));
        assertEquals(input.path("metadata").path("links"),json(cleared.asString()).path("metadata").path("links"));
        var legacy=json("""
            {"version":1,"providerApiVersion":"compatibility","fields":{
             "metadata":{"readable":true,"writable":true,"deletable":true,"note":null},
             "metadata.tags":{"readable":false,"writable":false,"deletable":false,"note":null}}}
            """);
        assertEquals(200,call("PUT",connectionPath+"/capabilities",legacy,call("GET",connectionPath,null,null).header("ETag")).statusCode());
        var fallback=nextItem.deepCopy().put("operationId",UUID.randomUUID().toString());
        ((ObjectNode)fallback.path("fields")).set("metadata",json("{\"categories\":[\"Aggregate fallback\"],\"tags\":[]}"));request.set("item",fallback);
        var compatible=call("POST","/task-sync/apply",request,cleared.header("ETag"));assertEquals(200,compatible.statusCode(),compatible.asString());
        assertEquals("Aggregate fallback",json(compatible.asString()).path("metadata").path("categories").get(0).asText());
        assertEquals(localMetadata.path("tags"),json(compatible.asString()).path("metadata").path("tags"));
    }
    @Test void readOnlySourceLinksFromTwoAccountsRequireReconciliationWithoutLosingLocalLinks() {
        var connections=new ObjectNode[2];var lists=new ObjectNode[2];var references=new ObjectNode[2];
        var links=JsonValues.array();
        links.add(json("{\"kind\":\"LOCAL\",\"url\":\"https://example.test/local\",\"title\":\"Local\",\"providerId\":null,\"remoteId\":null}").put("id",UUID.randomUUID().toString()));
        for(int index=0;index<2;index++) {
            var connectionInput=json("{\"providerId\":\"fixture\",\"name\":\"Read-only links\",\"readable\":true,\"writable\":true,\"enabled\":true}")
                    .put("remoteAccountId",UUID.randomUUID().toString());
            connections[index]=create("/task-connections",connectionInput);
            String connectionPath="/task-connections/"+connections[index].path("id").asText();
            var caps=json("{\"version\":1,\"providerApiVersion\":\"fixture-v1\",\"fields\":{\"metadata.links\":{\"readable\":true,\"writable\":false,\"deletable\":false,\"note\":\"Output-only source context\"}}}");
            var setCaps=call("PUT",connectionPath+"/capabilities",caps,call("GET",connectionPath,null,null).header("ETag"));assertEquals(200,setCaps.statusCode(),setCaps.asString());
            var listInput=json("{\"remoteListId\":\"list\",\"name\":\"Links\",\"kind\":\"STANDARD\",\"readable\":true,\"writable\":true,\"shared\":false,\"owner\":true,\"present\":true,\"position\":0}");
            listInput.set("connectionId",connections[index].get("id"));lists[index]=create("/task-lists",listInput);
            // Identical provider and remote IDs in different accounts are not
            // evidence that the source links refer to the same object.
            links.add(json("{\"kind\":\"PROVIDER_SOURCE\",\"title\":\"Source\",\"providerId\":\"fixture\",\"remoteId\":\"same-source-id\"}")
                    .put("id",UUID.randomUUID().toString()).put("url","https://example.test/account-"+index));
        }
        var taskInput=json("{\"projectId\":null,\"parentTaskId\":null,\"summary\":\"Two source accounts\",\"url\":\"https://example.test/primary\",\"metadata\":{\"tags\":[],\"categories\":[]}}");
        ((ObjectNode)taskInput.get("metadata")).set("links",links);
        var task=create("/tasks",taskInput);String path="/tasks/"+task.path("id").asText();
        for(int index=0;index<2;index++) {
            var ref=json("{\"providerId\":\"fixture\",\"externalId\":\"same-task-id\",\"externalUrl\":null}")
                    .put("repositoryId",connections[index].path("remoteAccountId").asText()+"/list");
            ref.set("taskListId",lists[index].get("id"));
            var baseline=json("{\"version\":1,\"capturedAt\":\"2026-10-29T02:00:00Z\",\"fields\":{\"metadata\":{}}}");
            ((ObjectNode)baseline.path("fields").path("metadata")).putArray("links").add(links.get(index+1));ref.set("baseline",baseline);references[index]=ref;
            var linked=call("PUT",path+"/external-references",ref,call("GET",path,null,null).header("ETag"));assertEquals(200,linked.statusCode(),linked.asString());
        }
        var before=call("GET",path,null,null);var original=json(before.asString());
        for(int index=0;index<2;index++) {
            var item=JsonValues.object().put("operationId",UUID.randomUUID().toString()).put("deleted",false);
            for(String key:java.util.List.of("providerId","repositoryId","externalId"))item.set(key,references[index].get(key));
            var observed=item.putObject("fields").putObject("metadata").putArray("links");observed.add(links.get(index+1));
            var input=JsonValues.object().put("sourceState","HEALTHY").put("scanComplete",false);
            input.set("connectionId",connections[index].get("id"));input.set("listId",lists[index].get("id"));input.putArray("items").add(item);
            var unchanged=call("POST","/task-sync/preview",input,null);assertEquals(200,unchanged.statusCode(),unchanged.asString());
            assertEquals("KEEP_LOCAL",json(unchanged.asString()).path("items").get(0).path("status").asText());
            ((ObjectNode)observed.get(0)).put("title","Changed source title");
            var conflict=call("POST","/task-sync/preview",input,null);assertEquals(200,conflict.statusCode(),conflict.asString());
            assertEquals("CONFLICT",json(conflict.asString()).path("items").get(0).path("status").asText());
            input.remove("items");input.remove("scanComplete");input.set("item",item);
            assertEquals(409,call("POST","/task-sync/apply",input,before.header("ETag")).statusCode());
            // A read-only component never exports other account/local resources.
            var outbound=JsonValues.object();outbound.set("connectionId",connections[index].get("id"));outbound.set("listId",lists[index].get("id"));outbound.set("taskId",task.get("id"));
            for(String key:java.util.List.of("providerId","repositoryId","externalId"))outbound.set(key,references[index].get(key));
            var export=call("POST","/task-sync/export-preview",outbound,null);assertEquals(200,export.statusCode(),export.asString());
            var exported=json(export.asString());assertFalse(exported.path("writeFields").path("metadata").has("links"));
            assertTrue(java.util.stream.StreamSupport.stream(exported.path("warnings").spliterator(),false)
                    .anyMatch(w -> w.path("field").asText().equals("metadata.links") && w.path("reason").asText().equals("NOT_WRITABLE")));
            var after=call("GET",path,null,null);assertEquals(before.header("ETag"),after.header("ETag"));assertEquals(original,json(after.asString()));
        }
    }

}
