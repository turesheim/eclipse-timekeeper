package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.*;
import io.restassured.specification.RequestSpecification;

@QuarkusTest @TestProfile(VaultDavHttpTest.Profile.class)
class VaultDavHttpTest {
    static final String ROOT="/dav/vault/",ALIAS="/remote.php/webdav/vault/";
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_dav_http","timekeeper.auth.public-origin","https://timekeeper.example.test");}}
    private RequestSpecification client(String token){return given().header("Authorization","Basic "+Base64.getEncoder().encodeToString(("vault:"+token).getBytes(StandardCharsets.UTF_8)));}
    private RequestSpecification api(String token){return given().header("Authorization","Bearer "+token);}
    @Test void nestedCollectionIsPrivateGuardedAndSharedAcrossAliases(){
        assertEquals(401,given().request("PROPFIND",ROOT).statusCode());assertEquals(401,given().request("PROPFIND",ALIAS).statusCode());
        assertEquals(201,client("test-a").request("MKCOL",ROOT+"Nested/").statusCode());assertEquals(201,client("test-a").request("MKCOL",ROOT+"Nested/Child/").statusCode());
        String path="Nested/Child/Exact.md",body="---\r\nunknown: [x, 1]\r\n---\r\n[[Note]]  \r\n";
        var created=client("test-a").header("If-None-Match","*").body(body.getBytes(StandardCharsets.UTF_8)).put(ROOT+path);assertEquals(201,created.statusCode());String tag=created.header("ETag");
        assertEquals(body,client("test-a").get(ALIAS+path).asString());assertEquals(tag,client("test-a").head(ALIAS+path).header("ETag"));
        assertEquals(404,client("test-b").get(ROOT+path).statusCode());assertEquals(403,client("test-read").header("If-Match",tag).body("blocked").put(ROOT+path).statusCode());
        assertEquals(428,client("test-a").body("unguarded").put(ROOT+path).statusCode());assertEquals(428,client("test-a").delete(ROOT+path).statusCode());
        var listing=client("test-a").header("Depth","infinity").request("PROPFIND",ALIAS);assertEquals(207,listing.statusCode());assertTrue(listing.asString().contains(ALIAS+path));
        assertDoesNotThrow(()->javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(listing.asString()))));
        var moved=client("test-a").header("If-Match",tag).header("Overwrite","F").header("Destination",ROOT+"Nested/Moved.md").request("MOVE",ALIAS+path);
        // Destinations belong to the requested namespace; aliases do not broaden it.
        assertEquals(400,moved.statusCode());
        moved=client("test-a").header("If-Match",tag).header("Overwrite","F").header("Destination",ALIAS+"Nested/Moved.md").request("MOVE",ALIAS+path);assertEquals(201,moved.statusCode());
        assertEquals(404,client("test-a").get(ROOT+path).statusCode());assertEquals(body,client("test-a").get(ROOT+"Nested/Moved.md").asString());
        assertEquals(204,client("test-a").header("If-Match",moved.header("ETag")).delete(ROOT+"Nested/Moved.md").statusCode());
        var trash=api("test-a").get("/api/v1/notes/entries?trash=true");assertEquals(200,trash.statusCode());String id=trash.jsonPath().getString("items.find { it.path == 'Nested/Moved.md' }.id");String deletedTag=trash.jsonPath().getString("items.find { it.path == 'Nested/Moved.md' }.etag");
        assertEquals(200,api("test-a").header("If-Match",deletedTag).post("/api/v1/notes/entries/"+id+"/restore").statusCode());assertEquals(body,client("test-a").get(ROOT+"Nested/Moved.md").asString());
        assertEquals(404,api("test-b").get("/api/v1/notes/entries/"+id).statusCode());
    }
    @Test void journalEditsShareCanonicalVersionsAndLeaveLabelsIndependent(){
        String filename="2026-10-30.md";
        var create=client("test-a").header("If-None-Match","*").body("# Daily note").put(ROOT+"journal/"+filename);assertEquals(201,create.statusCode());
        assertEquals(create.header("ETag"),client("test-a").head("/dav/journal/"+filename).header("ETag"));
        var label=api("test-a").get("/api/v1/day-labels/2026-10-30");assertEquals(200,label.statusCode());
        assertEquals(200,api("test-a").header("If-Match",label.header("ETag")).contentType("application/json").body("{\"text\":\"Independent\"}").put("/api/v1/day-labels/2026-10-30").statusCode());
        assertEquals(create.header("ETag"),client("test-a").head(ROOT+"journal/"+filename).header("ETag"));
        var edit=client("test-a").header("If-Match",create.header("ETag")).body("# Legacy edit").put("/dav/journal/"+filename);assertEquals(204,edit.statusCode());
        assertEquals("# Legacy edit",client("test-a").get(ROOT+"journal/"+filename).asString());assertEquals(412,client("test-a").header("If-Match",create.header("ETag")).delete(ROOT+"journal/"+filename).statusCode());
        assertEquals(204,client("test-a").header("If-Match",edit.header("ETag")).delete(ROOT+"journal/"+filename).statusCode());assertEquals("Independent",api("test-a").get("/api/v1/day-labels/2026-10-30").jsonPath().getString("text"));
    }
    @Test void configurationAndUnsafePathsAreRejected(){
        assertEquals(403,client("test-a").request("MKCOL",ROOT+".obsidian/").statusCode());
        assertEquals(422,client("test-a").urlEncodingEnabled(false).body("x").put(ROOT+"a%2Fb.md").statusCode());
        assertEquals(422,client("test-a").body("x").put(ROOT+"CON.md").statusCode());
        assertEquals(400,client("test-a").header("Depth","2").request("PROPFIND",ROOT).statusCode());
        assertEquals(403,client("test-read").request("MKCOL",ROOT+"Blocked/").statusCode());
    }
}
