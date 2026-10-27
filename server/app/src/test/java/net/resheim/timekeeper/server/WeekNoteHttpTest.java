package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

@QuarkusTest @TestProfile(WeekNoteHttpTest.Profile.class)
class WeekNoteHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:week_note_http");}
    }
    private Response get(String token,String week){return given().header("Authorization","Bearer "+token).get("/api/v1/week-notes/"+week);}
    private Response put(String token,String week,String tag,String body){
        var request=given().header("Authorization","Bearer "+token).contentType("application/json").body(Map.of("body",body));
        if(tag!=null)request.header("If-Match",tag);return request.put("/api/v1/week-notes/"+week);
    }
    @Test void weeklyMarkdownRoundTripsPrivatelyWithGuardedVersionsAndIsoYear(){
        String week="2026-W53",body="# Year end\r\n\r\n- [ ] Øvelse  \r\n";
        var first=get("test-a",week);assertEquals(200,first.statusCode());assertEquals(0,first.jsonPath().getLong("version"));
        assertEquals(428,put("test-a",week,null,body).statusCode());
        var saved=put("test-a",week,first.header("ETag"),body);assertEquals(200,saved.statusCode());
        assertEquals(body,saved.jsonPath().getString("body"));assertTrue(saved.jsonPath().getBoolean("hasBody"));
        assertEquals("private, no-store",saved.header("Cache-Control"));
        var list=given().header("Authorization","Bearer test-a").queryParam("fromWeek","2026-W52").queryParam("toWeek","2027-W01").get("/api/v1/week-notes");
        assertEquals(200,list.statusCode());assertEquals(1,list.jsonPath().getList("items").size());assertFalse(list.asString().contains("Year end"));
        assertEquals(body,get("test-read",week).jsonPath().getString("body"));assertEquals(0,get("test-b",week).jsonPath().getLong("version"));
        var download=given().header("Authorization","Bearer test-a").get("/api/v1/week-notes/"+week+"/markdown");
        assertEquals(200,download.statusCode());assertEquals(body,download.asString());assertTrue(download.header("Content-Disposition").contains("2026-W53.md"));
        assertEquals(412,put("test-a",week,first.header("ETag"),"stale").statusCode());
        assertEquals(403,put("test-read",week,saved.header("ETag"),"forbidden").statusCode());
        assertEquals(204,given().header("Authorization","Bearer test-a").header("If-Match",saved.header("ETag")).delete("/api/v1/week-notes/"+week).statusCode());
        assertTrue(get("test-a",week).jsonPath().getBoolean("deleted"));
        assertEquals(404,given().header("Authorization","Bearer test-a").get("/api/v1/week-notes/"+week+"/markdown").statusCode());
        assertEquals(200,put("test-a",week,get("test-a",week).header("ETag"),body).statusCode());
    }
    @Test void invalidIsoWeeksAndBodiesAreRejected(){
        for(String week:new String[]{"2025-W53","2026-W00","2026-W54","2026-W1"})assertEquals(422,get("test-a",week).statusCode(),week);
        String week="2027-W01";var first=get("test-a",week);
        assertEquals(400,given().header("Authorization","Bearer test-a").header("If-Match",first.header("ETag")).contentType("application/json").body("{}").put("/api/v1/week-notes/"+week).statusCode());
        assertEquals(422,put("test-a",week,first.header("ETag"),"ø".repeat(100001)).statusCode());
    }
}
