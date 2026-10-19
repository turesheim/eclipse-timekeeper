package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(LocaleHttpTest.Profile.class)
class LocaleHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:locale_http_tests");}
    }
    private Response profile(String token) {return given().header("Authorization","Bearer "+token).get("/api/v1/me");}
    private Response save(String token,String etag,String body) {
        return given().header("Authorization","Bearer "+token).header("If-Match",etag).contentType("application/json").body(body).put("/api/v1/me");
    }
    @BeforeEach void reset() {
        for(String token:new String[]{"test-a","test-b"}) {
            var current=profile(token);assertEquals(200,current.statusCode());
            assertEquals(200,save(token,current.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"locale\":null,\"holidayCountry\":null}").statusCode());
        }
    }
    @Test void languagePreferenceIsOwnedSharedAcrossConnectionsAndPreservedByTimezoneOnlyEdits() {
        var initial=profile("test-a");assertTrue(initial.jsonPath().get("locale")==null);
        var changed=save("test-a",initial.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"locale\":\"nb-NO\"}");
        assertEquals(200,changed.statusCode());assertEquals("nb-NO",changed.jsonPath().getString("locale"));
        assertEquals("nb-NO",profile("test-a").jsonPath().getString("locale"));assertNull(profile("test-b").jsonPath().get("locale"));
        assertEquals("nb-NO",given().header("Authorization","Bearer test-a").get("/api/v1/auth/session").jsonPath().getString("locale"));
        assertNull(given().header("Authorization","Bearer test-b").get("/api/v1/auth/session").jsonPath().get("locale"));
        var timezone=save("test-a",changed.header("ETag"),"{\"zoneId\":\"UTC\"}");
        assertEquals(200,timezone.statusCode());assertEquals("nb-NO",timezone.jsonPath().getString("locale"));assertEquals("UTC",timezone.jsonPath().getString("zoneId"));
        var automatic=save("test-a",timezone.header("ETag"),"{\"zoneId\":\"UTC\",\"locale\":null,\"holidayCountry\":null}");
        assertEquals(200,automatic.statusCode());assertNull(automatic.jsonPath().get("locale"));
    }
    @Test void staleUnsupportedAndReadOnlyChangesCannotReplaceThePreference() {
        var initial=profile("test-a");var changed=save("test-a",initial.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"locale\":\"en-GB\"}");assertEquals(200,changed.statusCode());
        assertEquals(412,save("test-a",initial.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"locale\":\"nb-NO\"}").statusCode());
        for(String invalid:new String[]{"de-DE","en","xx", "<script>"}) assertEquals(422,save("test-a",changed.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"locale\":\""+invalid+"\"}").statusCode());
        assertEquals(403,save("test-read",changed.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"locale\":\"nb-NO\"}").statusCode());
        assertEquals(changed.header("ETag"),profile("test-a").header("ETag"));assertEquals("en-GB",profile("test-a").jsonPath().getString("locale"));
    }
    @Test void holidayCountryIsOwnedAndIndependentOfLanguageAndTimezone() {
        var initial=profile("test-a");assertNull(initial.jsonPath().get("holidayCountry"));
        var selected=save("test-a",initial.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"locale\":\"en-US\",\"holidayCountry\":\"NO\"}");
        assertEquals(200,selected.statusCode());assertEquals("NO",selected.jsonPath().getString("holidayCountry"));
        assertEquals("NO",profile("test-a").jsonPath().getString("holidayCountry"));assertNull(profile("test-b").jsonPath().get("holidayCountry"));
        var language=save("test-a",selected.header("ETag"),"{\"zoneId\":\"UTC\",\"locale\":\"nb-NO\"}");
        assertEquals(200,language.statusCode());assertEquals("NO",language.jsonPath().getString("holidayCountry"));
        var hidden=save("test-a",language.header("ETag"),"{\"zoneId\":\"UTC\",\"holidayCountry\":\"none\"}");
        assertEquals(200,hidden.statusCode());assertEquals("nb-NO",hidden.jsonPath().getString("locale"));assertEquals("none",hidden.jsonPath().getString("holidayCountry"));
        var automatic=save("test-a",hidden.header("ETag"),"{\"zoneId\":\"UTC\",\"holidayCountry\":null}");
        assertEquals(200,automatic.statusCode());assertNull(automatic.jsonPath().get("holidayCountry"));assertEquals("nb-NO",automatic.jsonPath().getString("locale"));
    }
    @Test void holidayCountryChangesRejectStaleInvalidAndReadOnlyWrites() {
        var initial=profile("test-a");var selected=save("test-a",initial.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"holidayCountry\":\"NO\"}");assertEquals(200,selected.statusCode());
        assertEquals(412,save("test-a",initial.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"holidayCountry\":\"US\"}").statusCode());
        for(String invalid:new String[]{"no","ZZ","nb-NO","<script>"})assertEquals(422,save("test-a",selected.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"holidayCountry\":\""+invalid+"\"}").statusCode());
        assertEquals(403,save("test-read",selected.header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"holidayCountry\":\"US\"}").statusCode());
        assertEquals(selected.header("ETag"),profile("test-a").header("ETag"));assertEquals("NO",profile("test-a").jsonPath().getString("holidayCountry"));
    }

}
