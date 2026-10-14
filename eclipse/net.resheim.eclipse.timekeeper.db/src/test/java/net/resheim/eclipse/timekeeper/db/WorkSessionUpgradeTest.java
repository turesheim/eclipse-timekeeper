package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkSessionUpgradeTest {
    @TempDir Path directory;
    private String url(String name) {return "jdbc:h2:"+directory.resolve(name);}
    private void previous(String name) throws Exception {
        try(var connection=DriverManager.getConnection(url(name),"sa","");var stream=getClass().getResourceAsStream("fixtures/version-2-native-model.sql")) {
            assertNotNull(stream);RunScript.execute(connection,new InputStreamReader(stream,StandardCharsets.UTF_8));
            assertEquals(DatabaseSchema.Kind.UPGRADE_REQUIRED,DatabaseSchema.inspect(connection));
        }
    }
    private Map<String,List<String>> rows(String name) throws Exception {
        var result=new TreeMap<String,List<String>>();
        try(var connection=DriverManager.getConnection(url(name)+";IFEXISTS=TRUE","sa","")) {
            for(var entry:DatabaseSchema.VERSION_2.entrySet()) {
                var values=new ArrayList<String>();
                String columns=entry.getValue().stream().map(value->'"'+value+'"').collect(java.util.stream.Collectors.joining(","));
                try(var query=connection.createStatement();var records=query.executeQuery("SELECT "+columns+" FROM "+entry.getKey())) {
                    while(records.next()) {
                        var row=new ArrayList<String>();for(int index=1;index<=entry.getValue().size();index++) row.add(records.getString(index));values.add(row.toString());
                    }
                }
                Collections.sort(values);result.put(entry.getKey(),values);
            }
        }
        return result;
    }
    @Test void knownVersion2UpgradePreservesEveryExistingRowAndLeavesAVerifiedRestorableBackup() throws Exception {
        previous("previous");var expected=rows("previous");
        var manager=DatabaseStartup.open(url("previous"));
        try {CurrentModelFixture.verify(manager);assertEquals(0L,manager.createQuery("SELECT COUNT(s) FROM WorkSession s",Long.class).getSingleResult());}
        finally {DatabaseStartup.close(manager);}
        assertEquals(expected,rows("previous"));
        try(var connection=DriverManager.getConnection(url("previous"),"sa","")) {
            assertEquals(new DatabaseVersion.Stamp(3,"READY","MIGRATION"),DatabaseVersion.read(connection));
            assertEquals(DatabaseSchema.Kind.CURRENT,DatabaseSchema.inspect(connection));
        }
        Path backup;
        try(var files=Files.list(directory.resolve(".timekeeper-backups"))) {var all=files.toList();assertEquals(1,all.size());backup=all.getFirst();}
        if(Files.getFileStore(backup).supportsFileAttributeView("posix"))
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(backup));
        try(var connection=DriverManager.getConnection(url("restored"),"sa","");var reader=Files.newBufferedReader(backup,StandardCharsets.UTF_8)) {
            RunScript.execute(connection,reader);assertEquals(DatabaseSchema.Kind.UPGRADE_REQUIRED,DatabaseSchema.inspect(connection));
            assertEquals(new DatabaseVersion.Stamp(2,"READY","NEW"),DatabaseVersion.read(connection));
        }
        assertEquals(expected,rows("restored"));
        DatabaseStartup.close(DatabaseStartup.open(url("previous")));
        try(var files=Files.list(directory.resolve(".timekeeper-backups"))) {assertEquals(1,files.count(),"A normal restart repeated the upgrade");}
    }
    @Test void aBackupFailureOrUnknownPreviousShapeCannotModifyTheSource() throws Exception {
        previous("blocked");var expected=rows("blocked");Path obstacle=directory.resolve("not-a-directory");Files.writeString(obstacle,"keep");
        String key="net.resheim.eclipse.timekeeper.db.upgrade-backup-directory",old=System.getProperty(key);
        try {
            System.setProperty(key,obstacle.resolve("backup").toString());
            assertThrows(SQLException.class,()->DatabaseStartup.open(url("blocked")));
        } finally {if(old==null) System.clearProperty(key);else System.setProperty(key,old);}
        assertEquals(expected,rows("blocked"));
        try(var connection=DriverManager.getConnection(url("blocked"),"sa","");var query=connection.createStatement()) {
            assertEquals(new DatabaseVersion.Stamp(2,"READY","NEW"),DatabaseVersion.read(connection));query.execute("ALTER TABLE ACTIVITY ADD UNKNOWN_FIELD VARCHAR");
        }
        assertThrows(SQLException.class,()->DatabaseStartup.open(url("blocked")));assertEquals(expected,rows("blocked"));
        assertFalse(Files.exists(directory.resolve(".timekeeper-backups")));
    }
}
