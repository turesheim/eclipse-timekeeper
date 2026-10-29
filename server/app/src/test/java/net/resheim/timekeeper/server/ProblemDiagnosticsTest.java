package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ProblemDiagnosticsTest {
    @Test void diagnosticsNeverIncludeExceptionMessagesSqlOrPrivateValues() {
        String secret="private-token-and-journal-text";
        var cause=new SQLException("SELECT '"+secret+"'", "42000");
        String value=ProblemMapper.diagnostic("UNAVAILABLE",new ApiFailure(503,"UNAVAILABLE",cause));
        assertTrue(value.contains("code=UNAVAILABLE"));assertTrue(value.contains("category=SQLException"));assertTrue(value.contains("sql_state=42000"));
        assertFalse(value.contains(secret));assertFalse(value.contains("SELECT"));assertFalse(value.contains("'"));
        String invalid=ProblemMapper.diagnostic(secret+"\n",new SQLException(secret,secret));
        assertTrue(invalid.contains("code=unknown"));assertTrue(invalid.contains("sql_state=unknown"));assertFalse(invalid.contains(secret));
    }
}
