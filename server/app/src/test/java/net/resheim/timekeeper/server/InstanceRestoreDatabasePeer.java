package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Disposable crash probe. Invoked only by the database restore tests. */
public final class InstanceRestoreDatabasePeer {
    public static void main(String[] args) throws Exception {
        var input=MAPPER.readTree(Files.readString(Path.of(args[0])));
        String url=System.getenv("TIMEKEEPER_RESTORE_PROBE_URL"),password=System.getenv().getOrDefault("TIMEKEEPER_INSTANCE_TEST_PG_PASSWORD","");
        try(var archive=InstanceBackupArchive.open(Path.of(input.path("archive").asText()),"Disposable full restore archive passphrase".toCharArray(),Path.of(input.path("stage").asText()),10_000_000);
                var c=url.startsWith("jdbc:postgresql:")?DriverManager.getConnection(url,"postgres",password):DriverManager.getConnection(url)) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var lock=InstanceRestoreFence.exclusive(c)) {
                c.setAutoCommit(false);
                InstanceRestoreDatabase.apply(c,lock,archive,new InstanceRestoreDatabase.Deployment("dagsverk","https://destination.example:8443","AUTO",true),"bootstrap",UUID.fromString(input.path("operation").asText()),input.path("version").asLong(),UUID.fromString(input.path("root").asText()),UUID.fromString(input.path("generation").asText()),Instant.parse("2026-10-28T12:00:00Z"));
                if(input.path("commit").asBoolean())c.commit();
                System.out.println("RESTORE_CRASH_BOUNDARY");System.out.flush();Runtime.getRuntime().halt(0);
            }
        }
    }
}
