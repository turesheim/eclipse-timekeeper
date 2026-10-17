package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParser;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import net.resheim.eclipse.timekeeper.client.CsvSnapshot;

@QuarkusTest @TestProfile(WorkSessionProjectTest.Profile.class)
class WorkSessionProjectTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {
            String url=System.getenv("TIMEKEEPER_TEST_PROJECTS_DB_URL");
            if(url==null) return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:work_session_project_tests");
            return Map.of("quarkus.datasource.jdbc.url",url,"quarkus.datasource.db-kind","postgresql",
                    "quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"),"quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
        }
    }
    @Inject ServerStore store;
    @TempDir Path temporary;
    private Response call(String token,String method,String path,JsonNode body,String match) {
        var request=given().header("Authorization","Bearer "+token);
        if(method.equals("POST")) {request.header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json");if(body==null) request.body("{}");}
        if(body!=null) request.contentType("application/json").body(body.toString());
        if(match!=null) request.header("If-Match",match);
        return request.request(method,"/api/v1"+path);
    }
    private JsonNode json(Response response,int status) {assertEquals(status,response.statusCode(),response.asString());return parse(response.asString());}
    private JsonNode client(String token,String name) {return json(call(token,"POST","/clients",object().put("name",name).put("color","#176653"),null),201);}
    private JsonNode project(String token,JsonNode client) {return json(call(token,"POST","/projects",object().put("name","Delivery").set("clientId",client.get("id")),null),201);}
    private ObjectNode input() {return object().put("summary","Independent session notes").put("title","Delivery work").put("start","2025-10-20T06:03:00.123456789Z").put("end","2025-10-20T14:03:00.987654321Z");}
    @AfterEach void clear() throws Exception {
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);actual.stopMaintenance();
        try(var connection=actual.datasource.getConnection()) {
            connection.setAutoCommit(false);
            for(var table:List.of("tk_auth_session","tk_api_token","tk_auth_challenge","tk_account","tk_active","tk_relation","tk_external_reference","tk_receipt","tk_change","tk_resource","tk_owner"))
                try(var statement=connection.createStatement()) {statement.executeUpdate("DELETE FROM "+table+" WHERE "+(table.equals("tk_owner") ? "id" : "owner_id")+" IN ('a','b')");}
            connection.commit();
        }
    }
    @Test void projectDeterminesClientAndSurvivesTimerAndLegacyWritesWithoutCrossOwnerLinks() {
        var client=client("test-a","Owned client");var other=client("test-a","Another client");var project=project("test-a",client);var foreign=project("test-b",client("test-b","Private"));
        var body=object().put("summary","Running").put("title","Today").set("projectId",project.get("id"));
        var started=json(call("test-a","POST","/work-sessions/start",body,null),201);String path="/work-sessions/"+started.path("id").asText();
        assertEquals(client.get("id"),started.get("clientId"));assertEquals(project.get("id"),started.get("projectId"));
        assertEquals(1,json(call("test-a","GET","/work-sessions?projectId="+project.path("id").asText(),null,null),200).path("items").size());
        assertEquals(404,call("test-b","GET",path,null,null).statusCode());
        assertEquals(404,call("test-a","GET","/work-sessions?projectId="+foreign.path("id").asText(),null,null).statusCode());
        var stopped=json(call("test-a","POST",path+"/stop",null,etag(started)),200);assertEquals(project.get("id"),stopped.get("projectId"));
        var legacy=object().put("summary","Changed by interval-only client");legacy.set("start",stopped.get("start"));legacy.set("end",stopped.get("end"));
        var edited=json(call("test-a","PUT",path,legacy,etag(stopped)),200);assertEquals(project.get("id"),edited.get("projectId"));assertEquals(client.get("id"),edited.get("clientId"));
        var invalid=legacy.deepCopy();invalid.set("clientId",other.get("id"));
        assertEquals(422,call("test-a","PUT",path,invalid,etag(edited)).statusCode());
        invalid.remove("clientId");invalid.set("projectId",foreign.get("id"));assertEquals(404,call("test-a","PUT",path,invalid,etag(edited)).statusCode());
        assertEquals(edited,json(call("test-a","GET",path,null,null),200));
        assertEquals(403,call("test-read","PUT",path,legacy,etag(edited)).statusCode());
        assertEquals(409,call("test-a","DELETE","/projects/"+project.path("id").asText(),null,etag(project)).statusCode());
        var move=object().put("name","Delivery moved").set("clientId",other.get("id"));
        assertEquals(409,call("test-a","PUT","/projects/"+project.path("id").asText(),move,etag(project)).statusCode());
        assertEquals(project,json(call("test-a","GET","/projects/"+project.path("id").asText(),null,null),200));
        legacy.putNull("projectId");legacy.set("clientId",other.get("id"));var cleared=json(call("test-a","PUT",path,legacy,etag(edited)),200);
        assertTrue(cleared.path("projectId").isNull());assertEquals(other.get("id"),cleared.get("clientId"));
        assertEquals(412,call("test-a","PUT",path,legacy,etag(edited)).statusCode());
        assertEquals(200,call("test-a","PUT","/projects/"+project.path("id").asText(),move,etag(project)).statusCode());
    }
    @Test void transferRemapsProjectsAndRejectsMissingOrInconsistentReferencesAtomically() throws Exception {
        var client=client("test-a","Export client");var project=project("test-a",client);var body=input();body.set("projectId",project.get("id"));
        var original=json(call("test-a","POST","/work-sessions",body,null),201);
        var exported=json(call("test-a","GET","/data/export",null,null),200);var folder=temporary.resolve("export");
        CsvSnapshot.write(JsonParser.parseString(exported.toString()).getAsJsonObject(),folder);var csv=parse(CsvSnapshot.read(folder).toString());assertEquals(exported,csv);
        json(call("test-b","POST","/data/import",csv,null),201);var target=json(call("test-b","GET","/data/export",null,null),200);var copied=target.path("workSessions").get(0);
        assertNotEquals(original.get("id"),copied.get("id"));assertNotEquals(project.get("id"),copied.get("projectId"));
        var copiedProject=target.path("projects").get(0);assertEquals(copiedProject.get("id"),copied.get("projectId"));assertEquals(copiedProject.get("clientId"),copied.get("clientId"));
        var invalid=((ObjectNode)exported).deepCopy();invalid.putArray("projects");
        assertEquals(422,call("test-b","POST","/data/import",invalid,null).statusCode());assertEquals(target,json(call("test-b","GET","/data/export",null,null),200));
        var another=client("test-a","Different");exported=json(call("test-a","GET","/data/export",null,null),200);invalid=((ObjectNode)exported).deepCopy();((ObjectNode)invalid.path("workSessions").get(0)).set("clientId",another.get("id"));
        assertEquals(422,call("test-b","POST","/data/import",invalid,null).statusCode());assertEquals(target,json(call("test-b","GET","/data/export",null,null),200));
    }
    @Test void oldSessionsRemainUnchangedOnReadAndProjectlessCreationStillUsesTheChosenClient() {
        var client=client("test-a","Direct");var body=input();body.set("clientId",client.get("id"));var original=json(call("test-a","POST","/work-sessions",body,null),201);
        assertFalse(original.has("projectId"));String path="/work-sessions/"+original.path("id").asText();assertEquals(original,json(call("test-a","GET",path,null,null),200));
        store.transaction("a",()->{store.retireSessionLinks();return null;});assertEquals(original,json(call("test-a","GET",path,null,null),200));
        var project=project("test-a",client);body.set("projectId",project.get("id"));var updated=json(call("test-a","PUT",path,body,etag(original)),200);assertEquals(project.get("id"),updated.get("projectId"));
        store.transaction("a",()->{store.retireSessionLinks();return null;});assertEquals(updated,json(call("test-a","GET",path,null,null),200));
        assertEquals(409,call("test-a","DELETE","/projects/"+project.path("id").asText(),null,etag(project)).statusCode());
    }
}
