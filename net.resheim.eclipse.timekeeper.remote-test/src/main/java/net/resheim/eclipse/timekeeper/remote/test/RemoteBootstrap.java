package net.resheim.eclipse.timekeeper.remote.test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;

/** Prepares isolated credentials/configuration before any Timekeeper UI startup, then runs the IDE. */
public final class RemoteBootstrap implements IApplication {
    private IApplication delegate;
    private Process server;
    static Path folder;
    static URI serverUrl;
    static String token;
    private static String accountAToken,accountBToken;
    static Properties proof;
    public static Properties receipt() {return proof;}
    public static Path fixtureFolder() {return folder;}
    public static URI endpoint() {return serverUrl;}
    public static String fixtureToken() {return token;}
    public static String accountAToken() {return accountAToken;}
    public static String accountBToken() {return accountBToken;}
    @Override public Object start(IApplicationContext context) throws Exception {
        folder=Path.of(System.getProperty("timekeeper.remote.fixture")).toAbsolutePath();Files.createDirectories(folder);
        requireArgument("-eclipse.keyring",folder.resolve("secure.storage"));
        requireArgument("-eclipse.password",folder.resolve("master.password"));
        Path password=folder.resolve("master.password");
        if(!Files.exists(password)) Files.writeString(password,randomSecret(),StandardCharsets.US_ASCII);
        try {
            var secure=SecurePreferencesFactory.getDefault();if(secure==null) throw new IllegalStateException("Fixture secure storage unavailable");
            var node=secure.node("/net.resheim.eclipse.timekeeper/backend");
            token=node.get("token","");
            if(token.isEmpty()) {token=randomSecret();node.put("token",token,true);}
            accountAToken=node.get("fixture-token-a","");
            if(accountAToken.isEmpty()) {accountAToken=token;node.put("fixture-token-a",accountAToken,true);}
            accountBToken=node.get("fixture-token-b","");
            if(accountBToken.isEmpty()) {accountBToken=randomSecret();node.put("fixture-token-b",accountBToken,true);}
            secure.flush();
            proof=new Properties();Path evidence=folder.resolve("proof.properties");
            if(Files.exists(evidence)) try(var input=Files.newInputStream(evidence)) {proof.load(input);}
            Path distribution=Path.of(System.getProperty("timekeeper.remote.distribution"));
            String checksum=hash(distribution);
            String expected=Files.readString(Path.of(distribution+".sha256")).trim().split("\\s+")[0];
            if(!checksum.equals(expected)) throw new IllegalStateException("Server distribution checksum mismatch");
            if(proof.containsKey("distribution.sha256") && !checksum.equals(proof.getProperty("distribution.sha256")))
                throw new IllegalStateException("Use clean verify after changing the fixture distribution");
            Path extracted=folder.resolve("distribution");
            if(!Files.exists(extracted)) {
                Files.createDirectory(extracted);
                try(var zip=new ZipInputStream(Files.newInputStream(distribution))) {
                    for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                        Path file=extracted.resolve(entry.getName()).normalize();
                        if(!file.startsWith(extracted)) throw new IllegalStateException("Unsafe server ZIP entry");
                        if(entry.isDirectory()) Files.createDirectories(file);
                        else {Files.createDirectories(file.getParent());Files.copy(zip,file);}
                    }
                }
            }
            Path registry=folder.resolve("server-tokens.json");
            Files.writeString(registry,"["+principal("remote-owner-a",accountAToken)+","+principal("remote-owner-b",accountBToken)+"]",StandardCharsets.UTF_8);
            int port;
            if(proof.containsKey("server.port")) port=Integer.parseInt(proof.getProperty("server.port"));
            else try(var socket=new ServerSocket(0)) {port=socket.getLocalPort();}
            serverUrl=URI.create("http://127.0.0.1:"+port);
            Files.createDirectories(folder.resolve("server data"));
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",extracted.resolve("timekeeper-server/quarkus-run.jar").toString());
            builder.environment().put("TIMEKEEPER_SERVER_DB_URL","jdbc:h2:"+folder.resolve("server data/timekeeper"));
            builder.environment().put("TIMEKEEPER_SERVER_TOKENS_FILE",registry.toString());builder.environment().put("QUARKUS_HTTP_PORT",String.valueOf(port));
            builder.redirectErrorStream(true).redirectOutput(folder.resolve("server-"+System.nanoTime()+".log").toFile());
            server=builder.start();
            try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);boolean ready=false;
                while(System.nanoTime()<deadline && server.isAlive()) {
                    try {ready=http.send(HttpRequest.newBuilder(serverUrl.resolve("/q/health/ready")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}
                    catch(java.io.IOException unavailable) { }
                    if(ready) break;Thread.sleep(50);
                }
                if(!ready) throw new IllegalStateException("Packaged server did not become ready; see isolated fixture logs");
            }
            var preferences=InstanceScope.INSTANCE.getNode("net.resheim.eclipse.timekeeper.db");
            preferences.put("backend-mode","remote");preferences.put("backend-url",serverUrl.toString());preferences.flush();
            System.setProperty("net.resheim.eclipse.timekeeper.db.url","jdbc:h2:"+folder.resolve("native-local"));
            // Reflection prevents class linkage from activating the DB plug-in before configuration.
            Class.forName("net.resheim.eclipse.timekeeper.remote.test.NativeDatabaseFixture").getMethod("prepare",Path.class).invoke(null,folder);
            String localHash=hash(folder.resolve("native-local.mv.db"));
            if(proof.containsKey("native.sha256") && !localHash.equals(proof.getProperty("native.sha256"))) throw new IllegalStateException("The local fixture changed between Eclipse launches");
            proof.setProperty("native.sha256",localHash);proof.setProperty("distribution.sha256",checksum);proof.setProperty("server.port",String.valueOf(port));
            String previousPids=proof.getProperty("server.pids","");proof.setProperty("server.pids",previousPids.isEmpty() ? String.valueOf(server.pid()) : previousPids+","+server.pid());writeProof();
            for(var application:Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.core.runtime.applications")) {
                if(application.getDeclaringExtension().getUniqueIdentifier().equals("org.eclipse.ui.ide.workbench")) {
                    delegate=(IApplication)application.getChildren("run")[0].createExecutableExtension("class");
                    return delegate.start(context);
                }
            }
            throw new IllegalStateException("Installed IDE application is missing");
        } finally {stopServer();}
    }
    @Override public void stop() {if(delegate!=null) delegate.stop();stopServer();}
    private void stopServer() {
        if(server==null) return;server.destroy();
        try {if(!server.waitFor(5,TimeUnit.SECONDS)) {server.destroyForcibly();server.waitFor(5,TimeUnit.SECONDS);}}
        catch(InterruptedException interrupted) {server.destroyForcibly();Thread.currentThread().interrupt();}
    }
    private static void requireArgument(String name,Path expected) {
        String[] args=Platform.getCommandLineArgs();
        for(int i=0;i<args.length-1;i++) if(args[i].equals(name) && Path.of(args[i+1]).toAbsolutePath().equals(expected)) return;
        throw new IllegalStateException("Isolated secure storage argument missing: "+name);
    }
    public static void writeProof() throws Exception {try(var output=Files.newOutputStream(folder.resolve("proof.properties"))) {proof.store(output,"Credential-free installed remote acceptance");}}
    static String hash(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)) {byte[] block=new byte[65536];for(int count;(count=input.read(block))!=-1;) digest.update(block,0,count);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(byte[] value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static String principal(String owner,String secret) throws Exception {return principal(owner,secret,true);}
    private static String principal(String owner,String secret,boolean writable) throws Exception {
        return "{\"ownerId\":\""+owner+"\",\"sha256\":\""+hash(secret.getBytes(StandardCharsets.UTF_8))+"\",\"permissions\":[\"read\""+(writable ? ",\"write\"" : "")+"]}";
    }
    /** Changes only the disposable fixture's registry; the live server reloads it per request. */
    public static void setAccountBWritable(boolean writable) throws Exception {
        Path temporary=Files.createTempFile(folder,"server-tokens-",".json");
        try {
            Files.writeString(temporary,"["+principal("remote-owner-a",accountAToken)+","+principal("remote-owner-b",accountBToken,writable)+"]",StandardCharsets.UTF_8);
            Files.move(temporary,folder.resolve("server-tokens.json"),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }
    private static String randomSecret() {byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
