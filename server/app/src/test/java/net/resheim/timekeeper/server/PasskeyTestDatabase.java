package net.resheim.timekeeper.server;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Each direct JDBC test gets its own disposable database when PostgreSQL is configured. */
final class PasskeyTestDatabase implements AfterEachCallback {
    private String database, url;
    private Connection admin() throws SQLException {
        return DriverManager.getConnection(System.getenv("TIMEKEEPER_TEST_PG_URL"),
                System.getenv("TIMEKEEPER_TEST_PG_USER"),System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
    }
    synchronized Connection connection(String h2Url) throws SQLException {
        String configured=System.getenv("TIMEKEEPER_TEST_PG_URL");
        if(configured==null) return DriverManager.getConnection(h2Url);
        if(url==null) {
            database="tk_passkeys_"+UUID.randomUUID().toString().replace("-","");
            try(var c=admin();var s=c.createStatement()) {s.execute("CREATE DATABASE "+database);}
            url=configured.substring(0,configured.lastIndexOf('/')+1)+database;
        }
        return DriverManager.getConnection(url,System.getenv("TIMEKEEPER_TEST_PG_USER"),System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"));
    }
    @Override public void afterEach(ExtensionContext context) throws SQLException {
        if(database!=null) try(var c=admin();var s=c.createStatement()) {s.execute("DROP DATABASE "+database+" WITH (FORCE)");}
        database=null;url=null;
    }
}
