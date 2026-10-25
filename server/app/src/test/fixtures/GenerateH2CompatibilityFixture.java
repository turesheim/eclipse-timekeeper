import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.h2.tools.RunScript;

/** Run with H2 2.5.250; never run against a user's database. */
class GenerateH2CompatibilityFixture {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]),resources=Path.of(args[1]);
        if(Files.exists(Path.of(output+".mv.db"))) throw new IllegalArgumentException("Fixture already exists");
        try(var connection=DriverManager.getConnection("jdbc:h2:"+output.toAbsolutePath(),"sa", "")) {
            if(!connection.getMetaData().getDatabaseProductVersion().startsWith("2.5.250 "))
                throw new IllegalStateException("Run with H2 2.5.250");
            for(String name:new String[]{"schema-v1.sql","auth-v2.sql","recovery-v3.sql","passkeys-v4.sql","settings-v5.sql","locale-v6.sql"}) {
                try(var reader=Files.newBufferedReader(resources.resolve("db").resolve(name))) {RunScript.execute(connection,reader);}
            }
            try(var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE tk_schema SET schema_version=6,state='READY' WHERE id=1");
                statement.executeUpdate("INSERT INTO tk_owner VALUES('compatibility-owner','Europe/Oslo',7,11,'synthetic-fixture-secret')");
            }
            try(var statement=connection.prepareStatement("INSERT INTO tk_resource VALUES('compatibility-owner','DAY_NOTE','00000000-0000-0000-0000-000020710204',9,?)")) {
                statement.setString(1,"{\"version\":9,\"text\":\"Existing label\",\"body\":\"\\r\\n# Existing Markdown\\r\\n  keep whitespace  \\t\\r\\n\"}");
                statement.executeUpdate();
            }
        }
    }
}
