package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class HttpProtocolTest {
    @TestHTTPResource URI baseUri;
    @Test void serverInformationRequiresReadPermissionAndRetainsBuildAndDeploymentIdentity() throws Exception {
        given().get("/api/v1/server").then().statusCode(401);
        var response=given().header("Authorization","Bearer test-read").get("/api/v1/server").then()
            .statusCode(200).header("Cache-Control",equalTo("no-store"))
            .header("X-Timekeeper-Owner",equalTo("YQ"))
            .body("deployedAt",equalTo("2026-10-13T08:30:00Z")).extract().response();
        var information=JsonValues.parse(response.asString());
        var build=new java.util.Properties();
        try(var input=getClass().getResourceAsStream("/timekeeper-build.properties")) {build.load(input);}
        assertEquals(build.getProperty("version"),information.get("version").asText());
        if(!build.getProperty("commit").equals("unknown")) assertEquals(build.getProperty("commit"),information.get("commit").asText());
        assertTrue(java.time.Instant.parse(information.get("builtAt").asText()).isBefore(java.time.Instant.now()));
        assertNotEquals(information.get("deployedAt"),information.get("startedAt"));
        assertEquals(information,JsonValues.parse(given().header("Authorization","Bearer test-a").get("/api/v1/server").asString()));
        given().header("Authorization","Bearer test-a").header("X-Timekeeper-Owner","Yg").get("/api/v1/server").then().statusCode(401);
    }
    @Test void ownerBindingIsOptionalAndCannotGrantForeignPermissions() {
        given().header("Authorization","Bearer test-a").get("/api/v1/me").then().statusCode(200).header("X-Timekeeper-Owner",equalTo("YQ"));
        given().header("Authorization","Bearer test-a").header("X-Timekeeper-Owner","YQ").get("/api/v1/projects").then().statusCode(200);
        given().header("Authorization","Bearer test-a").header("X-Timekeeper-Owner","Yg").get("/api/v1/projects").then().statusCode(401).body("code",equalTo("OWNER_CHANGED"));
        given().header("Authorization","Bearer test-read").header("X-Timekeeper-Owner","YQ").contentType("application/json").body("{\"name\":\"Read only\"}")
                .post("/api/v1/projects").then().statusCode(403).body("code",equalTo("FORBIDDEN"));
        for(String malformed:java.util.List.of("%%","YQ=","_w","YR"))
            given().header("Authorization","Bearer test-a").header("X-Timekeeper-Owner",malformed).get("/api/v1/me").then().statusCode(400).body("code",equalTo("BAD_REQUEST"));
        given().header("Authorization","Bearer test-a").header("X-Timekeeper-Owner","YQ","YQ").get("/api/v1/me").then().statusCode(400);
    }
    @Test void malformedJsonAndUnsupportedContentTypeUseProblemResponses() {
        given().header("Authorization","Bearer test-a").contentType("application/json").body("{")
                .post("/api/v1/projects").then().statusCode(400).contentType("application/problem+json").body("code",equalTo("BAD_REQUEST"));
        given().header("Authorization","Bearer test-a").contentType("text/plain").body("Project")
                .post("/api/v1/projects").then().statusCode(415).contentType("application/problem+json").body("code",equalTo("UNSUPPORTED_MEDIA_TYPE"));
    }
    @Test void transferRecordLimitIs413RatherThanAFieldValidationError() {
        var input=JsonValues.object();input.put("format","timekeeper-domain-v1");input.put("zoneId","UTC");
        input.putArray("tasks");input.putArray("labels");input.putArray("activities");var projects=input.putArray("projects");
        for(int i=0;i<10001;i++) {var project=JsonValues.object();project.put("id",java.util.UUID.randomUUID().toString());project.put("name","Bounded transfer");projects.add(project);}
        given().header("Authorization","Bearer test-a").header("Idempotency-Key",java.util.UUID.randomUUID().toString())
                .contentType("application/json").body(input.toString()).post("/api/v1/data/import").then()
                .statusCode(413).contentType("application/problem+json").body("code",equalTo("PAYLOAD_TOO_LARGE"));
    }
    @Test void bodyLimitUsesAProblemResponseWithoutCreatingAnyRecord() throws Exception {
        var request=HttpRequest.newBuilder(baseUri.resolve("/api/v1/projects"))
                .header("Authorization","Bearer test-a").header("Content-Type","application/json")
                .expectContinue(true).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\""+"x".repeat(21*1024*1024)+"\"}")).build();
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(413,response.statusCode());
            assertEquals("application/problem+json",response.headers().firstValue("Content-Type").orElse(""));
            assertEquals("PAYLOAD_TOO_LARGE",JsonValues.parse(response.body()).get("code").asText());
        }
    }
}
