package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;
import io.quarkus.test.junit.*;
import static io.restassured.RestAssured.given;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(ProblemDiagnosticsTest.Profile.class)
class ProblemDiagnosticsTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:problem_diagnostics");}
    }
    @jakarta.inject.Inject TokenRegistry registry;
    @Test void diagnosticsNeverIncludeExceptionMessagesSqlOrPrivateValues() {
        String secret="private-token-and-journal-text";
        var cause=new SQLException("SELECT '"+secret+"'", "42000");
        String value=ProblemMapper.diagnostic("UNAVAILABLE",new ApiFailure(503,"UNAVAILABLE",cause));
        assertTrue(value.contains("code=UNAVAILABLE"));assertTrue(value.contains("category=SQLException"));assertTrue(value.contains("sql_state=42000"));
        assertFalse(value.contains(secret));assertFalse(value.contains("SELECT"));assertFalse(value.contains("'"));
        String invalid=ProblemMapper.diagnostic(secret+"\n",new SQLException(secret,secret));
        assertTrue(invalid.contains("code=unknown"));assertTrue(invalid.contains("sql_state=unknown"));assertFalse(invalid.contains(secret));
    }
    @Test void authenticationFailureReportsSafeCategoriesAndRecoversWithoutExposingRegistryContents() throws Exception {
        var actual=(TokenRegistry)io.quarkus.arc.ClientProxy.unwrap(registry);String previous=actual.tokensFile,secret="private-token-and-journal-text";
        var file=Files.createTempFile("invalid-registry-",".json");Files.writeString(file,"{"+secret);
        var messages=new CopyOnWriteArrayList<String>();var thrown=new CopyOnWriteArrayList<Throwable>();
        var capture=new Handler(){public void publish(LogRecord record){messages.add(record instanceof org.jboss.logmanager.ExtLogRecord value?value.getFormattedMessage():record.getMessage());if(record.getThrown()!=null)thrown.add(record.getThrown());}public void flush(){}public void close(){}};
        Logger problem=Logger.getLogger(ProblemMapper.class.getName()),tokens=Logger.getLogger(TokenRegistry.class.getName());problem.addHandler(capture);tokens.addHandler(capture);
        try {
            actual.tokensFile=file.toString();
            var response=given().header("Authorization","Bearer test-a").get("/api/v1/me");
            assertEquals(503,response.statusCode());assertEquals("UNAVAILABLE",response.jsonPath().getString("code"));assertFalse(response.asString().contains(secret));
            assertTrue(messages.stream().anyMatch(message->message.contains("Token registry load failed code=TOKEN_REGISTRY_UNAVAILABLE")&&message.contains("category=JsonParseException")));
            assertTrue(messages.stream().anyMatch(message->message.contains("API failure code=UNAVAILABLE")&&message.contains("source=net.resheim.timekeeper.server.TokenRegistry.authenticate")));
            assertFalse(messages.stream().anyMatch(message->message.contains(secret)||message.contains(file.toString())||message.contains("Bearer")));assertTrue(thrown.isEmpty());
            actual.tokensFile=previous;assertEquals(200,given().header("Authorization","Bearer test-a").get("/api/v1/me").statusCode());
        }finally{actual.tokensFile=previous;problem.removeHandler(capture);tokens.removeHandler(capture);Files.deleteIfExists(file);}
    }
}
