package net.resheim.timekeeper.server;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/** Disposable protocol fixture for testing the shipped helper without test libraries. */
public final class InstanceRestorePackagedFixture {
    public static void main(String[] args) throws Exception {
        var fixture=new InstanceRestoreRecoveryTest();fixture.directory=Path.of(args[0]);
        var target=fixture.fixture("jdbc:h2:"+fixture.directory.resolve("target"),"sa","synthetic packaged role password");
        Path secret=fixture.directory.resolve("database-secret");Files.writeString(secret,"synthetic packaged role password\n");Files.setPosixFilePermissions(secret,PosixFilePermissions.fromString("rw-------"));
        var settings=new Properties();try(var input=Files.newInputStream(target.settings())){settings.load(input);}settings.remove("quarkus.datasource.password");try(var output=Files.newOutputStream(target.settings())){settings.store(output,"Disposable file-backed destination");}
    }
}
