package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;

@QuarkusTest @TestProfile(JournalDavHttpTest.Profile.class)
class JournalDavHttpTest {
    static final String ROOT="/dav/journal/";
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:journal_dav_http",
                "timekeeper.auth.public-origin","https://timekeeper.example.test");}
    }
    private RequestSpecification client(String token){
        String basic=Base64.getEncoder().encodeToString(("any-user:"+token).getBytes(StandardCharsets.UTF_8));
        return given().header("Authorization","Basic "+basic);
    }
    @Test void legacyCollectionAliasSharesAuthenticationDocumentsAndConditionalVersions(){
        String alias="/remote.php/webdav/",filename="2026-08-14.md",source="---\r\ncustom: retained\r\n---\r\n# Journal æ\r\n";
        assertEquals(401,given().request("PROPFIND",alias).statusCode());
        assertEquals(401,given().header("Authorization","Bearer test-a").get(alias+filename).statusCode());
        var created=client("test-a").header("If-None-Match","*").body(source.getBytes(StandardCharsets.UTF_8)).put(alias+filename);
        assertEquals(201,created.statusCode());String first=created.header("ETag");
        assertEquals(alias+filename,java.net.URI.create(created.header("Location")).getPath());
        assertEquals(source,client("test-a").get(ROOT+filename).asString());
        assertEquals(first,client("test-a").head(ROOT+filename).header("ETag"));
        var listing=client("test-a").header("Depth","1").request("PROPFIND",alias);
        assertEquals(207,listing.statusCode());assertTrue(listing.asString().contains(alias+filename));
        assertTrue(listing.asString().contains(alias+"assets/"));assertFalse(listing.asString().contains(ROOT));
        assertEquals(207,client("test-read").header("Depth","0").request("PROPFIND",alias).statusCode());
        assertEquals(403,client("test-read").header("If-Match",first).body("denied").put(alias+filename).statusCode());
        assertEquals(404,client("test-b").get(alias+filename).statusCode());
        assertEquals(428,client("test-a").body("unguarded").put(alias+filename).statusCode());
        assertEquals(428,client("test-a").delete(alias+filename).statusCode());
        var changed=client("test-a").header("If-Match",first).body((source+"changed\n").getBytes(StandardCharsets.UTF_8)).put(ROOT+filename);
        assertEquals(204,changed.statusCode());
        assertEquals(412,client("test-a").header("If-Match",first).delete(alias+filename).statusCode());
        assertEquals(204,client("test-a").header("If-Match",changed.header("ETag")).delete(alias+filename).statusCode());
        assertEquals(404,client("test-a").get(ROOT+filename).statusCode());
        assertEquals(405,client("test-a").delete(alias).statusCode());
        String asset="assets/alias-proof.txt";
        var uploaded=client("test-a").header("If-None-Match","*").body("attachment æ".getBytes(StandardCharsets.UTF_8)).put(alias+asset);
        assertEquals(201,uploaded.statusCode());String assetTag=uploaded.header("ETag");
        assertArrayEquals("attachment æ".getBytes(StandardCharsets.UTF_8),client("test-a").get(ROOT+asset).asByteArray());
        var assets=client("test-a").header("Depth","1").request("PROPFIND",alias+"assets/");
        assertEquals(207,assets.statusCode());assertTrue(assets.asString().contains(alias+asset));
        assertFalse(assets.asString().contains(ROOT));
        assertEquals(404,client("test-b").get(alias+asset).statusCode());
        assertEquals(428,client("test-a").delete(alias+asset).statusCode());
        assertEquals(204,client("test-a").header("If-Match",assetTag).delete(alias+asset).statusCode());
        assertEquals(404,client("test-a").get(ROOT+asset).statusCode());

    }
    @Test void weeklyDocumentsUseIsoFilenamesAndConditionalWebDavWrites(){
        String file=ROOT+"2026-W44.md",body="# Weekly plan\r\n- [ ] Ship  \r\n";
        assertEquals(404,client("test-a").body("bad").put(ROOT+"2025-W53.md").statusCode());
        var created=client("test-a").header("If-None-Match","*").contentType("text/markdown").body(body).put(file);
        assertEquals(201,created.statusCode());String tag=created.header("ETag");
        assertEquals(body,client("test-a").get(file).asString());
        assertTrue(client("test-a").header("Depth","1").request("PROPFIND",ROOT).asString().contains("2026-W44.md"));
        assertEquals(body,given().header("Authorization","Bearer test-a").get("/api/v1/week-notes/2026-W44").jsonPath().getString("body"));
        assertEquals(428,client("test-a").body("unguarded").put(file).statusCode());
        assertEquals(412,client("test-a").header("If-Match","\"stale\"").body("stale").put(file).statusCode());
        assertEquals(204,client("test-a").header("If-Match",tag).delete(file).statusCode());
        assertEquals(404,client("test-a").get(file).statusCode());
    }
    @Test void accountScopedDocumentsRoundTripWithStrongConditionalVersions(){
        String filename="2026-09-20.md",day="2026-09-20",file=ROOT+filename;
        String source="---\r\ntimekeeper-label: Planning\r\ncustom: first\r\n---\r\n# Body\r\n  spaces  \r\n";
        var unauthenticated=given().request("PROPFIND",ROOT);assertEquals(401,unauthenticated.statusCode());
        assertTrue(unauthenticated.header("WWW-Authenticate").startsWith("Basic "));
        assertEquals(401,given().header("Authorization","Basic invalid").request("PROPFIND",ROOT).statusCode());
        var root=client("test-a").header("Depth","1").request("PROPFIND",ROOT);assertEquals(207,root.statusCode());
        assertFalse(root.asString().contains(filename));
        assertEquals(207,client("test-a").header("Host","timekeeper.example.test").header("Depth","0").request("PROPFIND",ROOT).statusCode());
        assertEquals("1",client("test-a").options(ROOT).header("DAV"));
        assertEquals(405,client("test-a").delete(ROOT).statusCode());
        assertEquals(405,client("test-a").request("MKCOL",ROOT).statusCode());
        assertEquals(201,client("test-a").body(new byte[0]).put(ROOT+"2026-09-19.md").statusCode());
        var created=client("test-a").header("If-None-Match","*").contentType("text/markdown").body(source.getBytes(StandardCharsets.UTF_8)).put(file);
        assertEquals(201,created.statusCode());String firstTag=created.header("ETag");assertNotNull(firstTag);
        assertEquals(204,client("test-a").body(source).put(file).statusCode());
        assertEquals(428,client("test-a").body("unconditional overwrite").put(file).statusCode());
        assertEquals(source,client("test-a").get(file).asString());
        var head=client("test-a").head(file);assertEquals(200,head.statusCode());
        assertEquals(source.getBytes(StandardCharsets.UTF_8).length,Integer.parseInt(head.header("Content-Length")));
        assertEquals(firstTag,head.header("ETag"));
        assertEquals(304,client("test-a").header("If-None-Match",firstTag).get(file).statusCode());
        var listing=client("test-a").header("Depth","1").request("PROPFIND",ROOT);
        assertEquals(207,listing.statusCode());assertTrue(listing.asString().contains(file));assertTrue(listing.asString().contains(firstTag));
        assertDoesNotThrow(()->javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(listing.asString()))));
        assertEquals(207,client("test-a").header("Depth","0").request("PROPFIND",file).statusCode());
        assertEquals(400,client("test-a").header("Depth","2").request("PROPFIND",ROOT).statusCode());
        assertEquals(403,client("test-read").header("If-Match",firstTag).body(source).put(file).statusCode());
        assertEquals(404,client("test-b").get(file).statusCode());
        assertFalse(client("test-b").header("Depth","1").request("PROPFIND",ROOT).asString().contains(filename));
        var rest=client("test-a").get("/api/v1/day-notes/"+day); // Basic credentials are reserved for WebDAV.
        assertEquals(401,rest.statusCode());
        rest=given().header("Authorization","Bearer test-a").get("/api/v1/day-notes/"+day);
        assertEquals("",rest.jsonPath().getString("text"));assertEquals("# Body\r\n  spaces  \r\n",rest.jsonPath().getString("body"));
        assertFalse(rest.asString().contains("custom"));assertFalse(rest.asString().contains("modifiedAt"));
        var calendar=given().header("Authorization","Bearer test-a").header("If-Match",rest.header("ETag"))
                .contentType("application/json").body("{\"text\":\"Updated\"}").put("/api/v1/day-notes/"+day);
        assertEquals(200,calendar.statusCode());
        assertEquals(428,client("test-a").header("If-Match","*").body(source).put(file).statusCode());
        String afterCalendar=client("test-a").get(file).asString();assertTrue(afterCalendar.contains("custom: first"));
        assertTrue(afterCalendar.endsWith("# Body\r\n  spaces  \r\n"));
        String secondTag=client("test-a").head(file).header("ETag");assertEquals(firstTag,secondTag);
        String metadataOnly=afterCalendar.replace("custom: first","custom: second");
        var metadata=client("test-a").header("If-Match",secondTag).contentType("text/markdown").body(metadataOnly).put(file);
        assertEquals(204,metadata.statusCode());assertNotEquals(secondTag,metadata.header("ETag"));
        assertEquals(metadataOnly,client("test-a").get(file).asString());
        assertEquals(412,client("test-a").header("If-Match",firstTag).body(source).put(file).statusCode());
        assertEquals(412,client("test-a").header("If-Match",firstTag).delete(file).statusCode());
        assertEquals(calendar.jsonPath().getLong("version")+1,given().header("Authorization","Bearer test-a").get("/api/v1/day-notes/"+day).jsonPath().getLong("version"));
        assertEquals(412,client("test-a").header("If-None-Match","*").body("overwrite").put(file).statusCode());
        assertEquals(428,client("test-a").delete(file).statusCode());
        assertEquals(204,client("test-a").header("If-Match",metadata.header("ETag")).delete(file).statusCode());
        assertEquals(404,client("test-a").get(file).statusCode());
        assertFalse(client("test-a").header("Depth","1").request("PROPFIND",ROOT).asString().contains(filename));
        var recreated=client("test-a").header("If-None-Match","*").body(new byte[0]).put(file);assertEquals(201,recreated.statusCode());
        assertNotEquals(firstTag,recreated.header("ETag"));assertEquals("",client("test-a").get(file).asString());
        assertTrue(client("test-a").header("Depth","1").request("PROPFIND",ROOT).asString().contains(filename));
        assertEquals(422,client("test-a").header("If-Match",recreated.header("ETag")).body(new byte[]{(byte)0xc3,0x28}).put(file).statusCode());
    }
}
