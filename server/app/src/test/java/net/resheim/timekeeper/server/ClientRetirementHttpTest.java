package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.Map;
import java.util.UUID;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(ClientRetirementHttpTest.Profile.class)
class ClientRetirementHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:client_retirement");}
    }
    private Response get(String owner,String path){return given().header("Authorization","Bearer "+owner).get("/api/v1"+path);}
    private Response save(String owner,String id,String etag,com.fasterxml.jackson.databind.JsonNode value){return given().header("Authorization","Bearer "+owner).header("If-Match",etag).contentType("application/json").body(value.toString()).put("/api/v1/clients/"+id);}
    @Test void retirementIsOwnedConditionalReversibleAndPortableWithoutChangingHistory(){
        var input=object().put("name","Historical client").put("color","#176653");
        var created=given().header("Authorization","Bearer test-a").contentType("application/json").body(input.toString()).post("/api/v1/clients");assertEquals(201,created.statusCode());String id=created.jsonPath().getString("id");assertFalse(created.jsonPath().getBoolean("retired"));
        var project=given().header("Authorization","Bearer test-a").contentType("application/json").body(object().put("name","Historical project").put("clientId",id).toString()).post("/api/v1/projects");assertEquals(201,project.statusCode());
        var retired=input.deepCopy().put("retired",true);var saved=save("test-a",id,created.header("ETag"),retired);assertEquals(200,saved.statusCode());assertTrue(saved.jsonPath().getBoolean("retired"));
        assertEquals(412,save("test-a",id,created.header("ETag"),input).statusCode());assertEquals(403,save("test-read",id,saved.header("ETag"),input).statusCode());assertEquals(404,get("test-b","/clients/"+id).statusCode());assertEquals(404,save("test-b",id,saved.header("ETag"),input).statusCode());
        assertEquals(project.asString(),get("test-a","/projects/"+project.jsonPath().getString("id")).asString());
        var renamed=save("test-a",id,saved.header("ETag"),input.deepCopy().put("name","Renamed historical client"));assertEquals(200,renamed.statusCode());assertTrue(renamed.jsonPath().getBoolean("retired"));
        var exported=get("test-a","/data/export");assertEquals(200,exported.statusCode());var data=parse(exported.asString());assertTrue(java.util.stream.StreamSupport.stream(data.path("clients").spliterator(),false).anyMatch(value->value.path("id").asText().equals(id)&&value.path("retired").asBoolean()));
        var imported=given().header("Authorization","Bearer test-b").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").body(data.toString()).post("/api/v1/data/import");assertTrue(imported.statusCode()<300,imported.asString());
        var copies=get("test-b","/clients");var copy=parse(copies.asString()).path("items");assertTrue(java.util.stream.StreamSupport.stream(copy.spliterator(),false).anyMatch(value->value.path("name").asText().equals("Renamed historical client")&&value.path("retired").asBoolean()&&!value.path("id").asText().equals(id)));
        var restored=save("test-a",id,renamed.header("ETag"),input.deepCopy().put("retired",false));assertEquals(200,restored.statusCode());assertFalse(restored.jsonPath().getBoolean("retired"));
    }
}
