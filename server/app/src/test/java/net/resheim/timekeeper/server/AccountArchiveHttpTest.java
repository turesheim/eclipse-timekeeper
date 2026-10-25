package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(AccountArchiveHttpTest.Profile.class)
class AccountArchiveHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return ArchiveTestDatabase.configuration("http","jdbc:h2:mem:account_archive_http_tests");}
    }
    @Inject ServerStore store;
    private Response call(String token,String method,String path,JsonNode body,String match,String key) {
        var request=given().header("Authorization","Bearer "+token);
        if(body!=null) request.contentType("application/json").body(body.toString());
        if(match!=null) request.header("If-Match",match);if(key!=null) request.header("Idempotency-Key",key);
        return request.request(method,"/api/v1"+path);
    }
    @Test void privateZipDownloadAndReviewedRestoreRespectReadOnlyOwnerAndConditionalBoundaries() throws Exception {
        call("test-a","POST","/projects",object().put("name","Source project"),null,UUID.randomUUID().toString()).then().statusCode(201);
        String date="2026-08-06",journal="Private account journal\r\n  preserve this spacing  \n";
        var empty=call("test-a","GET","/day-notes/"+date,null,null,null);
        call("test-a","PUT","/day-notes/"+date,object().put("body",journal),empty.header("ETag"),null).then().statusCode(200);
        store.transaction("a",()->{store.update("UPDATE tk_account SET administrator=TRUE WHERE owner_id=?",store.owner());return null;});
        byte[] bytes=call("test-read","GET","/data/account/export",null,null,null).then().statusCode(200).header("Cache-Control","no-store").contentType("application/zip").extract().asByteArray();
        JsonNode source=null;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) if(entry.getName().equals("archive.json")) source=parse(new String(zip.readAllBytes(),StandardCharsets.UTF_8));}
        assertNotNull(source);assertEquals("a",source.path("account").path("ownerId").asText());assertEquals(1,source.path("work").path("projects").size());
        assertEquals(journal,source.path("dayNotes").get(0).path("body").asText());
        byte[] foreign=call("test-b","GET","/data/account/export",null,null,null).then().statusCode(200).extract().asByteArray();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(foreign))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().equals("archive.json"))assertFalse(new String(zip.readAllBytes(),StandardCharsets.UTF_8).contains("Private account journal"));}
        given().get("/api/v1/data/account/export").then().statusCode(401);
        call("test-a","GET","/data/account/export?ownerId=b",null,null,null).then().statusCode(400);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var input=object().put("fileName","account.zip").put("mediaType","application/zip").put("bytes",bytes.length).put("sha256",digest);
        call("test-read","POST","/data/account/uploads",input,null,UUID.randomUUID().toString()).then().statusCode(403);
        var injected=input.deepCopy().put("ownerId","a");call("test-b","POST","/data/account/uploads",injected,null,UUID.randomUUID().toString()).then().statusCode(400);
        String key=UUID.randomUUID().toString();var created=call("test-b","POST","/data/account/uploads",input,null,key);created.then().statusCode(201);
        var replay=call("test-b","POST","/data/account/uploads",input,null,key);assertEquals(created.asString(),replay.asString());
        String upload="/data/account/uploads/"+parse(created.asString()).path("id").asText();
        call("test-a","GET",upload,null,null,null).then().statusCode(404);
        var chunk=object().put("data",Base64.getEncoder().encodeToString(bytes));
        call("test-a","PUT",upload+"/chunks/0",chunk,null,null).then().statusCode(404);
        var written=call("test-b","PUT",upload+"/chunks/0",chunk,null,null);written.then().statusCode(200);
        assertEquals(written.asString(),call("test-b","PUT",upload+"/chunks/0",chunk,null,null).asString());
        var options=object().put("restoreTimeZone",false);var preview=call("test-b","POST",upload+"/preview",options,null,null);preview.then().statusCode(200);
        assertTrue(parse(preview.asString()).path("canImport").asBoolean());
        assertEquals(0,parse(call("test-b","GET","/projects",null,null,null).asString()).path("items").size());
        call("test-read","POST",upload+"/import",options,preview.header("ETag"),null).then().statusCode(403);
        call("test-b","POST",upload+"/import",options,null,null).then().statusCode(428);
        var restored=call("test-b","POST",upload+"/import",options,preview.header("ETag"),null);restored.then().statusCode(201);
        assertEquals(restored.asString(),call("test-b","POST",upload+"/import",options,preview.header("ETag"),null).asString());
        var projects=parse(call("test-b","GET","/projects",null,null,null).asString()).path("items");assertEquals(1,projects.size());assertEquals("b",projects.get(0).path("ownerId").asText());
        assertNotEquals(source.path("work").path("projects").get(0).get("id"),projects.get(0).get("id"));
        assertEquals(1,parse(call("test-b","GET","/data/account/imports",null,null,null).asString()).path("items").size());
        assertEquals(0,parse(call("test-a","GET","/data/account/imports",null,null,null).asString()).path("items").size());
        assertEquals(1,parse(call("test-a","GET","/projects",null,null,null).asString()).path("items").size());
        assertEquals(journal,parse(call("test-b","GET","/day-notes/"+date,null,null,null).asString()).path("body").asText());
        // The extracted JSON and its original ZIP are one archive, even though
        // the upload bytes/hash and preference choice differ.
        byte[] extracted=source.toString().getBytes(StandardCharsets.UTF_8);
        var jsonInput=object().put("fileName","archive.json").put("mediaType","application/json").put("bytes",extracted.length).put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(extracted)));
        var second=call("test-b","POST","/data/account/uploads",jsonInput,null,UUID.randomUUID().toString());second.then().statusCode(201);
        String jsonUpload="/data/account/uploads/"+parse(second.asString()).path("id").asText();call("test-b","PUT",jsonUpload+"/chunks/0",object().put("data",Base64.getEncoder().encodeToString(extracted)),null,null).then().statusCode(200);
        var duplicate=call("test-b","POST",jsonUpload+"/preview",object().put("restoreTimeZone",true),null,null);duplicate.then().statusCode(200);
        assertTrue(parse(duplicate.asString()).path("alreadyImported").asBoolean());
        assertEquals(1,parse(call("test-b","GET","/projects",null,null,null).asString()).path("items").size());
    }
}
