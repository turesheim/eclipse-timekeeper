package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.Map;
import java.util.UUID;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(FocusAreasHttpTest.Profile.class)
class FocusAreasHttpTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:focus_areas");}}
    private Response get(String token){return given().header("Authorization","Bearer "+token).get("/api/v1/focus-areas");}
    private Response put(String token,String tag,String body){var request=given().header("Authorization","Bearer "+token).contentType("application/json").body(body);if(tag!=null)request.header("If-Match",tag);return request.put("/api/v1/focus-areas");}
    @Test void privateGuardedSettingsSurviveUnrelatedProfileUpdates(){
        String client=given().header("Authorization","Bearer test-a").contentType("application/json").body("{\"name\":\"Focus client\",\"color\":\"#112233\"}").post("/api/v1/clients").then().statusCode(201).extract().path("id");
        var initial=get("test-a");assertEquals(200,initial.statusCode());assertEquals(0,initial.jsonPath().getList("areas").size());
        var input=object();input.putArray("areas").add(object().put("id",UUID.randomUUID().toString()).put("name"," Work ").set("clientIds",MAPPER.createArrayNode().add(client)));
        String body=input.toString();assertEquals(428,put("test-a",null,body).statusCode());
        var saved=put("test-a",initial.header("ETag"),body);assertEquals(200,saved.statusCode());assertEquals("Work",saved.jsonPath().getString("areas[0].name"));
        assertEquals("no-store",saved.header("Cache-Control"));assertNotNull(saved.header("X-Timekeeper-Owner"));
        assertEquals(403,put("test-read",saved.header("ETag"),body).statusCode());
        assertEquals(412,put("test-a",initial.header("ETag"),"{\"areas\":[]}").statusCode());
        assertEquals(0,get("test-b").jsonPath().getList("areas").size());assertEquals(404,put("test-b",get("test-b").header("ETag"),body).statusCode());
        var profile=given().header("Authorization","Bearer test-a").get("/api/v1/me");
        given().header("Authorization","Bearer test-a").header("If-Match",profile.header("ETag")).contentType("application/json").body("{\"zoneId\":\"Europe/Oslo\",\"holidayCountry\":\"NO\"}").put("/api/v1/me").then().statusCode(200);
        assertEquals(saved.header("ETag"),get("test-a").header("ETag"));
        assertEquals(200,put("test-a",saved.header("ETag"),"{\"areas\":[]}").statusCode());
    }
    @Test void rejectsDuplicateIdentitiesNamesAndUnknownFields(){
        String id=UUID.randomUUID().toString(),area="{\"id\":\""+id+"\",\"name\":\"Same\",\"clientIds\":[]}";
        String tag=get("test-a").header("ETag");
        assertEquals(422,put("test-a",tag,"{\"areas\":["+area+","+area+"]}").statusCode());
        assertEquals(400,put("test-a",tag,"{\"areas\":[],\"ownerId\":\"other\"}").statusCode());
        assertEquals(422,put("test-a",tag,"{\"areas\":["+area.replace("Same"," ")+"]}").statusCode());
    }
}
