package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(DayNoteHttpTest.Profile.class)
class DayNoteHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:day_note_http");}
    }
    private Response get(String token,String date){return given().header("Authorization","Bearer "+token).get("/api/v1/day-notes/"+date);}
    private Response save(String token,String date,String etag,String text){return given().header("Authorization","Bearer "+token).header("If-Match",etag).contentType("application/json").body(Map.of("text",text)).put("/api/v1/day-notes/"+date);}
    private Response list(String token,String first,String last){return given().header("Authorization","Bearer "+token).queryParam("fromDate",first).queryParam("toDate",last).get("/api/v1/day-notes");}
    @Test void labelsAreOwnedCivilDatesAndLeaveWorkAndProfileValuesUntouched(){
        String day="2026-05-17";var before=given().header("Authorization","Bearer test-a").get("/api/v1/me");
        var empty=get("test-a",day);assertEquals(200,empty.statusCode());assertEquals("",empty.jsonPath().getString("text"));assertEquals(0,empty.jsonPath().getLong("version"));
        var saved=save("test-a",day,empty.header("ETag"),"  Avspasering  ");assertEquals(200,saved.statusCode());assertEquals("Avspasering",saved.jsonPath().getString("text"));assertEquals(day,saved.jsonPath().getString("date"));
        assertEquals("Avspasering",get("test-read",day).jsonPath().getString("text"));assertEquals("",get("test-b",day).jsonPath().getString("text"));assertNotEquals(saved.header("ETag"),get("test-b",day).header("ETag"));
        assertEquals(1,list("test-a",day,day).jsonPath().getList("items").size());assertEquals(0,list("test-b",day,day).jsonPath().getList("items").size());
        var after=given().header("Authorization","Bearer test-a").get("/api/v1/me");assertEquals(before.header("ETag"),after.header("ETag"));
        assertEquals(403,save("test-read",day,saved.header("ETag"),"Ferie").statusCode());
        assertEquals(401,given().get("/api/v1/day-notes/"+day).statusCode());
    }
    @Test void staleWritesCannotOverwriteCreateDeleteOrRecreation(){
        String day="2026-06-01";var initial=get("test-a",day);var first=save("test-a",day,initial.header("ETag"),"Ferie");assertEquals(200,first.statusCode());
        assertEquals(412,save("test-a",day,initial.header("ETag"),"Peer").statusCode());
        var removed=save("test-a",day,first.header("ETag"),"");assertEquals(200,removed.statusCode());assertEquals(0,list("test-a",day,day).jsonPath().getList("items").size());
        assertEquals(412,save("test-a",day,initial.header("ETag"),"Old draft").statusCode());
        var recreated=save("test-a",day,removed.header("ETag"),"Avspasering");assertEquals(200,recreated.statusCode());assertEquals(3,recreated.jsonPath().getLong("version"));
        assertEquals(412,save("test-a",day,first.header("ETag"),"Stale").statusCode());assertEquals("Avspasering",get("test-a",day).jsonPath().getString("text"));
    }
    @Test void invalidDatesTextRangesAndInjectedFieldsAreRejected(){
        String day="2026-07-01";var initial=get("test-a",day);String etag=initial.header("ETag");
        for(String invalid:new String[]{"2026-02-30","0000-01-01","2026-1-1","not-a-date"})assertEquals(422,get("test-a",invalid).statusCode());
        for(String invalid:new String[]{"x".repeat(81),"Ferie\nAnnet","Ferie\tAnnet","x\u0000"})assertEquals(422,save("test-a",day,etag,invalid).statusCode());
        assertEquals(400,given().header("Authorization","Bearer test-a").header("If-Match",etag).contentType("application/json").body(Map.of("text","Ferie","ownerId","other")).put("/api/v1/day-notes/"+day).statusCode());
        assertEquals(428,given().header("Authorization","Bearer test-a").contentType("application/json").body(Map.of("text","Ferie")).put("/api/v1/day-notes/"+day).statusCode());
        assertEquals(422,list("test-a","2026-01-01","2026-04-01").statusCode());assertEquals(422,list("test-a","2026-05-02","2026-05-01").statusCode());
        assertEquals(200,save("test-a",day,etag,"ø".repeat(80)).statusCode());
    }
}
