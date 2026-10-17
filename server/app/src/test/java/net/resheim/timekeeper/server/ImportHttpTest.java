package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.restassured.response.Response;
import io.quarkus.test.junit.*;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(ImportHttpTest.Profile.class)
class ImportHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:import_http_tests");}
    }
    private Response call(String token,String method,String path,JsonNode body,String key,String match) {
        var request=given();if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.contentType("application/json").body(body.toString());
        if(key!=null) request.header("Idempotency-Key",key);if(match!=null) request.header("If-Match",match);
        return request.request(method,"/api/v1"+path);
    }
    private JsonNode json(Response response,int status) {assertEquals(status,response.statusCode(),response.asString());return parse(response.asString());}
    private ObjectNode source() {
        var input=object().put("sourceKey",hash("source")).put("sourceZone","UTC").put("fileFingerprint",hash("file"))
                .put("label","HTTP export").put("selectedEntries",1).put("selectedNanos","3600000000000");
        input.putArray("mappings").add(object().put("key",hash("client")).put("resourceType","CLIENT").put("action","CREATE").put("name","HTTP client").put("color","#123456"))
                .add(object().put("key",hash("project")).put("resourceType","PROJECT").put("action","CREATE").put("name","HTTP project").put("parentKey",hash("client")));
        return input;
    }
    private ArrayNode rows() {
        var row=object().put("sourceKey",hash("source")).put("start","2025-10-20T08:00:00Z").put("end","2025-10-20T09:00:00Z")
                .put("durationNanos","3600000000000").put("client","Source client").put("project","Source project").put("task","")
                .put("summary","HTTP interval").put("projectKey",hash("project")).put("occurrence",0);row.putArray("tags");row.putArray("labelKeys");return array().add(row);
    }
    @Test void completeHttpImportAndUndoArePrivateConditionalAndDurablyIdempotent() {
        assertEquals(401,call(null,"GET","/imports",null,null,null).statusCode());
        assertEquals(403,call("test-read","POST","/imports/toggl/jobs",source(),UUID.randomUUID().toString(),null).statusCode());
        var preview=object().put("sourceKey",hash("source"));preview.set("mappings",source().get("mappings"));preview.set("rows",rows());
        assertEquals("NEW",json(call("test-a","POST","/imports/toggl/preview",preview,null,null),200).path("rows").get(0).path("status").asText());
        var invalid=source().put("ownerId","b");assertEquals(400,call("test-a","POST","/imports/toggl/jobs",invalid,UUID.randomUUID().toString(),null).statusCode());
        String key=UUID.randomUUID().toString();var created=call("test-a","POST","/imports/toggl/jobs",source(),key,null);var job=json(created,201);
        assertEquals(job,json(call("test-a","POST","/imports/toggl/jobs",source(),key,null),201));
        String path="/imports/jobs/"+job.path("id").asText();
        assertEquals(404,call("test-b","GET",path,null,null,null).statusCode());
        assertEquals(404,call("test-b","GET",path+"/items",null,null,null).statusCode());
        assertEquals(404,call("test-b","POST",path+"/preview",object().set("rows",rows()),null,null).statusCode());
        var reviewed=json(call("test-a","POST",path+"/preview",object().set("rows",rows()),null,null),200);
        var batch=object().put("reviewToken",reviewed.path("reviewToken").asText()).put("finished",true);batch.set("rows",rows());
        var receipt=json(call("test-a","POST",path+"/batches/0",batch,null,null),200);assertEquals("IMPORTED",receipt.path("state").asText());
        assertEquals(receipt,json(call("test-a","POST",path+"/batches/0",batch,null,null),200));
        var changed=batch.deepCopy().put("finished",false);assertEquals(409,call("test-a","POST",path+"/batches/0",changed,null,null).statusCode());
        var current=call("test-a","GET",path,null,null,null);assertEquals("IMPORTED",json(current,200).path("state").asText());
        assertEquals(3,json(call("test-a","GET",path+"/items",null,null,null),200).path("items").size());
        assertEquals(1,json(call("test-a","GET","/imports?limit=1",null,null,null),200).path("items").size());
        assertEquals(0,json(call("test-b","GET","/imports",null,null,null),200).path("items").size());
        assertEquals(404,call("test-b","GET","/imports?after="+job.path("id").asText(),null,null,null).statusCode());
        assertEquals(428,call("test-a","POST",path+"/undo",null,UUID.randomUUID().toString(),null).statusCode());
        String undoKey=UUID.randomUUID().toString();var undo=json(call("test-a","POST",path+"/undo",null,undoKey,current.header("ETag")),200);
        assertEquals("UNDOING",undo.path("state").asText());assertEquals(undo,json(call("test-a","POST",path+"/undo",null,undoKey,current.header("ETag")),200));
        assertEquals(409,call("test-a","POST",path+"/batches/1",batch,null,null).statusCode());
        for(int step=0;step<10;step++) {
            var plan=json(call("test-a","GET",path+"/undo/preview",null,null,null),200);
            var input=object().put("reviewToken",plan.path("reviewToken").asText());input.set("choices",object());
            var result=json(call("test-a","POST",path+"/undo/batches/"+step,input,null,null),200);
            assertEquals(result,json(call("test-a","POST",path+"/undo/batches/"+step,input,null,null),200));
            if(result.path("state").asText().equals("UNDONE")) break;
        }
        assertEquals("UNDONE",json(call("test-a","GET",path,null,null,null),200).path("state").asText());
        assertEquals(0,json(call("test-a","GET","/projects",null,null,null),200).path("items").size());
        assertEquals(0,json(call("test-a","GET","/clients",null,null,null),200).path("items").size());
        var exported=json(call("test-a","GET","/data/export",null,null,null),200);
        assertFalse(exported.toString().contains("IMPORT_JOB"));assertFalse(exported.toString().contains("fileFingerprint"));
        assertEquals(404,call("test-a","GET","/IMPORT_JOB/"+job.path("id").asText(),null,null,null).statusCode());
    }
}
