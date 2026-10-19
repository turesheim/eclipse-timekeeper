package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;

/** Exercise the real Quarkus runtime and datasource, not a mock HTTP endpoint. */
@QuarkusTest
class ServerBootstrapTest {
    @Inject AgroalDataSource datasource;
    @Test
    void servesTheCanonicalPublicContract() {
        given().when().get("/api/v1/openapi.json").then()
                .statusCode(200).contentType("application/json")
                .body("openapi", equalTo("3.1.1"))
                .body("info.version", equalTo("1.1.0"))
                .body("servers.url", hasItem("/api/v1"));
    }

    @Test
    void authenticatedIdentityReachesTheBlockingResource() {
        var created=given().header("Authorization","Bearer test-a").contentType("application/json")
                .body("{\"name\":\"Authenticated bootstrap\"}").post("/api/v1/projects");
        created.then().statusCode(201).body("ownerId",equalTo("a"));
        String id=created.jsonPath().getString("id"),etag=created.header("ETag");
        given().header("Authorization","Bearer test-b").get("/api/v1/projects/"+id).then().statusCode(404);
        given().header("Authorization","Bearer test-read").header("If-Match",etag)
                .delete("/api/v1/projects/"+id).then().statusCode(403);
        given().header("Authorization","Bearer test-a").header("If-Match",etag)
                .delete("/api/v1/projects/"+id).then().statusCode(204);
    }

    @Test
    void datasourceIsReadyInTheActualServer() throws Exception {
        try (var connection = datasource.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
        given().when().get("/q/health/ready").then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem("Database connections health check"));
    }
}
