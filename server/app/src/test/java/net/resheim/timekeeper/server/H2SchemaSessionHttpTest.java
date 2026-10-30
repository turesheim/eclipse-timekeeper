package net.resheim.timekeeper.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** Retiring idle pool connections must not invalidate persisted enum checks. */
@QuarkusTest @TestProfile(H2SchemaSessionHttpTest.Profile.class)
class H2SchemaSessionHttpTest {
    private static final String USER="schema_user",PASSWORD="schema_session_test_password";
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){
            final java.nio.file.Path directory;
            try{directory=java.nio.file.Files.createTempDirectory("timekeeper-h2-schema-session-");directory.toFile().deleteOnExit();}
            catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
            return Map.of(
                "quarkus.datasource.jdbc.url","jdbc:h2:"+directory.resolve("database"),
                "quarkus.datasource.jdbc.metrics.enabled","true",
                "quarkus.datasource.jdbc.max-size","1",
                "quarkus.datasource.username",USER,"quarkus.datasource.password",PASSWORD,
                "quarkus.datasource.jdbc.background-validation-interval","0",
                "quarkus.datasource.jdbc.idle-removal-interval","0");}
    }
    @Inject AgroalDataSource datasource;
    @Test void retiringBootstrapPoolConnectionsPreservesAccountReadsWritesAndConstraints() throws Exception {
        given().header("Authorization","Bearer test-a").get("/api/v1/me").then().statusCode(200);
        String url=datasource.getConfiguration().connectionPoolConfiguration().connectionFactoryConfiguration().jdbcUrl();
        java.nio.file.Path.of(url.substring("jdbc:h2:".length())+".mv.db").toFile().deleteOnExit();
        try(var keeper=DriverManager.getConnection(url,USER,PASSWORD)){
          for(int retirement=0;retirement<3;retirement++){
            long before=datasource.getMetrics().destroyCount();datasource.flush(AgroalDataSource.FlushMode.ALL);
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(datasource.getMetrics().destroyCount()==before&&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(datasource.getMetrics().destroyCount()>before,"The original pool connections must actually retire");
            try(var sql=keeper.createStatement();var rows=sql.executeQuery("SELECT COUNT(*) FROM tk_account")){assertTrue(rows.next());assertTrue(rows.getLong(1)>0,"Retiring the schema session does not drop the database");}
            given().header("Authorization","Bearer test-a").get("/api/v1/me").then().statusCode(200);
            given().header("Authorization","Bearer test-a").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                    .body(Map.of("name","After pool retirement "+retirement,"color","#176653")).post("/api/v1/clients").then().statusCode(201);
            try(var sql=keeper.createStatement()){
                SQLException invalid=assertThrows(SQLException.class,()->sql.executeUpdate("UPDATE tk_account SET state='INVALID' WHERE owner_id='a'"));
                assertEquals("23513",invalid.getSQLState(),"Enum constraints must still reject invalid values");
            }
            given().header("Authorization","Bearer test-a").get("/api/v1/me").then().statusCode(200);
          }
        }
    }
}
