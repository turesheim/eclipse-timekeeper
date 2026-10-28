package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class H2CrashRecoveryTest {
    @TempDir Path directory;

    @Test
    void existingVersion250FileRetainsSchemaAndJournalAcrossWriteAndReopen() throws Exception {
        Path file=directory.resolve("existing.mv.db");
        try(var fixture=getClass().getResourceAsStream("/fixtures/h2-2.5.250-schema6.mv.db")) {
            java.util.Objects.requireNonNull(fixture,"Missing compatibility fixture");
            Files.copy(fixture,file);
        }
        String url="jdbc:h2:"+directory.resolve("existing")+";IFEXISTS=TRUE";
        String payload;
        try(var connection=DriverManager.getConnection(url,"sa", "");var statement=connection.createStatement()) {
            ServerSchema.initializeOrValidate(connection,true);
            try(var row=statement.executeQuery("SELECT version,payload FROM tk_resource WHERE owner_id='compatibility-owner'")) {
                org.junit.jupiter.api.Assertions.assertTrue(row.next());
                assertEquals(9,row.getLong(1));payload=row.getString(2);
                assertEquals("\r\n# Existing Markdown\r\n  keep whitespace  \t\r\n",new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload).get("body").asText());
            }
            payload=payload.replace("\"version\":9","\"version\":10");
            try(var update=connection.prepareStatement("UPDATE tk_resource SET version=10,payload=? WHERE owner_id='compatibility-owner'")) {
                update.setString(1,payload);update.executeUpdate();
            }
        }
        try(var connection=DriverManager.getConnection(url,"sa", "");var statement=connection.createStatement();
            var row=statement.executeQuery("SELECT version,payload FROM tk_resource WHERE owner_id='compatibility-owner'")) {
            org.junit.jupiter.api.Assertions.assertTrue(row.next());
            assertEquals(10,row.getLong(1));assertEquals(payload,row.getString(2));
            ServerSchema.validate(connection);
        }
    }

    @Test
    void acknowledgedWritesSurviveRepeatedUncleanOpenAndChunkReuse() {
        // H2 #4446: a stale file header can discard committed data despite sync().
        var builder=new MVStore.Builder().fileName(directory.resolve("journal.mv.db").toString())
                .autoCommitDisabled();
        var store=builder.open();
        MVMap<Integer,byte[]> notes=store.openMap("journal");
        for(int i=0;i<20;i++) {notes.put(i%10,new byte[1000+i]);store.commit();}
        store.closeImmediately();
        store=builder.open();
        // Force freed chunk reuse without waiting for the default retention period.
        store.setRetentionTime(0);
        notes=store.openMap("journal");
        for(int i=0;i<60;i++) {notes.put(i%10,new byte[1000+i]);store.commit();}
        notes.put(999,new byte[]{42});
        store.commit();store.sync();store.closeImmediately();
        try(var reopened=builder.open()) {
            MVMap<Integer,byte[]> saved=reopened.openMap("journal");
            assertArrayEquals(new byte[]{42},saved.get(999));
            for(int i=0;i<10;i++) assertEquals(1050+i,saved.get(i).length);
        }
    }
}
