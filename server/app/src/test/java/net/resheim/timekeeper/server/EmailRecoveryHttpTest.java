package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises actual SMTP delivery and the public/authenticated HTTP boundary; no mock mailbox logging. */
@QuarkusTest @TestProfile(EmailRecoveryHttpTest.Profile.class)
@QuarkusTestResource(value=EmailRecoveryHttpTest.SMTP.class,restrictToAnnotatedClass=true)
class EmailRecoveryHttpTest {
    static final String ORIGIN="https://mail.timekeeper.example.org";
    static final String PASSWORD="a sufficiently long initial passphrase";
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:recovery_http_tests","timekeeper.auth.public-origin",ORIGIN,"timekeeper.mail.enabled","true");}
    }
    public static class SMTP implements QuarkusTestResourceLifecycleManager {
        private ServerSocket server;
        private final ExecutorService threads=Executors.newCachedThreadPool();
        private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        private final BlockingQueue<String> received=new LinkedBlockingQueue<>();
        private final AtomicBoolean rejectPrimary=new AtomicBoolean();
        @Override public Map<String,String> start() {
            try {
                server=new ServerSocket(0,20,InetAddress.getLoopbackAddress());
                threads.submit(()->{while(!server.isClosed()) try {var socket=server.accept();sockets.add(socket);threads.submit(()->serve(socket));}catch(IOException stopped) {if(!server.isClosed()) throw new UncheckedIOException(stopped);}});
                return Map.of("quarkus.mailer.host","localhost","quarkus.mailer.port",Integer.toString(server.getLocalPort()),"quarkus.mailer.start-tls","DISABLED","quarkus.mailer.tls","false","quarkus.mailer.mock","false","quarkus.mailer.from","Timekeeper <timekeeper@example.org>");
            }catch(IOException failure) {throw new UncheckedIOException(failure);}
        }
        private void serve(Socket socket) {
            try(socket;var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));var output=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true)) {
                output.print("220 localhost Timekeeper test SMTP\r\n");output.flush();String recipient=null;
                for(String line;(line=input.readLine())!=null;) {
                    String command=line.toUpperCase(Locale.ROOT);
                    if(command.startsWith("EHLO") || command.startsWith("HELO")) output.print("250-localhost\r\n250 8BITMIME\r\n");
                    else if(command.startsWith("MAIL FROM")) {recipient=null;output.print("250 OK\r\n");}
                    else if(command.startsWith("RCPT TO")) {
                        if(rejectPrimary.get() && line.contains("primary@example.org")) output.print("550 Test primary unavailable\r\n");
                        else {recipient=line;output.print("250 OK\r\n");}
                    } else if(command.equals("DATA") && recipient!=null) {
                        output.print("354 End with dot\r\n");output.flush();var body=new StringBuilder(recipient).append('\n');
                        while((line=input.readLine())!=null && !line.equals(".")) body.append(line).append('\n');
                        received.add(body.toString());output.print("250 Accepted\r\n");
                    } else if(command.equals("QUIT")) {output.print("221 Bye\r\n");output.flush();return;}
                    else if(command.equals("RSET") || command.equals("NOOP")) output.print("250 OK\r\n");
                    else output.print("500 Unsupported test command\r\n");
                    output.flush();
                }
            }catch(IOException stopped) {if(!server.isClosed()) throw new UncheckedIOException(stopped);}
            finally {sockets.remove(socket);}
        }
        @Override public void inject(Object test) {
            for(var entry:Map.of("received",received,"rejectPrimary",rejectPrimary).entrySet()) try {
                var field=test.getClass().getDeclaredField(entry.getKey());field.setAccessible(true);field.set(test,entry.getValue());
            }catch(ReflectiveOperationException failure) {throw new RuntimeException(failure);}
        }
        @Override public void stop() {
            try {if(server!=null) server.close();for(var socket:sockets) socket.close();}catch(IOException failure) {throw new UncheckedIOException(failure);}finally {threads.shutdownNow();}
        }
    }
    BlockingQueue<String> received;
    AtomicBoolean rejectPrimary;
    @Inject AccountStore accounts;
    @TempDir Path temporary;

    private String link(String recipient,String route) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            String message=received.poll(1,TimeUnit.SECONDS);if(message==null) continue;
            message=message.replace("=\n","");
            if(!message.substring(0,message.indexOf('\n')).contains(recipient)) continue;
            var match=Pattern.compile(Pattern.quote(ORIGIN+"/web/#"+route+"/")+"([A-Za-z0-9_-]{43})").matcher(message);
            if(match.find()) return match.group(1);
        }
        throw new AssertionError("Expected recovery message was not delivered to the test mailbox");
    }
    private void verify(String cookie,String csrf,String slot,String email) throws Exception {
        var record=given().header("Cookie",cookie).header("Origin",ORIGIN).get("/api/v1/auth/email/addresses/"+slot).then().statusCode(200).extract().response();
        given().header("Cookie",cookie).header("Origin",ORIGIN).header("X-CSRF-Token",csrf).header("If-Match",record.header("ETag")).contentType("application/json")
                .body(Map.of("email",email,"currentPassword",PASSWORD)).put("/api/v1/auth/email/addresses/"+slot).then().statusCode(200);
        String token=link(email,"verify-email");
        for(int scan=0;scan<2;scan++) given().header("Origin",ORIGIN).contentType("application/json").body(Map.of("token",token,"purpose","VERIFY")).post("/api/v1/auth/email/preview").then().statusCode(204);
        given().header("Origin",ORIGIN).contentType("application/json").body(Map.of("token",token)).post("/api/v1/auth/email/verify").then().statusCode(204);
    }

    @Test void smtpDeliveryRecoveryAndPublicRepliesRemainIndependentOfMailboxFailures() throws Exception {
        String code=AccountPasswords.secret();var challenge=JsonValues.object();challenge.put("sha256",JsonValues.hash(code));challenge.put("purpose","SETUP");challenge.put("expiresAt",Instant.now().plusSeconds(600).toString());
        var file=temporary.resolve("setup.json");Files.writeString(file,challenge.toString());ClientProxy.unwrap(accounts).setupFile=file.toString();accounts.initialize();
        var status=given().header("Origin",ORIGIN).get("/api/v1/auth/status").then().statusCode(200).extract().response();
        String binding=BrowserCookies.BROWSER+"="+status.cookie(BrowserCookies.BROWSER);
        var setup=given().header("Cookie",binding).header("Origin",ORIGIN).contentType("application/json").body(Map.of("username","mail-admin","password",PASSWORD,"displayName","mail-admin","email","initial@example.org","code",code)).post("/api/v1/auth/setup").then().statusCode(200).extract().response();
        String cookie=AuthenticationFilter.COOKIE+"="+setup.cookie(AuthenticationFilter.COOKIE)+"; "+binding,csrf=setup.jsonPath().getString("csrf");
        try {
            verify(cookie,csrf,"PRIMARY","primary@example.org");verify(cookie,csrf,"BACKUP","backup@example.org");
            var rejected=given().header("Cookie",cookie).header("Origin","https://attacker.example.org").header("X-CSRF-Token",csrf).contentType("application/json").body("{}").post("/api/v1/auth/email/addresses/BACKUP/resend");assertEquals(403,rejected.statusCode());
            rejectPrimary.set(true);
            var known=given().header("Origin",ORIGIN).contentType("application/json").body(Map.of("username","mail-admin")).post("/api/v1/auth/email/forgot").then().statusCode(202).extract().response();
            var unknown=given().header("Origin",ORIGIN).contentType("application/json").body(Map.of("username","missing-account")).post("/api/v1/auth/email/forgot").then().statusCode(202).extract().response();
            assertEquals(known.asString(),unknown.asString());assertEquals("no-store",known.header("Cache-Control"));assertNull(known.header("Set-Cookie"));
            String reset=link("backup@example.org","reset-password");
            given().header("Origin",ORIGIN).contentType("application/json").body(Map.of("token",reset,"purpose","RESET")).post("/api/v1/auth/email/preview").then().statusCode(204);
            var completed=given().header("Origin",ORIGIN).contentType("application/json").body(Map.of("token",reset,"password",PASSWORD+" changed")).post("/api/v1/auth/email/reset").then().statusCode(204).extract().response();assertNull(completed.header("Set-Cookie"));
            given().header("Cookie",cookie).header("Origin",ORIGIN).get("/api/v1/auth/session").then().statusCode(401);
            given().header("Cookie",binding).header("Origin",ORIGIN).contentType("application/json").body(Map.of("username","mail-admin","password",PASSWORD+" changed","remember",false)).post("/api/v1/auth/login").then().statusCode(200);
            given().header("Origin",ORIGIN).contentType("application/json").body(Map.of("token",reset,"password",PASSWORD+" changed again")).post("/api/v1/auth/email/reset").then().statusCode(401);
        }finally {ClientProxy.unwrap(accounts).setupFile="NONE";}
    }
}
