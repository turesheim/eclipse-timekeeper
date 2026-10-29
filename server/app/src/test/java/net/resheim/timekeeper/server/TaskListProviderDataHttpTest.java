package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

/** List and section provider fields survive ordinary edits independently of task payloads. */
@QuarkusTest @TestProfile(TaskListProviderDataHttpTest.Profile.class)
class TaskListProviderDataHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:task_list_provider_data");}
    }
    private Response call(String owner,String method,String path,ObjectNode body,String match) {
        var request=given().header("Authorization","Bearer test-"+owner).contentType("application/json");
        if(body!=null)request.body(body.toString());if(match!=null)request.header("If-Match",match);
        return request.request(method,"/api/v1"+path);
    }
    private ObjectNode json(String value){return (ObjectNode)JsonValues.parse(value);}
    private ObjectNode create(String path,ObjectNode value) {
        var reply=call("a","POST",path,value,null);assertEquals(201,reply.statusCode(),reply.asString());return json(reply.asString());
    }
    @io.quarkus.test.common.http.TestHTTPResource java.net.URL serverUrl;
    @Test void javaClientRetainsAndExplicitlyClearsListAndSectionEnvelopes() throws Exception {
        try(var client=new net.resheim.eclipse.timekeeper.client.HttpTimekeeperService(serverUrl.toURI(),()->"test-a",java.time.Duration.ofSeconds(5))) {
            var connection=client.createTaskConnection(new net.resheim.eclipse.timekeeper.service.Commands.CreateTaskConnection("icloud","sdk-list-data","iCloud SDK",true,true,true));
            var list=client.createTaskList(new net.resheim.eclipse.timekeeper.service.Commands.CreateTaskList(connection.id(),"sdk-list","Groceries",net.resheim.eclipse.timekeeper.domain.TaskListKind.STANDARD,true,true,false,true,true,0));
            var section=client.createTaskListSection(new net.resheim.eclipse.timekeeper.service.Commands.CreateTaskListSection(list.id(),"sdk-section","Produce",net.resheim.eclipse.timekeeper.domain.TaskListSectionKind.GROCERY_CATEGORY,0,true,true));
            var data=java.util.Optional.of(new net.resheim.eclipse.timekeeper.domain.ExternalTaskProviderData("icloud.reminders",2,"{\"future\":{\"opaque\":[1,null]}}"));
            list=client.updateTaskListProviderData(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskListProviderData(list.id(),list.version(),data));
            section=client.updateTaskListSectionProviderData(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskListSectionProviderData(section.id(),section.version(),data));
            assertEquals(data,client.taskList(list.id()).orElseThrow().providerData());
            assertEquals(data,client.taskListSection(section.id()).orElseThrow().providerData());
            list=client.updateTaskList(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskList(list.id(),list.version(),"Renamed",list.kind(),true,true,false,true,true,0));
            section=client.updateTaskListSection(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskListSection(section.id(),section.version(),"Renamed",section.kind(),1,true,true));
            assertEquals(data,list.providerData());assertEquals(data,section.providerData());
            list=client.updateTaskListProviderData(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskListProviderData(list.id(),list.version(),java.util.Optional.empty()));
            section=client.updateTaskListSectionProviderData(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskListSectionProviderData(section.id(),section.version(),java.util.Optional.empty()));
            assertTrue(list.providerData().isEmpty());assertTrue(section.providerData().isEmpty());
        }
    }
    @Test void listAndSectionEnvelopesAreGuardedPreservedAndProviderScoped() {
        var connection=create("/task-connections",json("""
            {"providerId":"icloud","remoteAccountId":"list-data","name":"iCloud","readable":true,"writable":true,"enabled":true}
            """));
        var listInput=json("""
            {"remoteListId":"groceries","name":"Groceries","kind":"STANDARD","readable":true,"writable":true,"shared":false,"owner":true,"present":true,"position":0}
            """);listInput.set("connectionId",connection.get("id"));var list=create("/task-lists",listInput);
        var sectionInput=json("""
            {"remoteSectionId":"produce","name":"Produce","kind":"GROCERY_CATEGORY","position":0,"present":true,"writable":true}
            """);sectionInput.set("taskListId",list.get("id"));var section=create("/task-list-sections",sectionInput);
        var envelope=json("""
            {"providerData":{"namespace":"icloud.reminders","version":1,"payload":{"listType":"grocery","pinned":true,"future":{"unknown":[1,"retain",null]}}}}
            """);
        for(var entry:Map.of("/task-lists/"+list.path("id").asText(),listInput,"/task-list-sections/"+section.path("id").asText(),sectionInput).entrySet()) {
            String path=entry.getKey();var current=call("a","GET",path,null,null);
            var updated=call("a","PUT",path+"/provider-data",envelope,current.header("ETag"));assertEquals(200,updated.statusCode(),updated.asString());
            assertEquals(envelope.path("providerData"),json(updated.asString()).path("providerData"));
            assertEquals(404,call("b","PUT",path+"/provider-data",envelope,updated.header("ETag")).statusCode());
            assertEquals(403,call("read","PUT",path+"/provider-data",envelope,updated.header("ETag")).statusCode());
            assertEquals(412,call("a","PUT",path+"/provider-data",envelope,current.header("ETag")).statusCode());
            var wrong=envelope.deepCopy();((ObjectNode)wrong.path("providerData")).put("namespace","microsoft.todo");
            assertEquals(422,call("a","PUT",path+"/provider-data",wrong,updated.header("ETag")).statusCode());
            var secret=envelope.deepCopy();((ObjectNode)secret.path("providerData").path("payload")).put("accessToken","do-not-store");
            assertEquals(422,call("a","PUT",path+"/provider-data",secret,updated.header("ETag")).statusCode());
            var legacy=entry.getValue().deepCopy();legacy.remove("connectionId");legacy.remove("remoteListId");legacy.remove("taskListId");legacy.remove("remoteSectionId");legacy.put("name","Renamed");
            var renamed=call("a","PUT",path,legacy,updated.header("ETag"));assertEquals(200,renamed.statusCode(),renamed.asString());
            assertEquals(envelope.path("providerData"),json(renamed.asString()).path("providerData"));
            var cleared=call("a","PUT",path+"/provider-data",json("{\"providerData\":null}"),renamed.header("ETag"));assertEquals(200,cleared.statusCode());
            assertTrue(json(cleared.asString()).path("providerData").isNull());
        }
    }
}
