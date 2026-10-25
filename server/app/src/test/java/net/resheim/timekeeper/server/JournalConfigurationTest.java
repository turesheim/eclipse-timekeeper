package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JournalConfigurationTest {
    @TempDir Path temporary;
    private Path root,file;
    private JournalConfiguration configuration;
    private ObjectNode input;
    @BeforeEach void setup()throws Exception{
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"));
        root=temporary.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        Path incoming=Files.createDirectory(root.resolve("incoming"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        input=object().put("format",1).put("hostname","journal.example.invalid").put("port",2222).put("hostKey",JournalKeyTest.publicKey()).put("incomingRoot",incoming.toString()).put("encryptedStorage",true).put("backupKeysSeparated",true);
        input.putArray("accounts").add(object().put("ownerId","owned-account").put("username","journal_owner").put("brokerTokenSha256","a".repeat(64)));
        file=Files.createFile(root.resolve("journal.json"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        configuration=new JournalConfiguration();configuration.configurationFile=file.toString();write();
    }
    private void write()throws Exception{Files.writeString(file,input.toString());}
    private void invalid(){var failure=assertThrows(JournalConfiguration.Unavailable.class,configuration::load);assertEquals("JOURNAL_ADMIN_CONFIGURATION_INVALID",failure.code);assertNull(failure.getCause());}
    @Test void trustedMappingAndActualSshEndpointAreIndependentOfWebUrl()throws Exception{
        var deployment=configuration.load();assertEquals("journal.example.invalid",deployment.hostname());assertEquals(2222,deployment.port());assertEquals("owned-account",deployment.username("journal_owner").orElseThrow().owner());assertTrue(deployment.owner("unrelated").isEmpty());
        String epoch=deployment.epoch();input.put("port",2223);write();assertNotEquals(epoch,configuration.load().epoch());assertFalse(deployment.toString().contains("owned-account"));
    }
    @Test void absentPrerequisitesFailClosedWithoutExposingPrivateConfiguration()throws Exception{
        configuration.configurationFile="NONE";assertEquals("JOURNAL_ADMIN_SETUP_REQUIRED",assertThrows(JournalConfiguration.Unavailable.class,configuration::load).code);
        configuration.configurationFile=file.toString();input.put("encryptedStorage",false);write();invalid();input.put("encryptedStorage",true).put("backupKeysSeparated",false);write();invalid();
        Files.writeString(file,"{private-config-invalid");invalid();
    }
    @Test void permissionsAndAllSymlinkComponentsAreRejected()throws Exception{
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r-----"));invalid();Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        Path alias=root.resolve("alias.json");Files.createSymbolicLink(alias,file);configuration.configurationFile=alias.toString();invalid();configuration.configurationFile=file.toString();
        var incoming=Path.of(input.path("incomingRoot").asText());Files.setPosixFilePermissions(incoming,PosixFilePermissions.fromString("rwxr-x---"));invalid();Files.setPosixFilePermissions(incoming,PosixFilePermissions.fromString("rwx------"));
        Path link=root.resolve("linked-directory");Files.createSymbolicLink(link,incoming);input.put("incomingRoot",link.toString());write();invalid();
    }
    @Test void endpointInjectionAmbiguousBindingsAndUnknownPropertiesFailClosed()throws Exception{
        input.put("hostname","host; private-command");write();invalid();input.put("hostname","valid.example.invalid").put("port",65536);write();invalid();input.put("port",22);
        input.withArray("accounts").add(input.path("accounts").get(0).deepCopy());write();invalid();input.withArray("accounts").remove(1);
        ((ObjectNode)input.path("accounts").get(0)).put("username","-oProxyCommand=private");write();invalid();((ObjectNode)input.path("accounts").get(0)).put("username","valid_user");
        input.put("arbitrary-command","private");write();invalid();
    }
}
