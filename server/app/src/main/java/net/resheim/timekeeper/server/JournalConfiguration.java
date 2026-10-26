package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Operator-owned binding. A malformed or unavailable deployment fails closed. */
@ApplicationScoped
class JournalConfiguration {
    @ConfigProperty(name="timekeeper.journal.configuration-file",defaultValue="NONE") String configurationFile;
    record Binding(String owner,String username,String brokerHash) {
        @Override public String toString(){return "Journal account binding";}
    }
    record Deployment(String hostname,int port,JournalKey hostKey,Path incoming,String epoch,List<Binding> accounts) {
        Optional<Binding> owner(String owner){return accounts.stream().filter(account->account.owner().equals(owner)).findFirst();}
        Optional<Binding> username(String username){return accounts.stream().filter(account->account.username().equals(username)).findFirst();}
        @Override public String toString(){return "Journal deployment";}
    }
    static final class Unavailable extends RuntimeException {
        final String code;
        Unavailable(String code){super(code);this.code=code;}
    }
    Deployment load(){
        if(configurationFile==null||configurationFile.equals("NONE")||configurationFile.isBlank())throw new Unavailable("JOURNAL_ADMIN_SETUP_REQUIRED");
        try{
            Path file=Path.of(configurationFile).toAbsolutePath();privatePath(file,false);
            final byte[] bytes;
            try(var channel=Files.newByteChannel(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);var stream=Channels.newInputStream(channel)){
                bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException();
            }
            var input=MAPPER.readTree(bytes);fields(input,Set.of("format","hostname","port","hostKey","incomingRoot","encryptedStorage","backupKeysSeparated","accounts"));
            if(input.path("format").asInt()!=1||!input.path("encryptedStorage").isBoolean()||!input.path("encryptedStorage").asBoolean()||!input.path("backupKeysSeparated").isBoolean()||!input.path("backupKeysSeparated").asBoolean())throw new IllegalArgumentException();
            String host=text(input,"hostname",253);if(!host.matches("[A-Za-z0-9][A-Za-z0-9.-]*")||host.contains(".."))throw new IllegalArgumentException();
            var port=input.path("port");if(!port.isIntegralNumber()||port.asLong()<1||port.asLong()>65535)throw new IllegalArgumentException();
            var hostKey=JournalKey.parse(text(input,"hostKey",1024));Path incoming=Path.of(text(input,"incomingRoot",4096));
            if(!incoming.isAbsolute()||!incoming.equals(incoming.normalize()))throw new IllegalArgumentException();privatePath(incoming,true);
            if(!input.path("accounts").isArray()||input.path("accounts").size()>1000)throw new IllegalArgumentException();
            var accounts=new ArrayList<Binding>();var owners=new HashSet<String>();var names=new HashSet<String>();var hashes=new HashSet<String>();
            for(var account:input.path("accounts")){
                fields(account,Set.of("ownerId","username","brokerTokenSha256"));String owner=text(account,"ownerId",200),username=text(account,"username",32),digest=text(account,"brokerTokenSha256",64);
                if(owner.isBlank()||!owner.equals(owner.strip())||owner.equals("local")||!username.matches("[a-z_][a-z0-9_-]{0,31}")||!digest.matches("[a-f0-9]{64}")||!owners.add(owner)||!names.add(username)||!hashes.add(digest))throw new IllegalArgumentException();
                accounts.add(new Binding(owner,username,digest));
            }
            return new Deployment(host,port.asInt(),hostKey,incoming,JournalMarkdown.hash(bytes),List.copyOf(accounts));
        }catch(Exception failure){throw new Unavailable("JOURNAL_ADMIN_CONFIGURATION_INVALID");}
    }
    private static String text(JsonNode input,String name,int limit){var value=input.path(name);if(!value.isTextual()||value.asText().length()>limit||value.asText().codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();return value.asText();}
    private static void fields(JsonNode input,Set<String> allowed){if(input==null||!input.isObject())throw new IllegalArgumentException();input.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw new IllegalArgumentException();});}
    static void privatePath(Path path,boolean directory)throws IOException{
        // POSIX is an explicit prerequisite for this optional Unix gateway feature.
        if(Files.isSymbolicLink(path)||(directory?!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)))throw new IOException();
        var permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
        if(permissions.stream().anyMatch(permission->permission.name().startsWith("GROUP_")||permission.name().startsWith("OTHERS_")))throw new IOException();
        if(!permissions.contains(PosixFilePermission.OWNER_READ)||(directory&&(!permissions.contains(PosixFilePermission.OWNER_WRITE)||!permissions.contains(PosixFilePermission.OWNER_EXECUTE))))throw new IOException();
        // Reject aliases through ancestor symlinks as well as the final component.
        if(!path.toRealPath().equals(path.toAbsolutePath().normalize()))throw new IOException();
    }
}
