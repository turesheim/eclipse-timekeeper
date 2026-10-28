package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(InstanceAdministratorTest.Profile.class)
class InstanceAdministratorTest {
    static final String PASSWORD="synthetic instance administrator password";
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){
            String url=System.getenv("TIMEKEEPER_TEST_INSTANCE_DB_URL");
            if(url==null)return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:instance_administrator_tests","timekeeper.auth.tokens-file","NONE");
            return Map.of("quarkus.datasource.jdbc.url",url,"quarkus.datasource.db-kind","postgresql","quarkus.datasource.username",System.getenv("TIMEKEEPER_TEST_PG_USER"),"quarkus.datasource.password",System.getenv("TIMEKEEPER_TEST_PG_PASSWORD"),"timekeeper.auth.tokens-file","NONE");
        }
    }
    @Inject AccountStore accounts;
    @Inject ServerStore store;
    @Inject InstanceAdministrator administrators;
    @Inject InstanceBackupService backups;
    @Inject InstanceBackupJobs jobs;
    @Inject InstanceDownloads downloads;
    @Inject InstanceRestoreUploads uploads;
    @Inject ContractInputs contracts;
    @Inject RecoveryOrigin origin;
    @Inject BackendClock clock;
    @Inject AgroalDataSource datasource;
    @TempDir Path directory;
    TokenRegistry.Principal admin;Path output;
    @BeforeEach void setup() throws Exception {
        ClientProxy.unwrap(store).stopMaintenance();ClientProxy.unwrap(administrators).clock=clock;
        accounts.tx(c->{
            for(String table:List.of("tk_relation","tk_external_reference","tk_active","tk_receipt","tk_change","tk_auth_session","tk_passkey_ceremony","tk_passkey_credential","tk_recovery_link","tk_recovery_address","tk_auth_challenge","tk_api_token","tk_passkey_user","tk_locale","tk_account","tk_resource","tk_owner","tk_auth_throttle"))execute(c,"DELETE FROM "+table);
            execute(c,"DELETE FROM tk_instance WHERE kind<>'CONTROL'");
            execute(c,"UPDATE tk_instance SET version=1,payload='{}' WHERE kind='CONTROL'");
            execute(c,"UPDATE tk_auth_control SET bootstrap_completed=FALSE");return null;
        });
        String code=AccountPasswords.secret();Path file=directory.resolve("setup.json");Files.writeString(file,object().put("purpose","SETUP").put("sha256",hash(code)).put("expiresAt",Instant.now().plusSeconds(600).toString()).toString());
        ClientProxy.unwrap(accounts).setupFile=file.toString();accounts.initialize();var issued=accounts.complete(code,"instance-admin",PASSWORD,"Administrator",false);
        admin=accounts.session(issued.secret());output=Files.createDirectory(directory.resolve("operations"));
        var worker=ClientProxy.unwrap(jobs);worker.storage=output.resolve("private").toString();worker.maximumBytes=5_000_000;worker.retainedBytes=10_000_000;worker.maximumJobs=2;
    }
    @AfterEach void reset(){ClientProxy.unwrap(accounts).setupFile="NONE";ClientProxy.unwrap(administrators).clock=clock;ClientProxy.unwrap(backups).datasource=datasource;ClientProxy.unwrap(jobs).storage="NONE";ClientProxy.unwrap(downloads).clock=clock;ClientProxy.unwrap(uploads).maximumExpandedBytes=17_179_869_184L;}

    @Test void administratorMustProveCurrentCredentialAndReceivesOnlyEncryptedArchive() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);administrators.requireFresh(proof);assertFalse(proof.toString().contains(admin.digest()));
        char[] phrase="private backup creation passphrase".toCharArray(),copy=phrase.clone();Path encrypted=backups.create(proof,phrase,output);
        assertTrue(allCleared(phrase));
        try(var archive=InstanceBackupArchive.open(encrypted,copy,output,100_000_000)) {
            assertEquals(23,archive.counts().size());assertEquals(1,archive.counts().get("tk_account"));assertEquals(1,archive.counts().get("tk_auth_session"));
        }finally{Arrays.fill(copy,'\0');}
    }
    @Test void normalReadonlyManagedLegacyAndTokenExchangedSessionsCannotGainInstanceAuthority() {
        String owner=accounts.createUser(admin,"ordinary-user",PASSWORD,"Normal user",false).path("ownerId").asText();
        var normal=accounts.session(accounts.login("ordinary-user",PASSWORD,false).secret());denied(normal);
        var readOnly=accounts.tx(c->accounts.issue(c,admin.owner(),Set.of("read"),false,Instant.now(),null));denied(accounts.session(readOnly.secret()));
        var created=accounts.createToken(admin,"Admin API token",Set.of("read","write"),null);var token=accounts.managed(hash(created.path("token").asText()));denied(token);
        denied(accounts.session(accounts.exchange(token).secret()));denied(new TokenRegistry.Principal(admin.owner(),Set.of("read","write"),"LEGACY","0".repeat(64),null));
        assertNotEquals(admin.owner(),owner);
    }
    private void denied(TokenRegistry.Principal principal) {
        assertEquals(403,assertThrows(ApiFailure.class,()->administrators.require(principal)).status);
        assertEquals(403,assertThrows(ApiFailure.class,()->jobs.get(principal,UUID.randomUUID())).status);
        assertEquals(403,assertThrows(ApiFailure.class,()->administrators.verify(principal,PASSWORD)).status);
    }
    @Test void failedProofAttemptsAreRateLimitedWithoutEchoingPassword() {
        for(int index=0;index<5;index++){var failure=assertThrows(ApiFailure.class,()->administrators.verify(admin,"private wrong password"));assertEquals(422,failure.status);assertEquals("INSTANCE_PROOF_REQUIRED",failure.code);assertNull(failure.getCause());}
        assertEquals(429,assertThrows(ApiFailure.class,()->administrators.verify(admin,PASSWORD)).status);
    }
    @Test void successfulProofResetsFailuresWithoutBlockingNormalRepeatedOperations() {
        for(int cycle=0;cycle<2;cycle++) {
            for(int index=0;index<4;index++)assertThrows(ApiFailure.class,()->administrators.verify(admin,"wrong password"));
            administrators.requireFresh(administrators.verify(admin,PASSWORD));
        }
    }
    @Test void expiredOrRevokedProofCannotCreateFilesAndPassphraseIsCleared() throws Exception {
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());ClientProxy.unwrap(administrators).clock=new BackendClock(){@Override public Instant now(){return now.get();}};
        var proof=administrators.verify(admin,PASSWORD);now.set(now.get().plusSeconds(301));
        char[] phrase="private passphrase on expired proof".toCharArray();assertEquals(422,assertThrows(ApiFailure.class,()->backups.create(proof,phrase,output)).status);assertTrue(allCleared(phrase));
        now.set(now.get().minusSeconds(301));accounts.logout(admin);
        assertEquals(401,assertThrows(ApiFailure.class,()->administrators.requireFresh(proof)).status);
        try(var files=Files.list(output)){assertEquals(0,files.count());}
    }
    @Test void demotionDuringBackupCannotPublishArtifact() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);
        ClientProxy.unwrap(backups).datasource=afterCommit(()->accounts.tx(c->{execute(c,"UPDATE tk_account SET administrator=FALSE,version=version+1 WHERE owner_id=?",admin.owner());return null;}));
        char[] phrase="private passphrase during demotion".toCharArray();
        assertEquals(403,assertThrows(ApiFailure.class,()->backups.create(proof,phrase,output)).status);assertTrue(allCleared(phrase));
        try(var files=Files.list(output)){assertEquals(0,files.count());}
    }
    @Test void changedConfigurationCannotPublishAMixedSnapshot() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);String previous=System.getProperty("timekeeper.package-id");
        try {
            ClientProxy.unwrap(backups).datasource=afterCommit(()->System.setProperty("timekeeper.package-id","changed-during-backup"));
            char[] phrase="private passphrase during configuration change".toCharArray();
            var failure=assertThrows(ApiFailure.class,()->backups.create(proof,phrase,output));assertEquals("INSTANCE_CONFIGURATION_CHANGED",failure.code);assertNull(failure.getCause());assertTrue(allCleared(phrase));
            try(var files=Files.list(output)){assertEquals(0,files.count());}
        }finally {if(previous==null)System.clearProperty("timekeeper.package-id");else System.setProperty("timekeeper.package-id",previous);}
    }
    @Test void durableBackupJobIsEncryptedAuditedReadableAndExplicitlyDiscarded() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);char[] phrase="durable job backup passphrase".toCharArray(),copy=phrase.clone();
        var job=jobs.create(proof,phrase);assertTrue(allCleared(phrase));assertEquals(InstanceOperations.State.READY,job.state());
        assertEquals(job,jobs.get(admin,job.id()));
        Path encrypted=output.resolve("download.tki");jobs.read(proof,job.id(),(input,bytes)->{try(var target=Files.newOutputStream(encrypted)){assertEquals(bytes,input.transferTo(target));}return null;});
        try(var verified=InstanceBackupArchive.open(encrypted,copy,output,100_000_000)){assertEquals(23,verified.counts().size());assertEquals(1,verified.counts().get("tk_account"));assertEquals(3,verified.counts().get("tk_instance"));}
        finally{Arrays.fill(copy,'\0');}
        Path folder=output.resolve("private").resolve(job.id().toString());assertFalse(Files.exists(folder.resolve("work")));
        assertEquals(InstanceOperations.State.EXPIRED,jobs.discard(proof,job.id()).state());assertFalse(Files.exists(folder));
        assertEquals(InstanceOperations.State.EXPIRED,jobs.discard(proof,job.id()).state());
        assertEquals(409,assertThrows(ApiFailure.class,()->jobs.read(proof,job.id(),(input,bytes)->null)).status);
        accounts.tx(c->{try(var statement=query(c,"SELECT count(*) FROM tk_instance WHERE kind='AUDIT'");var rows=statement.executeQuery()){rows.next();assertEquals(3,rows.getInt(1));}catch(Exception failure){throw new AssertionError(failure);}return null;});
    }
    @Test void revokedBackupJobRecordsFailureAndRemovesAllPrivateWorkingFiles() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);ClientProxy.unwrap(backups).datasource=afterCommit(()->accounts.logout(admin));
        char[] phrase="private durable revoked backup phrase".toCharArray();assertThrows(ApiFailure.class,()->jobs.create(proof,phrase));assertTrue(allCleared(phrase));
        accounts.tx(c->{try(var statement=query(c,"SELECT payload FROM tk_instance WHERE kind='OPERATION'");var rows=statement.executeQuery()){assertTrue(rows.next());assertEquals("FAILED",parse(rows.getString(1)).path("state").asText());assertFalse(rows.next());}catch(Exception failure){throw new AssertionError(failure);}return null;});
        try(var paths=Files.list(output.resolve("private"))){assertEquals(Set.of(".lock",".identity"),paths.map(path->path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));}
    }
    @Test void backupJobCannotTouchStorageWithoutFreshProofOrFitAnOversizedArchive() throws Exception {
        char[] denied="private denied backup phrase".toCharArray();assertEquals(422,assertThrows(ApiFailure.class,()->jobs.create(null,denied)).status);assertTrue(allCleared(denied));assertFalse(Files.exists(output.resolve("private")));
        var proof=administrators.verify(admin,PASSWORD);var worker=ClientProxy.unwrap(jobs);worker.maximumBytes=64;worker.retainedBytes=128;
        char[] phrase="private over quota backup phrase".toCharArray();char[] oversized=phrase;assertEquals(413,assertThrows(ApiFailure.class,()->jobs.create(proof,oversized)).status);assertTrue(allCleared(phrase));
        try(var paths=Files.list(output.resolve("private"))){assertEquals(2,paths.count());}
    }
    @Test void newBackupCleansAbandonedWorkingStateWithoutErasingAudit() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);var files=new InstanceArtifacts(output.resolve("private"),5_000_000,10_000_000,2);
        var abandoned=accounts.tx(c->{InstanceOperations.bindStorage(c,files.identity());return InstanceOperations.begin(c,admin.owner(),InstanceOperations.Type.BACKUP,clock.now().minusSeconds(601));});
        Path work=files.reserveBackup(abandoned.id());Path secret=work.resolve("incomplete.zip");Files.writeString(secret,"private interrupted plaintext");Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var next=jobs.create(proof,"private recovered backup phrase".toCharArray());assertEquals(InstanceOperations.State.READY,next.state());assertFalse(Files.exists(work.getParent()));
        assertEquals(InstanceOperations.State.INTERRUPTED,jobs.get(admin,abandoned.id()).state());
    }
    @Test void asynchronousStartReturnsDurableReceiptBeforeSnapshotCanFinish() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        ClientProxy.unwrap(backups).datasource=afterCommit(()->{entered.countDown();try{if(!release.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Snapshot gate timed out");}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new AssertionError(stopped);}});
        char[] phrase="private asynchronous backup phrase".toCharArray();InstanceOperations.Status job=null;
        try {
            job=jobs.start(proof,phrase);assertTrue(allCleared(phrase));assertEquals(InstanceOperations.State.PREPARING,job.state());assertTrue(entered.await(30,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(InstanceOperations.State.PREPARING,jobs.get(admin,job.id()).state());
            char[] second="private conflicting job phrase".toCharArray();assertEquals(409,assertThrows(ApiFailure.class,()->jobs.start(proof,second)).status);assertTrue(allCleared(second));
        }finally {release.countDown();}
        assertNotNull(job);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);InstanceOperations.Status current;
        do{current=jobs.get(admin,job.id());if(current.state()!=InstanceOperations.State.PREPARING)break;Thread.sleep(20);}while(System.nanoTime()<deadline);
        assertEquals(InstanceOperations.State.READY,current.state());jobs.discard(proof,job.id());
    }
    private record Browser(String cookie,String csrf) { }
    private Browser httpBrowser(io.restassured.response.Response response) {
        assertEquals(200,response.statusCode(),response.asString());
        String cookie=AuthenticationFilter.COOKIE+"="+response.cookie(AuthenticationFilter.COOKIE);
        if(response.cookie(BrowserCookies.BROWSER)!=null)cookie+="; "+BrowserCookies.BROWSER+"="+response.cookie(BrowserCookies.BROWSER);
        return new Browser(cookie,parse(response.asString()).path("csrf").asText());
    }
    private io.restassured.response.Response http(Browser browser,boolean csrf,String method,String path,com.fasterxml.jackson.databind.JsonNode input) {
        var request=io.restassured.RestAssured.given();if(!origin.configured().equals("NONE"))request.header("Origin",origin.configured());
        if(browser!=null){request.header("Cookie",browser.cookie());if(csrf)request.header("X-CSRF-Token",browser.csrf());}
        if(input!=null)request.contentType("application/json").body(input.toString());return request.request(method,"/api/v1"+path);
    }
    private Browser httpLogin(String username) {return httpBrowser(http(null,false,"POST","/auth/login",object().put("username",username).put("password",PASSWORD).put("remember",false)));}
    @Test void httpBackupRequiresBrowserCsrfAndProofAndExposesOnlyDurableStatus() throws Exception {
        var browser=httpLogin("instance-admin");String path="/admin/instance/backups";var input=object().put("currentPassword",PASSWORD).put("passphrase","private HTTP archive passphrase");
        assertEquals(403,http(browser,false,"POST",path,input).statusCode());
        assertEquals(403,io.restassured.RestAssured.given().header("Cookie",browser.cookie()).header("X-CSRF-Token",browser.csrf()).header("Origin","https://untrusted.example").contentType("application/json").body(input.toString()).post("/api/v1"+path).statusCode());
        assertEquals(422,http(browser,true,"POST",path,input.deepCopy().put("currentPassword","wrong password")).statusCode());
        assertEquals(400,http(browser,true,"POST",path,input.deepCopy().put("path","/private/source")).statusCode());
        assertFalse(Files.exists(output.resolve("private")));
        var response=http(browser,true,"POST",path,input);assertEquals(202,response.statusCode(),response.asString());assertEquals("no-store",response.header("Cache-Control"));
        var job=parse(response.asString());contracts.validate("InstanceOperation",job);assertEquals("PREPARING",job.path("state").asText());
        var names=new HashSet<String>();job.fieldNames().forEachRemaining(names::add);assertEquals(Set.of("id","type","state","version","updatedAt"),names);
        String status="/admin/instance/operations/"+job.path("id").asText();assertEquals("/api/v1"+status,response.header("Location"));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);com.fasterxml.jackson.databind.JsonNode current;
        do{var read=http(browser,false,"GET",status,null);assertEquals(200,read.statusCode(),read.asString());current=parse(read.asString());if(!current.path("state").asText().equals("PREPARING"))break;Thread.sleep(20);}while(System.nanoTime()<deadline);
        assertEquals("READY",current.path("state").asText());contracts.validate("InstanceOperation",current);
        assertEquals(403,http(browser,false,"POST",status+"/discard",object().put("currentPassword",PASSWORD)).statusCode());
        var discarded=http(browser,true,"POST",status+"/discard",object().put("currentPassword",PASSWORD));assertEquals(200,discarded.statusCode(),discarded.asString());assertEquals("EXPIRED",parse(discarded.asString()).path("state").asText());
    }
    @Test void httpSummaryRecoversRecentOperationsWithoutOpeningStorageOrExposingCapabilities() throws Exception {
        var browser=httpLogin("instance-admin");var principal=accounts.session(browser.cookie().split("=",2)[1].split(";",2)[0]);
        var initial=http(browser,false,"GET","/admin/instance",null);assertEquals(200,initial.statusCode(),initial.asString());
        contracts.validate("InstanceBackupSummary",parse(initial.asString()));assertTrue(parse(initial.asString()).path("items").isEmpty());assertFalse(Files.exists(output.resolve("private")));
        UUID id=accounts.tx(c->{lock(c);return InstanceOperations.beginUpload(c,principal,100,"a".repeat(64),clock.now()).id();});
        var read=http(browser,false,"GET","/admin/instance",null);assertEquals(200,read.statusCode(),read.asString());var value=parse(read.asString());contracts.validate("InstanceBackupSummary",value);
        assertEquals(id.toString(),value.path("items").get(0).path("operation").path("id").asText());assertTrue(value.path("items").get(0).path("canAccessUpload").asBoolean());
        assertEquals(5_000_000,value.path("maximumArchiveBytes").asLong());assertTrue(value.path("storageConfigured").asBoolean());
        assertFalse(read.asString().contains(principal.digest()));assertFalse(read.asString().contains("worker"));assertFalse(Files.exists(output.resolve("private")));
        var other=httpLogin("instance-admin");assertFalse(parse(http(other,false,"GET","/admin/instance",null).asString()).path("items").get(0).path("canAccessUpload").asBoolean());
        assertEquals(400,http(browser,false,"GET","/admin/instance?path=private",null).statusCode());
        ClientProxy.unwrap(jobs).storage="NONE";assertFalse(parse(http(browser,false,"GET","/admin/instance",null).asString()).path("storageConfigured").asBoolean());
    }
    @Test void httpRegularAndTokenExchangedAccountsCannotReachInstanceJobs() {
        var token=accounts.createToken(admin,"Forbidden instance token",Set.of("read","write"),null).path("token").asText();
        var input=object().put("currentPassword",PASSWORD).put("passphrase","private forbidden API passphrase");
        assertEquals(403,io.restassured.RestAssured.given().header("Authorization","Bearer "+token).contentType("application/json").body(input.toString()).post("/api/v1/admin/instance/backups").statusCode());
        assertEquals(403,io.restassured.RestAssured.given().header("Authorization","Bearer "+token).get("/api/v1/admin/instance/operations/"+UUID.randomUUID()).statusCode());
        var request=io.restassured.RestAssured.given().header("Authorization","Bearer "+token);if(!origin.configured().equals("NONE"))request.header("Origin",origin.configured());
        var exchanged=httpBrowser(request.post("/api/v1/auth/token-login"));assertEquals(403,http(exchanged,true,"POST","/admin/instance/backups",input).statusCode());assertEquals(403,http(exchanged,false,"GET","/admin/instance",null).statusCode());
        accounts.createUser(admin,"http-ordinary-user",PASSWORD,"Ordinary user",false);var normal=httpLogin("http-ordinary-user");
        assertEquals(403,http(normal,true,"POST","/admin/instance/backups",input).statusCode());assertEquals(403,http(normal,false,"GET","/admin/instance",null).statusCode());
        assertEquals(403,http(normal,false,"GET","/admin/instance/operations/"+UUID.randomUUID(),null).statusCode());
        assertFalse(Files.exists(output.resolve("private")));
    }
    private Browser withDownload(Browser browser,io.restassured.response.Response response) {
        assertEquals(200,response.statusCode(),response.asString());String grant=response.cookie(InstanceDownloads.COOKIE);assertNotNull(grant);
        return new Browser(browser.cookie()+"; "+InstanceDownloads.COOKIE+"="+grant,browser.csrf());
    }
    private InstanceOperations.Status readyBackup() {return jobs.create(administrators.verify(admin,PASSWORD),"native browser backup passphrase".toCharArray());}
    @Test void nativeHttpDownloadIsEncryptedCookieBoundOneUseAndNeverPutsAGrantInTheUrl() throws Exception {
        var job=readyBackup();var browser=httpLogin("instance-admin");String path="/admin/instance/operations/"+job.id();var proof=object().put("currentPassword",PASSWORD);
        assertEquals(403,http(browser,false,"GET",path+"/content",null).statusCode());
        assertEquals(403,http(browser,false,"POST",path+"/download-grant",proof).statusCode());
        assertEquals(422,http(browser,true,"POST",path+"/download-grant",proof.deepCopy().put("currentPassword","wrong password")).statusCode());
        var issued=http(browser,true,"POST",path+"/download-grant",proof);var authorized=withDownload(browser,issued);var reply=parse(issued.asString());contracts.validate("InstanceDownload",reply);
        String raw=issued.cookie(InstanceDownloads.COOKIE);assertFalse(issued.asString().contains(raw));assertEquals("/api/v1"+path+"/content",reply.path("downloadUrl").asText());
        String cookie=issued.getHeaders().getValues("Set-Cookie").stream().filter(value->value.startsWith(InstanceDownloads.COOKIE+"=")).findFirst().orElseThrow();
        for(String flag:List.of("HttpOnly","Secure","SameSite=Strict","Path=/","Max-Age=120"))assertTrue(cookie.contains(flag));
        assertEquals(400,http(new Browser(authorized.cookie()+"; "+InstanceDownloads.COOKIE+"="+raw,authorized.csrf()),false,"GET",path+"/content",null).statusCode());
        assertEquals(403,io.restassured.RestAssured.given().header("Cookie",authorized.cookie()).header("Origin","https://untrusted.example").get("/api/v1"+path+"/content").statusCode());
        var response=http(authorized,false,"GET",path+"/content",null);assertEquals(200,response.statusCode(),response.asString());assertEquals("application/octet-stream",response.contentType());assertEquals("no-store",response.header("Cache-Control"));
        assertEquals("attachment; filename=\"timekeeper-instance-"+job.id()+".tkbackup\"",response.header("Content-Disposition"));
        byte[] encrypted=response.asByteArray();assertEquals(encrypted.length,Long.parseLong(response.header("Content-Length")));assertTrue(new String(encrypted,0,8,java.nio.charset.StandardCharsets.US_ASCII).equals("TKIBK001"));
        Path file=output.resolve("http-downloaded.tki");Files.write(file,encrypted);char[] phrase="native browser backup passphrase".toCharArray();
        try(var archive=InstanceBackupArchive.open(file,phrase,output,100_000_000)){assertEquals(23,archive.counts().size());}finally{Arrays.fill(phrase,'\0');}
        assertEquals(403,http(authorized,false,"GET",path+"/content",null).statusCode());
        accounts.tx(c->{try(var statement=query(c,"SELECT payload FROM tk_instance WHERE kind='AUDIT'");var rows=statement.executeQuery()){var actions=new HashSet<String>();while(rows.next()){var item=parse(rows.getString(1));assertFalse(item.toString().contains(raw));if(item.has("action"))actions.add(item.path("action").asText());}assertEquals(Set.of("DOWNLOAD_AUTHORIZED","DOWNLOAD_STARTED"),actions);}catch(Exception failure){throw new AssertionError(failure);}return null;});
    }
    @Test void downloadGrantCannotCrossJobsSessionsExpiryOrLogout() {
        var first=readyBackup();var second=readyBackup();var browser=httpLogin("instance-admin");var other=httpLogin("instance-admin");var proof=object().put("currentPassword",PASSWORD);
        String path="/admin/instance/operations/"+first.id();var issued=http(browser,true,"POST",path+"/download-grant",proof);var authorized=withDownload(browser,issued);
        var wrongSession=new Browser(other.cookie()+"; "+InstanceDownloads.COOKIE+"="+issued.cookie(InstanceDownloads.COOKIE),other.csrf());
        assertEquals(403,http(wrongSession,false,"GET",path+"/content",null).statusCode());
        assertEquals(403,http(authorized,false,"GET","/admin/instance/operations/"+second.id()+"/content",null).statusCode());
        ClientProxy.unwrap(downloads).clock=new BackendClock(){public Instant now(){return clock.now().plusSeconds(121);}};
        assertEquals(403,http(authorized,false,"GET",path+"/content",null).statusCode());ClientProxy.unwrap(downloads).clock=clock;
        var replacement=withDownload(browser,http(browser,true,"POST",path+"/download-grant",proof));
        assertEquals(403,http(authorized,false,"GET",path+"/content",null).statusCode());
        assertEquals(204,http(browser,true,"POST","/auth/logout",null).statusCode());assertEquals(401,http(replacement,false,"GET",path+"/content",null).statusCode());
    }
    @Test void concurrentNativeDownloadsCanConsumeACookieGrantOnlyOnce() throws Exception {
        var job=readyBackup();var browser=httpLogin("instance-admin");String path="/admin/instance/operations/"+job.id();var authorized=withDownload(browser,http(browser,true,"POST",path+"/download-grant",object().put("currentPassword",PASSWORD)));
        var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var replies=new ArrayList<java.util.concurrent.Future<Integer>>();
            for(int index=0;index<2;index++)replies.add(workers.submit(()->{ready.countDown();assertTrue(go.await(30,java.util.concurrent.TimeUnit.SECONDS));return http(authorized,false,"GET",path+"/content",null).statusCode();}));
            assertTrue(ready.await(30,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            var statuses=new ArrayList<Integer>();for(var reply:replies)statuses.add(reply.get(30,java.util.concurrent.TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,403),statuses);
        }finally{go.countDown();}
    }
    @Test void damagedCiphertextCannotMintADownloadGrantOrLeakItsStoragePath() throws Exception {
        var job=readyBackup();Path file=output.resolve("private").resolve(job.id().toString()).resolve("archive.tki");byte[] encrypted=Files.readAllBytes(file);encrypted[0]^=1;Files.write(file,encrypted,StandardOpenOption.TRUNCATE_EXISTING);
        var browser=httpLogin("instance-admin");var response=http(browser,true,"POST","/admin/instance/operations/"+job.id()+"/download-grant",object().put("currentPassword",PASSWORD));
        assertEquals(503,response.statusCode());assertFalse(response.asString().contains(output.toString()));assertFalse(response.asString().contains(PASSWORD));assertNull(response.cookie(InstanceDownloads.COOKIE));
    }
    private byte[] uploadFixture() throws Exception {
        var proof=administrators.verify(admin,PASSWORD);var job=jobs.create(proof,"upload review source passphrase".toCharArray());return jobs.read(proof,job.id(),(input,length)->input.readAllBytes());
    }
    private String cipherDigest(byte[] bytes) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private String createUpload(Browser browser,byte[] bytes,String digest) {
        var response=http(browser,true,"POST","/admin/instance/uploads",object().put("currentPassword",PASSWORD).put("bytes",bytes.length).put("sha256",digest));
        assertEquals(201,response.statusCode(),response.asString());var value=parse(response.asString());contracts.validate("InstanceRestoreUpload",value);String id=value.path("operation").path("id").asText();
        assertEquals("/api/v1/admin/instance/uploads/"+id,response.header("Location"));return id;
    }
    private io.restassured.response.Response chunk(Browser browser,boolean csrf,String id,long offset,byte[] bytes) {
        var request=io.restassured.RestAssured.given().header("Cookie",browser.cookie()).contentType("application/octet-stream").body(bytes);if(csrf)request.header("X-CSRF-Token",browser.csrf());if(!origin.configured().equals("NONE"))request.header("Origin",origin.configured());
        return request.put("/api/v1/admin/instance/uploads/"+id+"/content?offset="+offset);
    }
    private com.fasterxml.jackson.databind.JsonNode awaitUpload(Browser browser,String id,String expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);com.fasterxml.jackson.databind.JsonNode value;
        do{var response=http(browser,false,"GET","/admin/instance/uploads/"+id,null);assertEquals(200,response.statusCode(),response.asString());value=parse(response.asString());contracts.validate("InstanceRestoreUpload",value);if(value.path("operation").path("state").asText().equals(expected))return value;Thread.sleep(20);}while(System.nanoTime()<deadline);
        fail("Upload did not reach "+expected);return value;
    }
    @Test void httpUploadResumesRejectsChangedRetriesAndReviewsWithoutRestoringData() throws Exception {
        byte[] bytes=uploadFixture();var browser=httpLogin("instance-admin");
        var live=http(browser,true,"POST","/clients",object().put("name","Keep this target client").put("color","#123456"));assertEquals(201,live.statusCode(),live.asString());var client=parse(live.asString());
        String id=createUpload(browser,bytes,cipherDigest(bytes));String path="/admin/instance/uploads/"+id;int split=bytes.length/2;
        var first=Arrays.copyOf(bytes,split);assertEquals(403,chunk(browser,false,id,0,first).statusCode());
        var uploaded=chunk(browser,true,id,0,first);assertEquals(200,uploaded.statusCode(),uploaded.asString());assertEquals(split,parse(uploaded.asString()).path("received").asLong());
        assertEquals(200,chunk(browser,true,id,0,first).statusCode());var changed=first.clone();changed[0]^=1;assertEquals(409,chunk(browser,true,id,0,changed).statusCode());
        assertEquals(422,http(browser,true,"POST",path+"/validate",object().put("currentPassword",PASSWORD).put("passphrase","upload review source passphrase")).statusCode());
        assertEquals(200,chunk(browser,true,id,split,Arrays.copyOfRange(bytes,split,bytes.length)).statusCode());
        var wrong=http(browser,true,"POST",path+"/validate",object().put("currentPassword",PASSWORD).put("passphrase","incorrect backup passphrase"));assertEquals(202,wrong.statusCode(),wrong.asString());
        var rejected=awaitUpload(browser,id,"UPLOADING");assertEquals("INVALID_INSTANCE_BACKUP",rejected.path("validationCode").asText());assertTrue(rejected.path("complete").asBoolean());
        var accepted=http(browser,true,"POST",path+"/validate",object().put("currentPassword",PASSWORD).put("passphrase","upload review source passphrase"));assertEquals(202,accepted.statusCode(),accepted.asString());
        var review=awaitUpload(browser,id,"REVIEW");assertTrue(review.path("review").path("archiveVerified").asBoolean());assertFalse(review.path("review").path("restoreReady").asBoolean());assertEquals(23,review.path("review").path("counts").size());
        assertFalse(review.toString().contains(PASSWORD));assertFalse(review.toString().contains("saved_smtp_password"));assertFalse(review.toString().contains(output.toString()));
        assertFalse(Files.exists(output.resolve("private").resolve(id).resolve("work")));
        int accountCount=accounts.tx(c->{try(var statement=query(c,"SELECT count(*) FROM tk_account");var rows=statement.executeQuery()){rows.next();return rows.getInt(1);}catch(Exception failure){throw new AssertionError(failure);}});assertEquals(1,accountCount);
        var retained=http(browser,false,"GET","/clients/"+client.path("id").asText(),null);assertEquals(200,retained.statusCode(),retained.asString());assertEquals(client,parse(retained.asString()));
        assertEquals(200,http(browser,true,"POST","/admin/instance/operations/"+id+"/discard",object().put("currentPassword",PASSWORD)).statusCode());assertFalse(Files.exists(output.resolve("private").resolve(id)));
    }
    @Test void targetReviewRequiresFreshInitiatingSessionProofAndNeverReplacesExistingData() throws Exception {
        byte[] bytes=uploadFixture();var browser=httpLogin("instance-admin");String id=createUpload(browser,bytes,cipherDigest(bytes));String path="/admin/instance/uploads/"+id;
        var proof=object().put("currentPassword",PASSWORD);
        assertEquals(409,http(browser,true,"POST",path+"/target",proof).statusCode());
        assertEquals(200,chunk(browser,true,id,0,bytes).statusCode());
        assertEquals(202,http(browser,true,"POST",path+"/validate",proof.deepCopy().put("passphrase","upload review source passphrase")).statusCode());
        long version=awaitUpload(browser,id,"REVIEW").path("operation").path("version").asLong();
        assertEquals(403,http(browser,false,"POST",path+"/target",proof).statusCode());
        assertEquals(422,http(browser,true,"POST",path+"/target",object().put("currentPassword","wrong password")).statusCode());
        assertEquals(400,http(browser,true,"POST",path+"/target",proof.deepCopy().put("path","/private/target")).statusCode());
        assertEquals(403,http(httpLogin("instance-admin"),true,"POST",path+"/target",proof).statusCode());
        // The normal owner initialization creates the eight built-in labels.
        assertEquals(200,http(browser,false,"GET","/labels",null).statusCode());
        var initial=http(browser,true,"POST",path+"/target",proof);assertEquals(200,initial.statusCode(),initial.asString());var empty=parse(initial.asString());contracts.validate("InstanceRestoreTarget",empty);
        assertTrue(empty.path("databaseEligible").asBoolean(),initial.asString());assertFalse(empty.path("restoreReady").asBoolean());assertTrue(empty.path("blockers").isEmpty());assertEquals(version,empty.path("operationVersion").asLong());
        var created=http(browser,true,"POST","/clients",object().put("name","Existing target data").put("color","#123456"));assertEquals(201,created.statusCode());
        var populated=http(browser,true,"POST",path+"/target",proof);assertEquals(200,populated.statusCode(),populated.asString());var blocked=parse(populated.asString());contracts.validate("InstanceRestoreTarget",blocked);
        assertFalse(blocked.path("databaseEligible").asBoolean());assertTrue(blocked.path("blockers").toString().contains("TARGET_BUSINESS_DATA"));assertFalse(blocked.toString().contains(PASSWORD));assertFalse(blocked.toString().contains(output.toString()));
        var retained=http(browser,false,"GET","/clients/"+parse(created.asString()).path("id").asText(),null);assertEquals(200,retained.statusCode());assertEquals(parse(created.asString()),parse(retained.asString()));
        assertEquals(version,awaitUpload(browser,id,"REVIEW").path("operation").path("version").asLong());
    }
    @Test void invalidSourceAuthenticationHasASafeDistinctValidationCodeAndCannotReachReview() throws Exception {
        String owner=accounts.createUser(admin,"invalid-source-user",PASSWORD,"Source user",false).path("ownerId").asText();
        accounts.tx(c->{execute(c,"UPDATE tk_account SET password_hash=? WHERE owner_id=?","private unsupported source credential",owner);return null;});
        byte[] bytes=uploadFixture();var browser=httpLogin("instance-admin");String id=createUpload(browser,bytes,cipherDigest(bytes)),path="/admin/instance/uploads/"+id;
        assertEquals(200,chunk(browser,true,id,0,bytes).statusCode());
        var accepted=http(browser,true,"POST",path+"/validate",object().put("currentPassword",PASSWORD).put("passphrase","upload review source passphrase"));assertEquals(202,accepted.statusCode(),accepted.asString());
        var rejected=awaitUpload(browser,id,"UPLOADING");assertEquals("INVALID_INSTANCE_AUTHENTICATION",rejected.path("validationCode").asText());assertFalse(rejected.has("review"));assertTrue(rejected.path("complete").asBoolean());
        assertFalse(rejected.toString().contains("private unsupported"));assertFalse(rejected.toString().contains("invalid-source-user"));assertFalse(Files.exists(output.resolve("private").resolve(id).resolve("work")));
        assertEquals(409,http(browser,true,"POST",path+"/target",object().put("currentPassword",PASSWORD)).statusCode());
    }
    @Test void uploadRejectsOtherSessionsTokensOversizedChunksAndWrongChecksums() throws Exception {
        byte[] bytes=uploadFixture();var browser=httpLogin("instance-admin");String id=createUpload(browser,bytes,"0".repeat(64));String path="/admin/instance/uploads/"+id;var other=httpLogin("instance-admin");
        assertEquals(403,http(other,false,"GET",path,null).statusCode());assertEquals(403,chunk(other,true,id,0,bytes).statusCode());
        assertEquals(413,chunk(browser,true,id,0,new byte[InstanceArtifacts.CHUNK_BYTES+1]).statusCode());
        assertEquals(200,chunk(browser,true,id,0,bytes).statusCode());assertEquals(202,http(browser,true,"POST",path+"/validate",object().put("currentPassword",PASSWORD).put("passphrase","upload review source passphrase")).statusCode());
        assertEquals("INVALID_INSTANCE_BACKUP",awaitUpload(browser,id,"UPLOADING").path("validationCode").asText());
        assertFalse(Files.exists(output.resolve("private").resolve(id).resolve("work")));
        var token=accounts.createToken(admin,"Forbidden upload token",Set.of("read","write"),null).path("token").asText();
        assertEquals(403,io.restassured.RestAssured.given().header("Authorization","Bearer "+token).get("/api/v1"+path).statusCode());
        assertEquals(200,http(browser,true,"POST","/admin/instance/operations/"+id+"/discard",object().put("currentPassword",PASSWORD)).statusCode());
        accounts.tx(c->{assertEquals(0,InstanceOperations.interruptAbandoned(c,clock.now().plusSeconds(601)));assertEquals(InstanceOperations.State.EXPIRED,InstanceOperations.status(c,UUID.fromString(id)).state());return null;});
    }
    @Test void uploadReviewEnforcesExpansionBudgetAndAllowsRetryWithoutUploadingAgain() throws Exception {
        byte[] bytes=uploadFixture();var browser=httpLogin("instance-admin");String id=createUpload(browser,bytes,cipherDigest(bytes));assertEquals(200,chunk(browser,true,id,0,bytes).statusCode());String path="/admin/instance/uploads/"+id;
        ClientProxy.unwrap(uploads).maximumExpandedBytes=256;
        assertEquals(202,http(browser,true,"POST",path+"/validate",object().put("currentPassword",PASSWORD).put("passphrase","upload review source passphrase")).statusCode());
        var rejected=awaitUpload(browser,id,"UPLOADING");assertEquals("INVALID_INSTANCE_BACKUP",rejected.path("validationCode").asText());assertFalse(Files.exists(output.resolve("private").resolve(id).resolve("work")));
        ClientProxy.unwrap(uploads).maximumExpandedBytes=17_179_869_184L;
        assertEquals(202,http(browser,true,"POST",path+"/validate",object().put("currentPassword",PASSWORD).put("passphrase","upload review source passphrase")).statusCode());assertFalse(awaitUpload(browser,id,"REVIEW").has("validationCode"));
    }
    @Test void cancellingAnotherAdministratorsUploadRecordsTheActualActor() throws Exception {
        byte[] bytes=uploadFixture();var uploader=httpLogin("instance-admin");String id=createUpload(uploader,bytes,cipherDigest(bytes));
        String owner=accounts.createUser(admin,"second-administrator",PASSWORD,"Second administrator",true).path("ownerId").asText();var other=httpLogin("second-administrator");
        assertEquals(200,http(other,true,"POST","/admin/instance/operations/"+id+"/discard",object().put("currentPassword",PASSWORD)).statusCode());
        accounts.tx(c->{try(var statement=query(c,"SELECT actor_owner,payload FROM tk_instance WHERE kind='AUDIT' AND operation_id=?",UUID.fromString(id));var rows=statement.executeQuery()){boolean found=false;while(rows.next())if(parse(rows.getString(2)).path("state").asText().equals("EXPIRED")){found=true;assertEquals(owner,rows.getString(1));}assertTrue(found);}catch(Exception failure){throw new AssertionError(failure);}return null;});
        assertEquals("EXPIRED",awaitUpload(uploader,id,"EXPIRED").path("operation").path("state").asText());
    }
    private AgroalDataSource afterCommit(Runnable action) {
        var guarded=(AgroalDataSource)java.lang.reflect.Proxy.newProxyInstance(AgroalDataSource.class.getClassLoader(),new Class<?>[]{AgroalDataSource.class},(proxy,method,args)->{
            try {
                Object value=method.invoke(datasource,args);if(!method.getName().equals("getConnection"))return value;var connection=(Connection)value;
                return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(ignored,operation,arguments)->{
                    try {
                        Object result=operation.invoke(connection,arguments);
                        if(operation.getName().equals("commit"))action.run();
                        return result;
                    }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        return guarded;
    }
    private static boolean allCleared(char[] phrase){for(char value:phrase)if(value!='\0')return false;return true;}
}
