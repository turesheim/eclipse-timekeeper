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

@QuarkusTest @TestProfile(JournalAssetHttpTest.Profile.class)
class JournalAssetHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:journal_asset_http",
                "timekeeper.auth.public-origin","https://timekeeper.example.test");}
    }
    private RequestSpecification dav(String token){
        return given().header("Authorization","Basic "+Base64.getEncoder().encodeToString(("client:"+token).getBytes(StandardCharsets.UTF_8)));
    }
    private RequestSpecification api(String token){return given().header("Authorization","Bearer "+token);}

    @Test void binaryAssetsRoundTripAcrossWebDavAndBrowserApiWithVersionedDeletes(){
        String root="/dav/journal/assets/",file=root+"photo.png",rest="/api/v1/journal-assets/photo.png";
        byte[] image={(byte)0x89,'P','N','G',13,10,26,10,1,2,3,4};
        assertTrue(given().get(rest).statusCode()>=400);
        assertEquals(401,given().request("PROPFIND",root).statusCode());
        assertEquals(207,dav("test-a").header("Depth","0").request("PROPFIND",root).statusCode());
        assertEquals(405,dav("test-a").delete(root).statusCode());
        assertEquals(405,dav("test-a").request("MKCOL",root).statusCode());
        assertTrue(dav("test-a").header("Depth","1").request("PROPFIND","/dav/journal/").asString().contains("assets/"));
        var created=dav("test-a").header("If-None-Match","*").body(image).put(file);
        assertEquals(201,created.statusCode());String first=created.header("ETag");assertNotNull(first);
        assertEquals("image/png",dav("test-a").get(file).contentType());
        assertArrayEquals(image,dav("test-a").get(file).asByteArray());
        assertArrayEquals(image,api("test-a").get(rest).asByteArray());
        assertEquals("nosniff",api("test-a").get(rest).header("X-Content-Type-Options"));
        assertEquals(first,api("test-a").head(rest).header("ETag"));
        assertEquals(404,dav("test-b").get(file).statusCode());
        assertEquals(404,api("test-b").get(rest).statusCode());
        assertEquals(403,dav("test-read").header("If-Match",first).body(image).put(file).statusCode());
        assertEquals(412,dav("test-a").header("If-None-Match","*").body(image).put(file).statusCode());
        assertEquals(428,dav("test-a").body(new byte[]{1,2}).put(file).statusCode());
        assertEquals(204,dav("test-a").body(image).put(file).statusCode());
        var updated=api("test-a").header("If-Match",first).contentType("application/json")
                .body("{\"data\":\""+Base64.getEncoder().encodeToString(new byte[]{1,2,3})+"\"}").put(rest);
        assertEquals(204,updated.statusCode());String second=updated.header("ETag");assertNotEquals(first,second);
        assertEquals("application/octet-stream",dav("test-a").get(file).contentType());
        assertTrue(dav("test-a").get(file).header("Content-Disposition").startsWith("attachment;"));
        assertEquals(412,dav("test-a").header("If-Match",first).delete(file).statusCode());
        assertEquals(428,dav("test-a").delete(file).statusCode());
        assertEquals(204,dav("test-a").header("If-Match",second).delete(file).statusCode());
        assertEquals(404,api("test-a").get(rest).statusCode());
        assertFalse(dav("test-a").header("Depth","1").request("PROPFIND",root).asString().contains("photo.png"));
        var recreated=dav("test-a").header("If-None-Match","*").body(image).put(file);
        assertEquals(201,recreated.statusCode());assertNotEquals(first,recreated.header("ETag"));
        assertEquals(207,dav("test-a").header("Depth","0").request("PROPFIND",file).statusCode());
    }

    @Test void unsafeNamesAndOversizedPayloadsAreRejected(){
        String root="/dav/journal/assets/";
        assertEquals(404,dav("test-a").get(root+"script.html%3Fx=1").statusCode());
        assertEquals(404,dav("test-a").get(root+"other/file.png").statusCode());
        assertEquals(400,api("test-a").header("If-None-Match","*").contentType("application/json")
                .body("{\"data\":\"not base64!\"}").put("/api/v1/journal-assets/bad.png").statusCode());
        assertEquals(413,dav("test-a").header("If-None-Match","*").body(new byte[JournalAssets.MAX_BYTES+1])
                .put(root+"oversized.bin").statusCode());
    }
}
