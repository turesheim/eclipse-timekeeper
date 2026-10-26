package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest @TestProfile(JournalImportTest.Profile.class)
class JournalSynchronizationTest {
    @Inject ServerStore store;
    @Inject JournalSynchronization synchronization;
    @Inject JournalConfiguration configuration;
    @Inject AccountStore accounts;
    @Inject TokenRegistry tokens;
    @Inject ContractInputs inputs;
    @TempDir Path temporary;
    private Path root,file,incoming;
    private UUID device;
    private ObjectNode deployment;
    private static final String TOKEN="A".repeat(43),OTHER_TOKEN="B".repeat(43),NAME="2026-10-25.md";
    private static final String PASSWORD_HASH=AccountPasswords.hash("Journal synchronization fixture passphrase");
    @BeforeEach void setup()throws Exception{
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"));
        synchronization.stop();io.quarkus.arc.ClientProxy.unwrap(store).stopMaintenance();
        root=temporary.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        incoming=Files.createDirectory(root.resolve("incoming"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        deployment=object().put("format",1).put("hostname","ssh.example.invalid").put("port",2222).put("hostKey",JournalKeyTest.publicKey()).put("incomingRoot",incoming.toString()).put("encryptedStorage",true).put("backupKeysSeparated",true);
        deployment.putArray("accounts").add(object().put("ownerId","a").put("username","journal_a").put("brokerTokenSha256",hash(TOKEN)))
                .add(object().put("ownerId","b").put("username","journal_b").put("brokerTokenSha256",hash(OTHER_TOKEN)));
        file=Files.createFile(root.resolve("journal.json"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(file,deployment.toString());
        io.quarkus.arc.ClientProxy.unwrap(configuration).configurationFile=file.toString();
        for(String owner:List.of("a","b"))store.transaction(owner,()->{
            store.update("DELETE FROM tk_resource WHERE owner_id=? AND (entity_type LIKE 'JOURNAL_%' OR entity_type='DAY_NOTE')",owner);
            store.update("UPDATE tk_account SET username=?,password_hash=?,state='ENABLED' WHERE owner_id=?","journal-"+owner,PASSWORD_HASH,owner);new JournalDevices(store).enabled(true);return null;
        });
        store.transaction("a",()->{store.update("DELETE FROM tk_auth_throttle WHERE id IN (?,?)",hash("journal:proof:a"),hash("journal:proof:global"));return null;});
        String key=JournalKeyTest.publicKey();device=store.transaction("a",()->uuid(new JournalDevices(store).enroll("Laptop",key,null).get("id")));
    }
    @AfterEach void cleanup(){synchronization.stop();io.quarkus.arc.ClientProxy.unwrap(configuration).configurationFile="NONE";}
    private ObjectNode begin(){return synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->synchronization.begin(gateway,device));}
    private void upload(UUID lease,String name,String body){synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->synchronization.upload(gateway,lease,name,Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))));}
    private ObjectNode manifest(Map<String,String> bodies){
        var value=object().put("format",1).put("completed",true);var files=value.putArray("files");
        bodies.forEach((name,body)->{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);files.add(object().put("name",name).put("bytes",bytes.length).put("sha256",JournalMarkdown.hash(bytes)));});return value;
    }
    private ObjectNode complete(UUID id,ObjectNode manifest){return synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->synchronization.complete(gateway,id,manifest));}
    private String body(){return store.transaction("a",()->new DayNotes(store).get(DayNotes.date("2026-10-25")).path("body").asText());}
    @Test void onlyExplicitVerifiedCompletionMakesStagedFilesImportable()throws Exception{
        UUID id=uuid(begin().get("id"));String source="\r\n# Private Markdown  \r\n";upload(id,NAME,source);
        Path copy=incoming.resolve(hash("a")).resolve(id.toString()).resolve(NAME);assertEquals(source,Files.readString(copy));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(copy));assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(copy.getParent()));
        synchronization.rescan("a");assertEquals("",body());
        var receipt=complete(id,manifest(Map.of(NAME,source)));assertEquals("COMPLETED",receipt.path("status").asText());assertEquals("",body());
        synchronization.rescan("a");assertEquals(source,body());long version=store.transaction("a",()->new DayNotes(store).get(DayNotes.date("2026-10-25")).path("version").asLong());
        assertEquals(receipt.toString(),complete(id,manifest(Map.of(NAME,source))).toString());synchronization.rescan("a");assertEquals(version,store.transaction("a",()->new DayNotes(store).get(DayNotes.date("2026-10-25")).path("version").asLong()));
        assertFalse(Files.exists(copy.getParent()));
        assertEquals(409,assertThrows(ApiFailure.class,()->complete(id,manifest(Map.of(NAME,"changed after acceptance")))).status);
    }
    @Test void incompleteOrChangedManifestsCannotPartlyImport(){
        UUID id=uuid(begin().get("id"));upload(id,NAME,"one");var two=manifest(Map.of(NAME,"one","2026-10-26.md","two"));
        assertEquals(409,assertThrows(ApiFailure.class,()->complete(id,two)).status);synchronization.rescan("a");assertEquals("",body());
        upload(id,"2026-10-26.md","two");var wrong=manifest(Map.of(NAME,"changed","2026-10-26.md","two"));assertEquals(409,assertThrows(ApiFailure.class,()->complete(id,wrong)).status);
        assertEquals(409,assertThrows(ApiFailure.class,()->complete(id,manifest(Map.of(NAME,"one")))).status);
        complete(id,two);synchronization.rescan("a");assertEquals("one",body());
    }
    @Test void brokerScopeIsBoundToTrustedAccountAndCannotEnrollOrSelectOwners(){
        assertEquals(401,assertThrows(ApiFailure.class,()->synchronization.gateway("journal_b","JournalTransport "+TOKEN,gateway->synchronization.verify(gateway,device))).status);
        assertEquals(401,assertThrows(ApiFailure.class,()->synchronization.gateway("journal_a","Bearer "+TOKEN,gateway->synchronization.verify(gateway,device))).status);
        assertEquals(404,assertThrows(ApiFailure.class,()->synchronization.gateway("journal_b","JournalTransport "+OTHER_TOKEN,gateway->synchronization.verify(gateway,device))).status);
        UUID id=uuid(begin().get("id"));assertEquals(404,assertThrows(ApiFailure.class,()->synchronization.gateway("journal_b","JournalTransport "+OTHER_TOKEN,gateway->synchronization.heartbeat(gateway,id))).status);
    }
    @Test void unsafeNamesAndSymlinksCannotEscapeOrChangeNotes()throws Exception{
        UUID id=uuid(begin().get("id"));assertEquals(422,assertThrows(ApiFailure.class,()->upload(id,"../2026-10-25.md","escape")).status);
        Path privateTarget=root.resolve("private-file");Files.writeString(privateTarget,"unchanged");Path folder=incoming.resolve(hash("a")).resolve(id.toString());Files.createSymbolicLink(folder.resolve(NAME),privateTarget);
        assertEquals(503,assertThrows(ApiFailure.class,()->upload(id,NAME,"overwrite")).status);assertEquals("unchanged",Files.readString(privateTarget));assertEquals("",body());
        assertEquals(409,assertThrows(ApiFailure.class,()->complete(id,manifest(Map.of(NAME,"unchanged")))).status);
    }
    @Test void revokedAndDisabledCredentialsCannotCompleteInflightTransfersOrImportAcceptedBatches(){
        UUID id=uuid(begin().get("id"));upload(id,NAME,"pending");store.transaction("a",()->new JournalDevices(store).revoke(device));
        assertEquals(409,assertThrows(ApiFailure.class,()->complete(id,manifest(Map.of(NAME,"pending")))).status);assertEquals("",body());
    }
    @Test void changingSyncEpochAccountStateOrTrustedConfigurationInvalidatesPendingWork()throws Exception{
        UUID id=uuid(begin().get("id"));upload(id,NAME,"pending");complete(id,manifest(Map.of(NAME,"pending")));
        store.transaction("a",()->{new JournalDevices(store).enabled(false);new JournalDevices(store).enabled(true);return null;});synchronization.rescan("a");assertEquals("",body());
        UUID other=uuid(begin().get("id"));upload(other,NAME,"config");complete(other,manifest(Map.of(NAME,"config")));
        deployment.put("port",2223);Files.writeString(file,deployment.toString());synchronization.rescan("a");assertEquals("",body());
        UUID third=uuid(begin().get("id"));store.transaction("a",()->{store.update("UPDATE tk_account SET state='DISABLED',version=version+1 WHERE owner_id=?",store.owner());return null;});
        assertEquals(403,assertThrows(ApiFailure.class,()->synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->synchronization.heartbeat(gateway,third))).status);
    }
    @Test void receivingLeasesExpireAndConcurrentTransfersAreBounded(){
        UUID first=uuid(begin().get("id"));begin();assertEquals(429,assertThrows(ApiFailure.class,this::begin).status);
        store.transaction("a",()->{var state=new JournalState(store);state.update(JOURNAL_LEASE,state.required(JOURNAL_LEASE,first).put("expiresAt","2000-01-01T00:00:00Z"));return null;});
        assertEquals(409,assertThrows(ApiFailure.class,()->complete(first,manifest(Map.of()))).status);begin();
    }
    @Test void failedReceiversReleaseTheirSlotWithoutAdmittingOrCancellingCompletedNotes()throws Exception{
        UUID id=uuid(begin().get("id"));upload(id,NAME,"unacknowledged");
        assertEquals(404,assertThrows(ApiFailure.class,()->synchronization.gateway("journal_b","JournalTransport "+OTHER_TOKEN,gateway->synchronization.abort(gateway,id))).status);
        for(int retry=0;retry<2;retry++)assertEquals("ABORTED",synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->synchronization.abort(gateway,id)).path("status").asText());
        assertFalse(Files.exists(incoming.resolve(hash("a")).resolve(id.toString())));
        assertEquals(409,assertThrows(ApiFailure.class,()->complete(id,manifest(Map.of(NAME,"unacknowledged")))).status);assertEquals("",body());
        UUID completed=uuid(begin().get("id"));upload(completed,NAME,"accepted");complete(completed,manifest(Map.of(NAME,"accepted")));
        assertEquals("COMPLETED",synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->synchronization.abort(gateway,completed)).path("status").asText());
        synchronization.rescan("a");assertEquals("accepted",body());
    }
    @Test void internalHttpProtocolRejectsBrowserCredentialsOwnerInjectionAndUserApiTokens(){
        String base="/internal/journal/journal_a",credential="JournalTransport "+TOKEN;
        var input=object().put("deviceId",device.toString());
        given().contentType("application/json").body(input.toString()).post(base+"/verify").then().statusCode(401);
        given().header("Authorization","Bearer test-a").contentType("application/json").body(input.toString()).post(base+"/verify").then().statusCode(401);
        given().header("Authorization",credential).header("Cookie","private-cookie=value").contentType("application/json").body(input.toString()).post(base+"/verify").then().statusCode(400);
        given().header("Authorization",credential).header("Origin","https://example.invalid").contentType("application/json").body(input.toString()).post(base+"/verify").then().statusCode(400);
        given().header("Authorization",credential).contentType("application/json").body(input.deepCopy().put("ownerId","b").toString()).post(base+"/verify").then().statusCode(400);
        given().header("Authorization",credential).contentType("application/json").body(input.toString()).post(base+"/verify?ownerId=b").then().statusCode(400);
        given().header("Authorization",credential).get("/api/v1/day-notes/2026-10-25").then().statusCode(401);
        given().header("Authorization",credential).contentType("application/json").body(input.toString()).post(base+"/verify").then().statusCode(200).header("Cache-Control","private, no-store");
        var response=given().header("Authorization",credential).contentType("application/json").body(input.toString()).post(base+"/transfers").then().statusCode(200).extract().response();
        String transfer=base+"/transfers/"+response.jsonPath().getString("id"),body="private HTTP Markdown\r\n";
        given().header("Authorization",credential).contentType("application/json").body(object().put("data",Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))).toString()).put(transfer+"/files/"+NAME).then().statusCode(200);
        assertEquals("",body());
        given().header("Authorization",credential).contentType("application/json").body(manifest(Map.of(NAME,body)).toString()).post(transfer+"/complete").then().statusCode(200).header("Cache-Control","private, no-store");
        synchronization.rescan("a");assertEquals(body,body());
    }
    @Test void clientEnrollmentDefaultsToTwoWayAndRequiresDirectSignInAndCsrfAndKeepsSecretsOutOfStatusAndReceipts()throws Exception{
        var status=given().header("Authorization","Bearer test-read").get("/api/v1/journal").then().statusCode(200).header("Cache-Control","private, no-store").extract().response();
        inputs.validate("JournalStatus",parse(status.asString()));
        assertFalse(status.jsonPath().getBoolean("canManage"));assertFalse(status.asString().contains(TOKEN));assertFalse(status.asString().contains(incoming.toString()));
        var session=accounts.login("journal-a","Journal synchronization fixture passphrase",false);
        var password=object().put("currentPassword","Journal synchronization fixture passphrase");var settings=password.deepCopy().put("enabled",false);
        given().header("Authorization","Bearer test-a").header("If-Match",status.jsonPath().getString("settings.etag")).contentType("application/json").body(settings.toString()).put("/api/v1/journal/settings").then().statusCode(403);
        given().cookie(AuthenticationFilter.COOKIE,session.secret()).contentType("application/json").body(settings.toString()).put("/api/v1/journal/settings").then().statusCode(403);
        var disabled=given().cookie(AuthenticationFilter.COOKIE,session.secret()).header("X-CSRF-Token",session.csrf()).header("If-Match",status.jsonPath().getString("settings.etag")).contentType("application/json").body(settings.toString()).put("/api/v1/journal/settings").then().statusCode(200).extract().response();
        assertFalse(disabled.jsonPath().getBoolean("settings.enabled"));
        String key=UUID.randomUUID().toString();var enrollment=object().put("label","New laptop").put("publicKey",JournalKeyTest.publicKey());
        given().header("Authorization","Bearer test-a").header("Idempotency-Key",key).contentType("application/json").body(enrollment.toString()).post("/api/v1/journal/devices").then().statusCode(403);
        given().cookie(AuthenticationFilter.COOKIE,session.secret()).header("Idempotency-Key",key).contentType("application/json").body(enrollment.toString()).post("/api/v1/journal/devices").then().statusCode(403);
        var derived=accounts.exchange(tokens.authenticate("Bearer test-a"));
        given().cookie(AuthenticationFilter.COOKIE,derived.secret()).header("X-CSRF-Token",derived.csrf()).header("Idempotency-Key",key).contentType("application/json").body(enrollment.toString()).post("/api/v1/journal/devices").then().statusCode(403);
        var enrolled=given().cookie(AuthenticationFilter.COOKIE,session.secret()).header("X-CSRF-Token",session.csrf()).header("Idempotency-Key",key).contentType("application/json").body(enrollment.toString()).post("/api/v1/journal/devices").then().statusCode(201).extract().response();
        var replay=given().cookie(AuthenticationFilter.COOKIE,session.secret()).header("X-CSRF-Token",session.csrf()).header("Idempotency-Key",key).contentType("application/json").body(enrollment.toString()).post("/api/v1/journal/devices").then().statusCode(201).extract().response();assertEquals(enrolled.asString(),replay.asString());
        String enrolledId=enrolled.jsonPath().getString("id");assertTrue(enrolled.jsonPath().getBoolean("bidirectional"));
        store.transaction("a",()->{var devices=new JournalDevices(store);assertTrue(devices.settings().path("enabled").asBoolean());assertTrue(devices.settings().path("bidirectional").asBoolean());assertFalse(devices.list().stream().filter(value->value.path("id").asText().equals(device.toString())).findFirst().orElseThrow().path("bidirectional").asBoolean());return null;});
        var profile=given().cookie(AuthenticationFilter.COOKIE,session.secret()).get("/api/v1/journal/devices/"+enrolledId+"/profile").then().statusCode(200).extract().response();assertEquals("ssh.example.invalid",profile.jsonPath().getString("hostname"));assertEquals(2222,profile.jsonPath().getInt("port"));assertFalse(profile.asString().contains(TOKEN));assertFalse(profile.asString().contains("ownerId"));
        assertEquals(2,profile.jsonPath().getInt("format"));assertEquals("BIDIRECTIONAL",profile.jsonPath().getString("direction"));inputs.validate("JournalProfile",parse(profile.asString()));
        var legacy=object().put("label","Legacy upload client").put("publicKey",JournalKeyTest.publicKey()).put("bidirectional",false);
        given().cookie(AuthenticationFilter.COOKIE,session.secret()).header("X-CSRF-Token",session.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").body(legacy.toString()).post("/api/v1/journal/devices").then().statusCode(201).body("bidirectional",org.hamcrest.Matchers.is(false));
        assertEquals("JOURNAL_DEVICE_WRITE_ONLY",assertThrows(ApiFailure.class,()->store.transaction("a",()->new JournalDevices(store).authorizeBidirectional(device))).code);
        given().header("Authorization","Bearer test-b").get("/api/v1/journal/devices/"+enrolledId+"/profile").then().statusCode(404);
        store.transaction("a",()->{try(var statement=store.statement("SELECT body FROM tk_receipt WHERE owner_id=? AND command_id=?",store.owner(),UUID.fromString(key));var rows=statement.executeQuery()){assertTrue(rows.next());assertFalse(rows.getString(1).contains("fixture passphrase"));}catch(java.sql.SQLException failure){throw new AssertionError(failure);}return null;});
        given().cookie(AuthenticationFilter.COOKIE,session.secret()).header("X-CSRF-Token",session.csrf()).contentType("application/json").body(password.toString()).post("/api/v1/journal/devices/"+enrolledId+"/revoke").then().statusCode(200);
        given().cookie(AuthenticationFilter.COOKIE,session.secret()).get("/api/v1/journal/devices/"+enrolledId+"/profile").then().statusCode(403);
    }
    @Test void candidateReviewResolutionAndOriginalDownloadAreOwnedConditionalAndPrivate(){
        String original="---\nunknown: retained\n---\nprivate incoming";
        store.transaction("a",()->{var notes=new DayNotes(store);var day=DayNotes.date("2026-10-25");return notes.save(day,etag(notes.get(day)),object().put("body","calendar"));});
        UUID id=uuid(begin().get("id"));upload(id,NAME,original);complete(id,manifest(Map.of(NAME,original)));synchronization.rescan("a");
        var status=given().header("Authorization","Bearer test-a").get("/api/v1/journal").then().statusCode(200).extract().response();assertEquals(1,status.jsonPath().getInt("health.conflicts"));assertFalse(status.asString().contains("private incoming"));
        String item=status.jsonPath().getString("items[0].id"),path="/api/v1/journal/items/"+item;
        var review=given().header("Authorization","Bearer test-read").get(path).then().statusCode(200).header("Cache-Control","private, no-store").extract().response();assertEquals("private incoming",review.jsonPath().getString("body"));
        inputs.validate("JournalCandidate",parse(review.asString()));
        given().header("Authorization","Bearer test-b").get(path).then().statusCode(404);
        String source=given().header("Authorization","Bearer test-read").get(path+"/source").then().statusCode(200).header("Cache-Control","private, no-store").contentType("application/octet-stream").extract().asString();assertEquals(original,source);
        var resolution=object().put("action","INCOMING").put("currentEtag",review.jsonPath().getString("currentEtag"));
        given().header("Authorization","Bearer test-read").header("If-Match",review.jsonPath().getString("candidateEtag")).contentType("application/json").body(resolution.toString()).post(path+"/resolve").then().statusCode(403);
        given().header("Authorization","Bearer test-a").header("If-Match",review.jsonPath().getString("candidateEtag")).contentType("application/json").body(resolution.toString()).post(path+"/resolve").then().statusCode(200);assertEquals("private incoming",body());
        given().header("Authorization","Bearer test-a").get(path+"/source").then().statusCode(404);
    }
    @Test void completedCopiesAreReleasedWhileDurablePendingInputStillImports(){
        UUID first=uuid(begin().get("id"));upload(first,NAME,"durable");complete(first,manifest(Map.of(NAME,"durable")));
        Path folder=incoming.resolve(hash("a")).resolve(first.toString());assertTrue(Files.exists(folder));begin();assertFalse(Files.exists(folder));assertEquals("",body());synchronization.rescan("a");assertEquals("durable",body());
        assertEquals("COMPLETED",complete(first,manifest(Map.of(NAME,"durable"))).path("status").asText());
    }
    @Test void historyPruningNeverDiscardsAnUnresolvedCandidate(){
        UUID transfer=uuid(begin().get("id"));upload(transfer,NAME,"retained source");complete(transfer,manifest(Map.of(NAME,"retained source")));
        store.transaction("a",()->{
            var state=new JournalState(store);for(int index=0;index<205;index++)state.insert(JOURNAL_ITEM,UUID.randomUUID(),object().put("batchId",transfer.toString()).put("deviceId",device.toString()).put("sequence",index+2).put("filename","2026-10-26.md").put("state","UNCHANGED").put("processedAt",store.now().minusSeconds(1000-index).toString()));
            new JournalRetention(store).cleanup(io.quarkus.arc.ClientProxy.unwrap(configuration).load());
            assertEquals(201,state.list(JOURNAL_ITEM).size());assertEquals(1,state.list(JOURNAL_SOURCE).size());assertEquals(1,new JournalImport(store).pending().size());return null;
        });synchronization.rescan("a");assertEquals("retained source",body());
    }
    @Test void localFileErrorsDoNotBlockIndependentNotesAndSettlingDrainsTheBatch(){
        UUID id=uuid(begin().get("id"));upload(id,NAME,"INDEPENDENT_NOTE_BODY_MARKER");var manifest=manifest(Map.of(NAME,"INDEPENDENT_NOTE_BODY_MARKER"));
        manifest.putArray("errors").add(object().put("name","2026-10-26.md").put("code","FILE_TOO_LARGE"));complete(id,manifest);
        var result=synchronization.settle("journal_a","JournalTransport "+TOKEN,id);assertEquals(0,result.path("pending").asInt());assertEquals(1,result.path("errors").asInt());assertEquals("INDEPENDENT_NOTE_BODY_MARKER",body());
        var status=given().header("Authorization","Bearer test-a").get("/api/v1/journal").then().statusCode(200).extract().response();assertEquals(1,status.jsonPath().getInt("health.errors"));assertFalse(status.asString().contains("INDEPENDENT_NOTE_BODY_MARKER"));
    }
    private void bidirectional()throws Exception{
        String key=JournalKeyTest.publicKey();device=store.transaction("a",()->{var devices=new JournalDevices(store);devices.configure(true,true);return uuid(devices.enroll("Two directions",key,device,true).get("id"));});
    }
    private ObjectNode beginExchange(UUID exchange){return synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->synchronization.begin(gateway,device,exchange));}
    private ObjectNode exchangeManifest(UUID exchange,UUID client,Map<String,String> files){
        var value=manifest(files).put("format",2).put("exchangeId",exchange.toString()).put("clientStateId",client.toString());value.putArray("absent");value.putArray("errors");
        value.path("files").forEach(file->((ObjectNode)file).put("baseToken",""));return value;
    }
    private ObjectNode results(UUID exchange){return synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->new JournalExchanges(store).results(device,exchange,gateway.deployment().epoch()));}
    @Test void bidirectionalBatchesNeedCompletionAndProduceDurablePrivateOffersWithIdempotentReceipts()throws Exception{
        bidirectional();UUID exchange=UUID.randomUUID(),client=UUID.randomUUID();UUID id=uuid(beginExchange(exchange).get("id"));String input="---\r\ncustom: [one, two]\r\n---\r\n# Exact  \r\n";
        upload(id,NAME,input);synchronization.rescan("a");assertEquals("",body());assertThrows(ApiFailure.class,()->results(exchange));
        var manifest=exchangeManifest(exchange,client,Map.of(NAME,input));complete(id,manifest);assertEquals("",body());synchronization.settle("journal_a","JournalTransport "+TOKEN,id);
        var result=results(exchange);assertEquals(0,result.path("pending").asInt());assertEquals(1,result.path("offers").size());var offer=result.path("offers").get(0);UUID offered=uuid(offer.get("id"));
        assertFalse(result.toString().contains("# Exact"));assertEquals("# Exact  \r\n",body());
        byte[] output=synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->new JournalExchanges(store).outbound(device,exchange,offered,gateway.deployment().epoch()));assertEquals(input,new String(output,StandardCharsets.UTF_8));
        assertEquals(id,uuid(beginExchange(exchange).get("id")));assertEquals("COMPLETED",complete(id,manifest).path("status").asText());
        var receipt=synchronization.gateway("journal_a","JournalTransport "+TOKEN,gateway->new JournalReconciliation(store).acknowledge(offered,device,offer.path("token").asText(),true,offer.path("targetHash").asText(),gateway.deployment().epoch()));assertEquals("ACKNOWLEDGED",receipt.path("state").asText());
        assertEquals(1,store.transaction("a",()->new DayNotes(store).get(DayNotes.date("2026-10-25")).path("version").asLong()));
        UUID next=UUID.randomUUID();UUID second=uuid(beginExchange(next).get("id"));var deleted=exchangeManifest(next,client,Map.of());deleted.withArray("absent").add(object().put("name",NAME).put("baseToken",offer.path("token").asText()));
        complete(second,deleted);synchronization.settle("journal_a","JournalTransport "+TOKEN,second);assertTrue(store.transaction("a",()->new DayNotes(store).get(DayNotes.date("2026-10-25")).path("deleted").asBoolean()));assertFalse(results(next).path("offers").get(0).path("targetExists").asBoolean());
    }
    @Test void bidirectionalInternalHttpScopesOutboundDataToTheForcedDeviceAndRejectsWriteOnlyKeys()throws Exception{
        String prefix="/internal/journal/journal_a",credential="JournalTransport "+TOKEN;UUID exchange=UUID.randomUUID();
        given().header("Authorization",credential).contentType("application/json").body(object().put("deviceId",device.toString()).toString()).post(prefix+"/exchanges/inventory").then().statusCode(403);
        given().header("Authorization",credential).contentType("application/json").body(object().put("deviceId",device.toString()).put("exchangeId",exchange.toString()).toString()).post(prefix+"/transfers").then().statusCode(403);
        bidirectional();UUID id=uuid(beginExchange(exchange).get("id"));String input="PRIVATE_BIDIRECTIONAL_SOURCE_MARKER";upload(id,NAME,input);complete(id,exchangeManifest(exchange,UUID.randomUUID(),Map.of(NAME,input)));synchronization.settle("journal_a",credential,id);
        var offer=results(exchange).path("offers").get(0);String path="/exchanges/"+exchange+"/files/"+offer.path("id").asText(),body=object().put("deviceId",device.toString()).toString();
        given().header("Authorization",credential).contentType("application/json").body(body).post(prefix+path).then().statusCode(200).header("Cache-Control","private, no-store").contentType("application/octet-stream");
        given().header("Authorization","JournalTransport "+OTHER_TOKEN).contentType("application/json").body(body).post("/internal/journal/journal_b"+path).then().statusCode(404);
        given().header("Authorization","Bearer test-a").contentType("application/json").body(body).post(prefix+path).then().statusCode(401);
        given().header("Authorization",credential).header("Origin","https://example.invalid").contentType("application/json").body(body).post(prefix+path).then().statusCode(400);
        store.transaction("a",()->new JournalDevices(store).configure(true,false));given().header("Authorization",credential).contentType("application/json").body(body).post(prefix+path).then().statusCode(403);
    }
    @Test void malformedBidirectionalFilesDoNotBlockIndependentOffersAndUntrackedAbsenceDoesNotDelete()throws Exception{
        bidirectional();store.transaction("a",()->{var notes=new DayNotes(store);var day=DayNotes.date("2026-10-27");return notes.save(day,etag(notes.get(day)),object().put("body","Calendar-only file"));});
        UUID exchange=UUID.randomUUID(),id=uuid(beginExchange(exchange).get("id"));Map<String,String> files=Map.of(NAME,"---\ninvalid: [\n---\n","2026-10-26.md","Independent valid file");files.forEach((name,data)->upload(id,name,data));
        var manifest=exchangeManifest(exchange,UUID.randomUUID(),files);manifest.withArray("absent").add(object().put("name","2026-10-27.md").put("baseToken",""));complete(id,manifest);synchronization.settle("journal_a","JournalTransport "+TOKEN,id);
        var result=results(exchange);assertEquals(1,result.path("errors").size());assertEquals("INVALID_YAML",result.path("errors").get(0).path("code").asText());assertEquals(2,result.path("offers").size());
        assertEquals("Calendar-only file",store.transaction("a",()->new DayNotes(store).get(DayNotes.date("2026-10-27")).path("body").asText()));assertEquals("Independent valid file",store.transaction("a",()->new DayNotes(store).get(DayNotes.date("2026-10-26")).path("body").asText()));
    }
}
