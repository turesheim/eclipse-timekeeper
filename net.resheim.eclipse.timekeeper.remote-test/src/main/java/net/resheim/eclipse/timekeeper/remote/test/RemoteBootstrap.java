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
    private Process server,alternate,replyGate;
    private int upstreamPort;
    private static RemoteBootstrap active;
    static Path folder;
    static URI serverUrl;
    private static URI alternateUrl,selectedUrl;
    static String token;
    private static String accountAToken,accountBToken,observerBToken;
    static Properties proof;
    public static Properties receipt() {return proof;}
    public static Path fixtureFolder() {return folder;}
    public static URI endpoint() {return serverUrl;}
    public static URI alternateEndpoint() {return alternateUrl;}
    public static URI selectedEndpoint() {return selectedUrl;}
    public static String fixtureToken() {return token;}
    public static String accountAToken() {return accountAToken;}
    public static String accountBToken() {return accountBToken;}
    public static String observerBToken() {return observerBToken;}
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
            observerBToken=node.get("fixture-observer-b","");
            if(observerBToken.isEmpty()) {observerBToken=randomSecret();node.put("fixture-observer-b",observerBToken,true);}
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
            Files.writeString(registry,"["+principal("remote-owner-a",accountAToken)+","+principal("remote-owner-b",accountBToken)+","+principal("remote-owner-b",observerBToken)+"]",StandardCharsets.UTF_8);
            int port;
            if(proof.containsKey("server.port")) port=Integer.parseInt(proof.getProperty("server.port"));
            else try(var socket=new ServerSocket(0)) {port=socket.getLocalPort();}
            serverUrl=URI.create("http://127.0.0.1:"+port);
            active=this;launchServer();
            if(Boolean.getBoolean("timekeeper.remote.switch-tests")) {
                int alternatePort;
                if(proof.containsKey("server.alternate-port")) alternatePort=Integer.parseInt(proof.getProperty("server.alternate-port"));
                else try(var socket=new ServerSocket(0)) {alternatePort=socket.getLocalPort();}
                alternateUrl=URI.create("http://127.0.0.1:"+alternatePort);
                alternate=launch("alternate",alternatePort,folder.resolve("alternate server data/timekeeper"));
                proof.setProperty("server.alternate-port",String.valueOf(alternatePort));
                String pids=proof.getProperty("server.alternate-pids","");
                proof.setProperty("server.alternate-pids",pids.isEmpty() ? String.valueOf(alternate.pid()) : pids+","+alternate.pid());
            }
            if("passed".equals(proof.getProperty("phase.mylyn")) && !proof.containsKey("mylyn.between-launch-version")) {
                try(var api=new net.resheim.eclipse.timekeeper.client.HttpTimekeeperService(serverUrl,()->accountAToken,Duration.ofSeconds(5))) {
                    var id=new net.resheim.eclipse.timekeeper.domain.TaskId(java.util.UUID.fromString(proof.getProperty("mylyn.second")));
                    var original=api.task(id).orElseThrow();
                    var edited=api.updateTask(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTask(id,original.version(),original.projectId(),original.parentTaskId(),"Server edit between Eclipse launches",original.url()));
                    proof.setProperty("mylyn.between-launch-version",String.valueOf(edited.version()));writeProof();
                }
            }
            var preferences=InstanceScope.INSTANCE.getNode("net.resheim.eclipse.timekeeper.db");
            if(!proof.containsKey("backend.initialized")) {
                preferences.put("backend-mode","remote");preferences.put("backend-url",serverUrl.toString());preferences.flush();
                proof.setProperty("backend.initialized","true");
            }
            boolean embedded="embedded".equals(preferences.get("backend-mode","remote"));
            selectedUrl=URI.create(preferences.get("backend-url",serverUrl.toString()));
            System.setProperty("net.resheim.eclipse.timekeeper.db.url","jdbc:h2:"+folder.resolve("native-local"));
            // Reflection prevents class linkage from activating the DB plug-in before configuration.
            Class.forName("net.resheim.eclipse.timekeeper.remote.test.NativeDatabaseFixture").getMethod("prepare",Path.class).invoke(null,folder);
            String localHash=hash(folder.resolve("native-local.mv.db"));
            if(!embedded && proof.containsKey("native.sha256") && !localHash.equals(proof.getProperty("native.sha256"))) {
                if(!"embedded".equals(proof.getProperty("backend.last-runtime-mode")) || !"passed".equals(proof.getProperty("phase.backend.local")))
                    throw new IllegalStateException("The local fixture changed between remote Eclipse launches");
                proof.setProperty("native.after-embedded.sha256",localHash);
            }
            if(!proof.containsKey("native.initial.sha256")) proof.setProperty("native.initial.sha256",localHash);
            proof.setProperty("backend.last-runtime-mode",embedded ? "embedded" : "remote");
            proof.setProperty("native.sha256",localHash);proof.setProperty("distribution.sha256",checksum);proof.setProperty("server.port",String.valueOf(port));
            String previousPids=proof.getProperty("server.pids","");proof.setProperty("server.pids",previousPids.isEmpty() ? String.valueOf(server.pid()) : previousPids+","+server.pid());writeProof();
            for(var application:Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.core.runtime.applications")) {
                if(application.getDeclaringExtension().getUniqueIdentifier().equals("org.eclipse.ui.ide.workbench")) {
                    delegate=(IApplication)application.getChildren("run")[0].createExecutableExtension("class");
                    return delegate.start(context);
                }
            }
            throw new IllegalStateException("Installed IDE application is missing");
        } finally {stopReplyGate();stopServer();stopAlternate();}
    }
    private void launchServer() throws Exception {
        server=launch("main",upstreamPort==0 ? serverUrl.getPort() : upstreamPort,folder.resolve("server data/timekeeper"));
    }
    private Process launch(String name,int port,Path database) throws Exception {
        Files.createDirectories(database.getParent());
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",folder.resolve("distribution/timekeeper-server/quarkus-run.jar").toString());
        builder.environment().put("TIMEKEEPER_SERVER_DB_URL","jdbc:h2:"+database);
        builder.environment().put("TIMEKEEPER_SERVER_TOKENS_FILE",folder.resolve("server-tokens.json").toString());builder.environment().put("QUARKUS_HTTP_PORT",String.valueOf(port));
        builder.redirectErrorStream(true).redirectOutput(folder.resolve("server-"+name+"-"+System.nanoTime()+".log").toFile());
        Process process=builder.start();
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);boolean ready=false;
            while(System.nanoTime()<deadline && process.isAlive()) {
                try {ready=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/q/health/ready")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}
                catch(java.io.IOException unavailable) { }
                if(ready) break;Thread.sleep(50);
            }
            if(!ready) {process.destroyForcibly();throw new IllegalStateException("Packaged server did not become ready; see isolated fixture logs");}
        }
        return process;
    }
    public static long stopFixtureServer() {
        long pid=active.server.pid();active.stopServer();
        if(active.server.isAlive()) throw new IllegalStateException("Fixture server did not stop");
        return pid;
    }
    public static long restartFixtureServer() throws Exception {
        if(active.server!=null && active.server.isAlive()) throw new IllegalStateException("Fixture server is still running");
        active.launchServer();
        String previous=proof.getProperty("server.recovery-pids","");
        proof.setProperty("server.recovery-pids",previous.isEmpty() ? String.valueOf(active.server.pid()) : previous+","+active.server.pid());writeProof();
        return active.server.pid();
    }
    /** Cold snapshot of the isolated packaged-server H2 file, never the app's local database. */
    public static void backupFixtureServer() throws Exception {
        stopFixtureServer();
        try {
            Path backup=folder.resolve("cold-server-backup.mv.db");
            Files.copy(folder.resolve("server data/timekeeper.mv.db"),backup,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            proof.setProperty("server.backup.sha256",hash(backup));writeProof();
        } finally {restartFixtureServer();}
    }
    public static void restoreStoppedFixtureServer() throws Exception {
        if(active.server.isAlive()) throw new IllegalStateException("Cold restore requires the fixture server to be stopped");
        Path backup=folder.resolve("cold-server-backup.mv.db"),database=folder.resolve("server data/timekeeper.mv.db");
        if(!hash(backup).equals(proof.getProperty("server.backup.sha256"))) throw new IllegalStateException("Fixture backup checksum changed");
        Files.copy(backup,database,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        proof.setProperty("server.restored.sha256",hash(database));writeProof();restartFixtureServer();
    }
    @Override public void stop() {if(delegate!=null) delegate.stop();stopReplyGate();stopServer();stopAlternate();}
    private void stopAlternate() {
        if(alternate==null) return;alternate.destroy();
        try {if(!alternate.waitFor(5,TimeUnit.SECONDS)) {alternate.destroyForcibly();alternate.waitFor(5,TimeUnit.SECONDS);}}
        catch(InterruptedException interrupted) {alternate.destroyForcibly();Thread.currentThread().interrupt();}
    }
    public static void enableReplyGate() throws Exception {
        if(active.replyGate!=null) throw new IllegalStateException("Reply gate is already active");
        stopFixtureServer();try(var socket=new ServerSocket(0)) {active.upstreamPort=socket.getLocalPort();}
        restartFixtureServer();
        Path source=folder.resolve("ReplyGate.java");
        try(var input=RemoteBootstrap.class.getResourceAsStream("/fixture/ReplyGate.java")) {
            if(input==null) throw new IllegalStateException("Fixture proxy source missing");Files.copy(input,source);
        }
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"--add-modules=jdk.httpserver",source.toString(),"http://127.0.0.1:"+active.upstreamPort,String.valueOf(serverUrl.getPort()));
        builder.redirectErrorStream(true).redirectOutput(folder.resolve("server-reply-gate.log").toFile());active.replyGate=builder.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);boolean ready=false;
        while(active.replyGate.isAlive() && System.nanoTime()<deadline) {
            try {ready=control("health",null).equals("ready");}catch(java.io.IOException starting) { }
            if(ready) break;Thread.sleep(50);
        }
        if(!ready) throw new IllegalStateException("Fixture reply gate did not start");
        proof.setProperty("server.reply-gate-pid",String.valueOf(active.replyGate.pid()));writeProof();
    }
    public static void holdNextTaskReply(java.util.UUID id) throws Exception {control("hold","/api/v1/tasks/"+id);}
    public static void holdNextCreateReply(String collection) throws Exception {
        if(!java.util.Set.of("projects","tasks","activities","labels").contains(collection)) throw new IllegalArgumentException("Unsupported create fixture");
        control("hold","POST /api/v1/"+collection);
    }
    public static void holdNextTrackingReply(java.util.UUID activity) throws Exception {
        control("hold","POST /api/v1/activities/"+(activity==null ? "start" : activity+"/stop"));
    }
    public static void holdNextDeleteReply(String collection,java.util.UUID id) throws Exception {
        if(!java.util.Set.of("projects","tasks","activities","labels").contains(collection)) throw new IllegalArgumentException("Unsupported delete fixture");
        control("hold","DELETE /api/v1/"+collection+"/"+id);
    }
    public static void awaitDeleteReplyCommitted() throws Exception {awaitReplyCommitted(204);}
    public static void awaitTrackingReplyCommitted(boolean start) throws Exception {awaitReplyCommitted(start ? 201 : 200);}
    public static void awaitCreateReplyCommitted() throws Exception {awaitReplyCommitted(201);}
    public static void awaitTaskReplyCommitted() throws Exception {
        awaitReplyCommitted(200);
    }
    private static void awaitReplyCommitted(int status) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            String value=control("state",null);
            if(value.startsWith("committed=")) {
                if(!value.equals("committed="+status)) throw new IllegalStateException("Fixture mutation was not acknowledged by the backend: "+value);
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Fixture did not observe a committed task update");
    }
    public static void releaseTaskReply() throws Exception {control("release","");}
    private static String control(String operation,String value) throws Exception {
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            var request=HttpRequest.newBuilder(serverUrl.resolve("/__fixture/"+operation)).timeout(Duration.ofSeconds(2));
            if(value==null) request.GET();else request.POST(HttpRequest.BodyPublishers.ofString(value,StandardCharsets.UTF_8));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200) throw new IllegalStateException("Fixture proxy control failed");return response.body();
        }
    }
    private void stopReplyGate() {
        if(replyGate==null) return;replyGate.destroy();
        try {if(!replyGate.waitFor(5,TimeUnit.SECONDS)) {replyGate.destroyForcibly();replyGate.waitFor(5,TimeUnit.SECONDS);}}
        catch(InterruptedException interrupted) {replyGate.destroyForcibly();Thread.currentThread().interrupt();}
    }
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
    public static void setAccountBWritable(boolean writable) throws Exception {updateRegistry(true,writable);}
    public static void setAccountBEnabled(boolean enabled) throws Exception {updateRegistry(enabled,true);}
    public static void setAccountBOwner(boolean original) throws Exception {updateRegistry(true,true,original ? "remote-owner-b" : "remote-owner-c");}
    private static void updateRegistry(boolean enabled,boolean writable) throws Exception {
        updateRegistry(enabled,writable,"remote-owner-b");
    }
    private static void updateRegistry(boolean enabled,boolean writable,String owner) throws Exception {
        Path temporary=Files.createTempFile(folder,"server-tokens-",".json");
        try {
            Files.writeString(temporary,"["+principal("remote-owner-a",accountAToken)+(enabled ? ","+principal(owner,accountBToken,writable) : "")+","+principal("remote-owner-b",observerBToken)+"]",StandardCharsets.UTF_8);
            Files.move(temporary,folder.resolve("server-tokens.json"),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }
    private static String randomSecret() {byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
