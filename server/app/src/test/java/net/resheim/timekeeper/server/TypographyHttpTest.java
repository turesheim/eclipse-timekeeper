package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(TypographyHttpTest.Profile.class)
class TypographyHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:typography_http_tests");}
    }
    private Response profile(String token) {return given().header("Authorization","Bearer "+token).get("/api/v1/me");}
    private Response save(String token,String etag,String css) {
        return edit(token,etag,object().put("zoneId","Europe/Oslo").put("typographyCss",css).toString());
    }
    private Response edit(String token,String etag,String body) {
        return given().header("Authorization","Bearer "+token).header("If-Match",etag).contentType("application/json").body(body).put("/api/v1/me");
    }
    @BeforeEach void reset() {
        for(String token:new String[]{"test-a","test-b"}) {
            var current=profile(token);assertEquals(200,current.statusCode());
            assertEquals(200,edit(token,current.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"locale\":null,\"holidayCountry\":null,\"typographyCss\":null}").statusCode());
        }
    }
    @Test void fontPreferencesAreOwnedAndPersistAcrossAuthenticatedReads() {
        String css="/* Account's own sizes. */ :root { --font-base: 18px; --font-hint: 1rem; }";
        var changed=save("test-a",profile("test-a").header("ETag"),css);assertEquals(200,changed.statusCode());
        assertEquals(css,changed.jsonPath().getString("typographyCss"));
        assertEquals(css,profile("test-a").jsonPath().getString("typographyCss"));
        assertNull(profile("test-b").jsonPath().get("typographyCss"));
        assertEquals(changed.header("ETag"),profile("test-a").header("ETag"));
    }
    @Test void typographyAndOtherProfilePreferencesPreserveEachOtherIncludingResets() {
        String css=":root { --font-base: 112.5%; }";
        var initial=profile("test-a");
        var changed=edit("test-a",initial.header("ETag"),object().put("zoneId","Europe/Oslo").put("locale","nb-NO").put("holidayCountry","NO").put("typographyCss",css).toString());
        assertEquals(200,changed.statusCode());
        var timezone=edit("test-a",changed.header("ETag"),"{\"zoneId\":\"UTC\"}");assertEquals(200,timezone.statusCode());
        assertEquals(css,timezone.jsonPath().getString("typographyCss"));assertEquals("nb-NO",timezone.jsonPath().getString("locale"));assertEquals("NO",timezone.jsonPath().getString("holidayCountry"));
        var holidays=edit("test-a",timezone.header("ETag"),"{\"zoneId\":\"UTC\",\"holidayCountry\":null}");assertEquals(200,holidays.statusCode());
        assertEquals(css,holidays.jsonPath().getString("typographyCss"));
        var restored=edit("test-a",holidays.header("ETag"),"{\"zoneId\":\"UTC\",\"holidayCountry\":\"US\"}");assertEquals(200,restored.statusCode());
        var reset=edit("test-a",restored.header("ETag"),"{\"zoneId\":\"UTC\",\"typographyCss\":null}");assertEquals(200,reset.statusCode());
        assertNull(reset.jsonPath().get("typographyCss"));assertEquals("US",reset.jsonPath().getString("holidayCountry"));assertEquals("nb-NO",reset.jsonPath().getString("locale"));assertEquals("UTC",reset.jsonPath().getString("zoneId"));
    }
    @Test void staleReadOnlyAndInvalidWritesCannotReplaceTheSavedPreference() {
        var initial=profile("test-a");String css=":root { --font-base: 18px; }";
        var changed=save("test-a",initial.header("ETag"),css);assertEquals(200,changed.statusCode());
        assertEquals(412,save("test-a",initial.header("ETag"),null).statusCode());
        assertEquals(403,save("test-read",changed.header("ETag"),null).statusCode());
        assertEquals(422,save("test-a",changed.header("ETag"),"x".repeat(65537)).statusCode());
        for(String invalid:new String[]{"123","[]","{}"})assertEquals(422,edit("test-a",changed.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"typographyCss\":"+invalid+"}").statusCode());
        assertEquals(css,profile("test-a").jsonPath().getString("typographyCss"));assertEquals(changed.header("ETag"),profile("test-a").header("ETag"));
    }
}
