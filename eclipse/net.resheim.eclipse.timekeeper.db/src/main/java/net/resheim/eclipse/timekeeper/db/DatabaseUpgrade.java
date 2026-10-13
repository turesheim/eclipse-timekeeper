package net.resheim.eclipse.timekeeper.db;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.UUID;
import org.h2.tools.RunScript;

/** The only automatic upgrade is the known version-2 layout, after a verified backup. */
final class DatabaseUpgrade {
    private DatabaseUpgrade() { }
    static void workSessions(Connection connection,String jdbcUrl) throws SQLException {
        if(DatabaseSchema.inspect(connection)!=DatabaseSchema.Kind.UPGRADE_REQUIRED) throw new SQLException("Not a supported version-2 database");
        Path backup;
        try {
            Path directory=backupDirectory(jdbcUrl);Files.createDirectories(directory);
            try {backup=Files.createTempFile(directory,"timekeeper-schema-2-",".sql",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}
            catch(UnsupportedOperationException noPosix) {backup=Files.createTempFile(directory,"timekeeper-schema-2-",".sql");}
            try(var writer=Files.newBufferedWriter(backup,StandardCharsets.UTF_8);var query=connection.createStatement();var rows=query.executeQuery("SCRIPT NOPASSWORDS NOSETTINGS")) {
                while(rows.next()) {writer.write(rows.getString(1));writer.newLine();}
            }
            // Verify the backup before any version stamp or schema is changed.
            try(var verification=DriverManager.getConnection("jdbc:h2:mem:upgrade_backup_"+UUID.randomUUID(),"sa","");var reader=Files.newBufferedReader(backup,StandardCharsets.UTF_8)) {
                RunScript.execute(verification,reader);
                if(DatabaseSchema.inspect(verification)!=DatabaseSchema.Kind.UPGRADE_REQUIRED) throw new IOException("Backup did not restore the known version-2 schema");
            }
        } catch(IOException failure) {throw new SQLException("Could not create and verify an upgrade backup; no schema changes were made",failure);}
        try(var query=connection.createStatement()) {
            if(query.executeUpdate("UPDATE TIMEKEEPER_SCHEMA SET VERSION=3,STATE='MIGRATING',ORIGIN='MIGRATION' WHERE ID=1 AND VERSION=2 AND STATE='READY'")!=1)
                throw new SQLException("The schema version changed during upgrade");
            query.execute("CREATE TABLE WORK_SESSION(ID VARCHAR(36) PRIMARY KEY,VERSION BIGINT NOT NULL,OWNER_ID VARCHAR(400) NOT NULL,START_TIME VARCHAR(30) NOT NULL,END_TIME VARCHAR(30),SUMMARY VARCHAR(2000),STATE VARCHAR(16) NOT NULL)");
            query.execute("CREATE TABLE TIMEKEEPER_WRITE_LOCK(ID INTEGER PRIMARY KEY)");
            query.execute("INSERT INTO TIMEKEEPER_WRITE_LOCK VALUES(1)");
            query.execute("ALTER TABLE ACTIVITY ADD WORK_SESSION_ID VARCHAR(36)");
            query.execute("ALTER TABLE ACTIVITY ADD CONSTRAINT FK_ACTIVITY_WORK_SESSION_ID FOREIGN KEY(WORK_SESSION_ID) REFERENCES WORK_SESSION(ID)");
            if(!DatabaseSchema.hasCurrentTables(connection)) throw new SQLException("The upgraded schema is incomplete");
            DatabaseVersion.migrationValidated(connection);
        } catch(SQLException failure) {throw new SQLException("Work Session upgrade did not complete. Restore the verified backup before retrying: "+backup,failure);}
    }
    private static Path backupDirectory(String jdbcUrl) {
        String configured=System.getProperty("net.resheim.eclipse.timekeeper.db.upgrade-backup-directory");
        if(configured!=null && !configured.isBlank()) return Path.of(configured);
        String location=jdbcUrl.substring("jdbc:h2:".length()).split(";",2)[0];
        if(location.startsWith("file:")) location=location.substring(5);
        if(location.startsWith("mem:") || location.startsWith("tcp:") || location.startsWith("ssl:"))
            return Path.of(System.getProperty("user.home"),".timekeeper","backups");
        if(location.startsWith("~/")) location=Path.of(System.getProperty("user.home"),location.substring(2)).toString();
        return Path.of(location).toAbsolutePath().getParent().resolve(".timekeeper-backups");
    }
}
