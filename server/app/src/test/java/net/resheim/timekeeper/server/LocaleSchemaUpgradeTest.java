package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

class LocaleSchemaUpgradeTest {
    private void prior(Connection connection) throws Exception {
        try(var input=getClass().getResourceAsStream("/db/schema-v1.sql")) {RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));}
        ServerSchema.applyAuth(connection);ServerSchema.applyRecovery(connection);ServerSchema.applyPasskeys(connection);ServerSchema.applySettings(connection);
        try(var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE tk_schema SET schema_version=5,state='READY'");
            statement.executeUpdate("INSERT INTO tk_owner VALUES('retained','Europe/Oslo',7,19,'cursor')");
        }
    }
    @Test void upgradingV5RequiresAuthorizationAndPreservesWorkAndBrowserLanguageDefaults() throws Exception {
        String url="jdbc:h2:mem:locale_upgrade_"+UUID.randomUUID();
        try(var connection=DriverManager.getConnection(url);var statement=connection.createStatement()) {
            prior(connection);
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection));
            try(var rows=connection.getMetaData().getTables(null,"PUBLIC","TK_LOCALE",null)) {assertFalse(rows.next());}
            ServerSchema.initializeOrValidate(connection,true);ServerSchema.validate(connection);
            try(var rows=statement.executeQuery("SELECT zone_id,version,revision FROM tk_owner")) {
                assertTrue(rows.next());assertEquals("Europe/Oslo",rows.getString(1));assertEquals(7,rows.getLong(2));assertEquals(19,rows.getLong(3));
            }
            try(var rows=statement.executeQuery("SELECT count(*) FROM tk_locale")) {rows.next();assertEquals(0,rows.getInt(1));}
            statement.executeUpdate("INSERT INTO tk_locale VALUES('retained','nb-NO')");
            try(var reopened=DriverManager.getConnection(url);var query=reopened.createStatement()) {
                ServerSchema.initializeOrValidate(reopened);
                try(var rows=query.executeQuery("SELECT locale FROM tk_locale WHERE owner_id='retained'")) {assertTrue(rows.next());assertEquals("nb-NO",rows.getString(1));}
            }
        }
    }
    @Test void malformedV5DoesNotGetPartiallyUpgraded() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:locale_bad_"+UUID.randomUUID());var statement=connection.createStatement()) {
            prior(connection);statement.execute("ALTER TABLE tk_owner ADD COLUMN unexpected INT");
            assertThrows(SQLException.class,()->ServerSchema.initializeOrValidate(connection,true));
            try(var rows=connection.getMetaData().getTables(null,"PUBLIC","TK_LOCALE",null)) {assertFalse(rows.next());}
            try(var rows=statement.executeQuery("SELECT schema_version,state FROM tk_schema")) {rows.next();assertEquals(5,rows.getInt(1));assertEquals("READY",rows.getString(2));}
        }
    }
}
