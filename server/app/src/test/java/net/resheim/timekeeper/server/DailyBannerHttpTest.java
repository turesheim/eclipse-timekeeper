package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(DailyBannerHttpTest.Profile.class)
class DailyBannerHttpTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:daily_banner_tests");}}
    private Response read(String token){return given().header("Authorization","Bearer "+token).get("/api/v1/me");}
    private Response edit(String token,String etag,String location){return given().header("Authorization","Bearer "+token).header("If-Match",etag).contentType("application/json").body("{\"zoneId\":\"Europe/Oslo\",\"weatherLocation\":"+location+"}").put("/api/v1/me");}
    @Test void locationIsOptionalPrivateVersionedAndValidatedAndPreviewIsAuthenticated(){
        var before=read("test-a");var saved=edit("test-a",before.header("ETag"),"{\"name\":\"Trondheim\",\"latitude\":63.43,\"longitude\":10.3951}");assertEquals(200,saved.statusCode());assertEquals("Trondheim",read("test-a").jsonPath().getString("weatherLocation.name"));assertNull(read("test-b").jsonPath().get("weatherLocation"));
        assertEquals(412,edit("test-a",before.header("ETag"),"null").statusCode());assertEquals(403,edit("test-read",saved.header("ETag"),"null").statusCode());
        assertEquals(422,edit("test-a",saved.header("ETag"),"{\"latitude\":91,\"longitude\":0}").statusCode());
        var clear=edit("test-a",saved.header("ETag"),"null");assertEquals(200,clear.statusCode());assertNull(clear.jsonPath().get("weatherLocation"));
        String path="/api/v1/daily-banners/2026-11-16/preview";assertEquals(401,given().get(path).statusCode());
        var proposal=given().header("Authorization","Bearer test-b").get(path);assertEquals(200,proposal.statusCode());assertEquals(0,proposal.jsonPath().getList("forecast").size());assertEquals("late-autumn",proposal.jsonPath().getString("season"));assertEquals("private, no-store",proposal.header("Cache-Control"));
        assertEquals(400,given().header("Authorization","Bearer test-b").get("/api/v1/daily-banners/bad-date/preview").statusCode());
    }
}
