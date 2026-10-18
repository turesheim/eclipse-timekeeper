package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(ClientHttpTest.Profile.class)
class ClientHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:client_http_tests");}
    }
    @Inject ServerStore store;
    private Response call(String token,String method,String path,JsonNode body,String etag) {
        var request=given().header("Authorization","Bearer "+token);
        if(body!=null) request.contentType("application/json").body(body.toString());
        if(etag!=null) request.header("If-Match",etag);
        return request.request(method,"/api/v1"+path);
    }
    private JsonNode json(Response response,int status) {assertEquals(status,response.statusCode());return JsonValues.parse(response.asString());}
    private JsonNode client(String name,String color) {var input=JsonValues.object();input.put("name",name);input.put("color",color);return input;}
    @Test void clientsOwnManyProjectsAndSessionsAndLegacyEditsPreserveServerMetadata() {
        String prefix=UUID.randomUUID().toString();
        var first=json(call("test-a","POST","/clients",client(prefix+" Alpha","#ffee00"),null),201);
        var second=json(call("test-a","POST","/clients",client(prefix+" Beta","#102030"),null),201);
        var projectInput=JsonValues.object();projectInput.put("name",prefix+" Project");projectInput.put("clientId",first.get("id").asText());
        var project=json(call("test-a","POST","/projects",projectInput,null),201);
        projectInput.put("name",prefix+" Other project");var other=json(call("test-a","POST","/projects",projectInput,null),201);
        assertEquals(first.get("id"),project.get("clientId"));assertEquals(first.get("id"),other.get("clientId"));
        var sessionInput=JsonValues.object();sessionInput.put("clientId",first.get("id").asText());sessionInput.put("title","Planning");sessionInput.put("summary","Description");
        sessionInput.put("start","2025-10-20T06:03:00.123456789Z");sessionInput.put("end","2025-10-20T14:03:00.987654321Z");
        var session=json(call("test-a","POST","/work-sessions",sessionInput,null),201);
        assertEquals(first.get("id"),session.get("clientId"));assertFalse(session.has("title"));assertEquals("Planning\n\nDescription",session.path("summary").asText());
        var current=call("test-a","GET","/projects/"+project.get("id").asText(),null,null);
        var legacy=JsonValues.object();legacy.put("name","Renamed by Eclipse");
        var renamed=json(call("test-a","PUT","/projects/"+project.get("id").asText(),legacy,current.header("ETag")),200);
        assertEquals(first.get("id"),renamed.get("clientId"));
        assertEquals(404,call("test-b","GET","/clients/"+first.get("id").asText(),null,null).statusCode());
        projectInput.put("clientId",UUID.randomUUID().toString());assertEquals(404,call("test-a","POST","/projects",projectInput,null).statusCode());
        var foreign=JsonValues.object();foreign.put("name","Foreign");foreign.put("clientId",first.get("id").asText());
        assertEquals(404,call("test-b","POST","/projects",foreign,null).statusCode());
        current=call("test-a","GET","/clients/"+first.get("id").asText(),null,null);
        assertEquals(409,call("test-a","DELETE","/clients/"+first.get("id").asText(),null,current.header("ETag")).statusCode());
        assertEquals(403,call("test-read","POST","/clients",client("Forbidden","#ffffff"),null).statusCode());
        assertEquals(422,call("test-a","POST","/clients",client("Bad color","red"),null).statusCode());
        assertEquals(422,call("test-a","POST","/clients",client(" ","#ffffff"),null).statusCode());
        var invalid=sessionInput.deepCopy();invalid.putNull("clientId");assertEquals(422,call("test-a","POST","/work-sessions",invalid,null).statusCode());
        invalid=sessionInput.deepCopy();invalid.put("title"," ");assertEquals(422,call("test-a","POST","/work-sessions",invalid,null).statusCode());
        var edited=json(call("test-a","PUT","/clients/"+first.get("id").asText(),client("New name","#ffffff"),current.header("ETag")),200);
        assertEquals(1,edited.get("version").asLong());
        assertEquals(412,call("test-a","PUT","/clients/"+first.get("id").asText(),client("Stale","#000000"),current.header("ETag")).statusCode());
        current=call("test-a","GET","/work-sessions/"+session.get("id").asText(),null,null);
        sessionInput.put("clientId",second.get("id").asText());sessionInput.put("title","New title");
        var changed=json(call("test-a","PUT","/work-sessions/"+session.get("id").asText(),sessionInput,current.header("ETag")),200);
        assertEquals(second.get("id"),changed.get("clientId"));assertFalse(changed.has("title"));assertEquals("New title\n\nDescription",changed.path("summary").asText());
        var report=json(call("test-a","GET","/work-sessions/"+session.get("id").asText()+"/report",null,null),200);
        assertEquals(changed,report.get("session"));
        var list=json(call("test-a","GET","/projects?clientId="+first.get("id").asText(),null,null),200);
        assertEquals(2,list.get("items").size());
        var disposable=json(call("test-a","POST","/clients",client("Disposable","#000000"),null),201);
        current=call("test-a","GET","/clients/"+disposable.get("id").asText(),null,null);
        assertEquals(204,call("test-a","DELETE","/clients/"+disposable.get("id").asText(),null,current.header("ETag")).statusCode());
    }
    @Test void existingDataGetsOneRealClientOnceWithoutChangingTimes() {
        String owner="client-migration-"+UUID.randomUUID();var project=UUID.randomUUID();var session=UUID.randomUUID();
        store.transaction(owner,()->{
            for(var entry:Map.of("PROJECT",project,"WORK_SESSION",session).entrySet()) {
                var node=JsonValues.object();node.put("id",entry.getValue().toString());node.put("ownerId",owner);node.put("version",4);
                if(entry.getKey().equals("PROJECT")) node.put("name","Legacy project");
                else {node.put("summary","Legacy session");node.put("start","2025-10-20T06:03:00.123456789Z");node.put("end","2025-10-20T14:03:00.987654321Z");node.put("state","COMPLETED");}
                store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",owner,entry.getKey(),entry.getValue(),4,node.toString());
            }
            return null;
        });
        // Startup must also upgrade owners no longer configured in the token registry.
        store.initializeConfiguredOwners();
        store.transaction(owner,()->{
            var migrated=store.find("WORK_SESSION",session).orElseThrow();
            assertFalse(migrated.has("title"));assertEquals("Legacy session",migrated.path("summary").asText());assertEquals("2025-10-20T06:03:00.123456789Z",migrated.get("start").asText());
            assertEquals(5,migrated.get("version").asLong());assertEquals(migrated.get("clientId"),store.find("PROJECT",project).orElseThrow().get("clientId"));
            assertTrue(store.find("CLIENT",JsonValues.uuid(migrated.get("clientId"))).isPresent());assertEquals(1,store.list("CLIENT").size());
            long revision=store.revision();store.migrateClientDetails();assertEquals(revision,store.revision());assertEquals(migrated,store.find("WORK_SESSION",session).orElseThrow());return null;
        });
    }
    @Test void titlelessSessionsAllowEmptyMultilineAndMigratedLengthSummaries() {
        for(String summary:List.of("","Planning\n\nExisting notes","T".repeat(1000)+"\n\n"+"N".repeat(2000))) {
            var input=JsonValues.object().put("summary",summary).put("start","2025-10-20T06:00:00Z").put("end","2025-10-20T07:00:00Z");
            var session=json(call("test-a","POST","/work-sessions",input,null),201);
            assertEquals(summary,session.path("summary").asText());assertFalse(session.has("title"));
        }
    }
}
