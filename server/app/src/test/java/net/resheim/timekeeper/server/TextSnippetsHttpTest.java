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

@QuarkusTest @TestProfile(TextSnippetsHttpTest.Profile.class)
class TextSnippetsHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:text_snippets_tests");}
    }
    private Response profile(String token){return given().header("Authorization","Bearer "+token).get("/api/v1/me");}
    private Response edit(String token,String etag,String body){return given().header("Authorization","Bearer "+token).header("If-Match",etag).contentType("application/json").body(body).put("/api/v1/me");}
    private Response save(String token,String etag,String snippets){return edit(token,etag,"{\"zoneId\":\"Europe/Oslo\",\"textSnippets\":"+snippets+"}");}
    @BeforeEach void reset(){for(String token:new String[]{"test-a","test-b"})assertEquals(200,edit(token,profile(token).header("ETag"),"{\"zoneId\":\"Europe/Oslo\",\"textSnippets\":null,\"holidayCountry\":null,\"typographyCss\":null}").statusCode());}
    @Test void snippetsArePrivatePreservedAndExplicitlyResettable(){
        String snippets="[{\"keyword\":\";hello\",\"content\":\"Hello {cursor}\\n{date +1M:MMMM}\"}]";
        var changed=save("test-a",profile("test-a").header("ETag"),snippets);assertEquals(200,changed.statusCode());
        assertEquals(";hello",profile("test-a").jsonPath().getString("textSnippets[0].keyword"));assertNull(profile("test-b").jsonPath().get("textSnippets"));
        var other=edit("test-a",changed.header("ETag"),"{\"zoneId\":\"UTC\",\"holidayCountry\":\"NO\",\"typographyCss\":\":root { --font-base: 18px; }\"}");assertEquals(200,other.statusCode());assertEquals(";hello",other.jsonPath().getString("textSnippets[0].keyword"));
        var disabled=save("test-a",other.header("ETag"),"[]");assertEquals(200,disabled.statusCode());assertEquals(0,disabled.jsonPath().getList("textSnippets").size());assertEquals("NO",disabled.jsonPath().getString("holidayCountry"));
        var defaults=save("test-a",disabled.header("ETag"),"null");assertEquals(200,defaults.statusCode());assertNull(defaults.jsonPath().get("textSnippets"));assertNotNull(defaults.jsonPath().getString("typographyCss"));
    }
    @Test void staleReadOnlyAndInvalidWritesLeaveTheSavedValueUntouched(){
        var initial=profile("test-a");String value="[{\"keyword\":\";dt\",\"content\":\"{date}\"}]";var changed=save("test-a",initial.header("ETag"),value);assertEquals(200,changed.statusCode());
        assertEquals(412,save("test-a",initial.header("ETag"),"[]").statusCode());assertEquals(403,save("test-read",changed.header("ETag"),"[]").statusCode());
        for(String invalid:new String[]{"{}","123","[{\"keyword\":\"bad\",\"content\":\"x\"}]","[{\"keyword\":\";x\",\"content\":\"x\"},{\"keyword\":\";X\",\"content\":\"y\"}]",array().add(object().put("keyword",";big").put("content","x".repeat(8193))).toString()})assertEquals(422,save("test-a",changed.header("ETag"),invalid).statusCode(),invalid.substring(0,Math.min(70,invalid.length())));
        var large=array();for(int i=0;i<9;i++)large.add(object().put("keyword",";key"+i).put("content","x".repeat(8192)));assertEquals(422,save("test-a",changed.header("ETag"),large.toString()).statusCode());
        assertEquals(";dt",profile("test-a").jsonPath().getString("textSnippets[0].keyword"));assertEquals(changed.header("ETag"),profile("test-a").header("ETag"));
    }
}
