package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipFile;
import com.fasterxml.jackson.core.JsonToken;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;

@QuarkusTest @TestProfile(AccountArchiveTest.Profile.class)
class AccountArchiveTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return ArchiveTestDatabase.configuration("export","jdbc:h2:mem:account_archive_tests");}
    }
    @Inject AccountArchive archives;
    @Inject ServerStore store;
    @Inject TokenRegistry tokens;
    @Inject ContractInputs inputs;
    @Inject AccountStore accounts;
    @Inject io.agroal.api.AgroalDataSource datasource;
    @io.quarkus.test.common.http.TestHTTPResource java.net.URI server;

    /** Commit an authentication change after the archive's MVCC snapshot starts. */
    private AccountArchive racingArchive(String owner,Runnable change,java.util.concurrent.atomic.AtomicInteger attempts) {
        var archive=new AccountArchive();archive.accounts=accounts;archive.tokens=tokens;archive.restoreFence=io.quarkus.arc.ClientProxy.unwrap(archives).restoreFence;
        archive.datasource=(io.agroal.api.AgroalDataSource)java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),new Class<?>[]{io.agroal.api.AgroalDataSource.class},(proxy,method,args)->{
                try {
                    Object result=method.invoke(datasource,args);
                    if(!method.getName().equals("getConnection")) return result;
                    var connection=(java.sql.Connection)result;
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(ignored,operation,values)->{
                        try {
                            Object value=operation.invoke(connection,values);
                            if(operation.getName().equals("setReadOnly") && Boolean.TRUE.equals(values[0]) && attempts.incrementAndGet()==1) {
                                try(var query=AccountStore.query(connection,"SELECT revision FROM tk_owner WHERE id=?",owner);var rows=query.executeQuery()) {assertTrue(rows.next());}
                                change.run();
                            }
                            return value;
                        }catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
                    });
                }catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
            });
        return archive;
    }
    @Test void concurrentAuthenticationTouchDoesNotBlockOrRestartTheSnapshot() throws Exception {
        var principal=tokens.authenticate("Bearer test-read");var attempts=new java.util.concurrent.atomic.AtomicInteger();
        var archive=racingArchive(principal.owner(),()->accounts.tx(c->{accounts.guard(c,principal);return null;}),attempts);
        try(var download=archive.download(principal)) {
            assertEquals(1,attempts.get());
            assertEquals(principal.owner(),AccountArchiveInput.read(download.file(),"application/zip").path("account").path("ownerId").asText());
        }
    }
    @Test void revocationDuringPreparationMustNotReturnPrivateData() {
        var principal=tokens.authenticate("Bearer test-b");var attempts=new java.util.concurrent.atomic.AtomicInteger();
        var archive=racingArchive(principal.owner(),()->accounts.tx(c->{AccountStore.execute(c,"UPDATE tk_api_token SET revoked=TRUE WHERE digest=?",principal.digest());return null;}),attempts);
        try {
            assertEquals(401,assertThrows(ApiFailure.class,()->archive.download(principal)).status);assertEquals(1,attempts.get());
        } finally {accounts.tx(c->{AccountStore.execute(c,"UPDATE tk_api_token SET revoked=FALSE WHERE digest=?",principal.digest());return null;});}
    }

    @Test void aLongPreparationLeavesAccountReadsAndReadinessAvailableAndRejectsAnotherPreparation()throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var principal=tokens.authenticate("Bearer test-read");
        var archive=racingArchive(principal.owner(),()->{
            entered.countDown();try{assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new AssertionError(stopped);}
        },new java.util.concurrent.atomic.AtomicInteger());
        try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var pending=worker.submit(()->archive.download(principal));
            try{
                assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
                var http=java.net.http.HttpClient.newHttpClient();
                for(String path:List.of("/api/v1/me","/q/health/ready")){
                    var request=java.net.http.HttpRequest.newBuilder(server.resolve(path)).timeout(java.time.Duration.ofSeconds(3)).header("Authorization","Bearer test-read").GET().build();
                    assertEquals(200,http.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
                }
                var busy=assertThrows(ApiFailure.class,()->archive.notesDownload(principal));assertEquals(503,busy.status);assertEquals("ACCOUNT_ARCHIVE_BUSY",busy.code);
            }finally{release.countDown();}
            try(var result=pending.get(15,java.util.concurrent.TimeUnit.SECONDS)){assertTrue(result.length()>0);}
            try(var result=archive.download(principal)){assertTrue(result.length()>0);}
        }
    }
    @Test void aFailedPreparationCleansPrivateOutputAndReleasesCapacity()throws Exception {
        var principal=tokens.authenticate("Bearer test-read");var archive=racingArchive(principal.owner(),()->{throw new AssertionError("Synthetic interrupted preparation");},new java.util.concurrent.atomic.AtomicInteger());
        var root=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));Set<java.nio.file.Path> before;
        try(var files=Files.list(root)){before=files.filter(path->path.getFileName().toString().startsWith("timekeeper-account-download-")).collect(java.util.stream.Collectors.toSet());}
        assertThrows(AssertionError.class,()->archive.download(principal));
        try(var files=Files.list(root)){assertEquals(before,files.filter(path->path.getFileName().toString().startsWith("timekeeper-account-download-")).collect(java.util.stream.Collectors.toSet()));}
        try(var result=archive.download(principal)){assertTrue(result.length()>0);}
    }

    @Test void administratorDownloadIsPrivateAndManagementMetadataContainsNoCredentials() throws Exception {
        var alice=tokens.authenticate("Bearer test-a");tokens.authenticate("Bearer test-b");
        store.transaction("a",()->{
            store.service().createProject(new CreateProject("=Private formula name"));
            store.updateProfile("Europe/Oslo");store.update("UPDATE tk_account SET display_name='Alice',administrator=TRUE WHERE owner_id=?",store.owner());
            store.update("INSERT INTO tk_recovery_address VALUES(?,'BACKUP',?,NULL,0)",store.owner(),"alice-backup@example.invalid");
            store.update("INSERT INTO tk_passkey_user VALUES(?,?)",store.owner(),"excluded-opaque-handle");
            store.update("INSERT INTO tk_passkey_credential VALUES(?,?,?,?,?,?,0,FALSE,FALSE,'[]',?,?,NULL,FALSE,0)",UUID.randomUUID(),store.owner(),"1".repeat(64),"excluded-credential-id","example.invalid","excluded-public-key","Private device","2026-01-01T00:00:00Z");
            new ImportLedger(store).create("Private import audit",hash("source"),"Europe/Oslo",hash("file"),hash("plan"));return null;
        });
        store.transaction("b",()->{
            store.service().createProject(new CreateProject("OTHER ACCOUNT MUST STAY PRIVATE"));
            store.update("INSERT INTO tk_recovery_address VALUES(?,'PRIMARY',?,NULL,1)",store.owner(),"other-private@example.invalid");return null;
        });
        store.transaction("a",()->{
            store.update("UPDATE tk_service_settings SET saved_smtp_password=?,effective_smtp_password=? WHERE id=1","excluded-global-smtp-secret","excluded-global-smtp-secret");return null;
        });
        java.nio.file.Path path;
        try(var download=archives.download(alice)) {
            path=download.file();assertTrue(download.length()>0);
            try(var zip=new ZipFile(path.toFile())) {
                String text=new String(zip.getInputStream(zip.getEntry("archive.json")).readAllBytes(),StandardCharsets.UTF_8);var value=parse(text);
                assertEquals(value,AccountArchiveInput.read(path,"application/zip"));
                assertDoesNotThrow(()->inputs.validate("AccountArchiveSnapshot",value));
                assertEquals(AccountArchive.FORMAT,value.path("format").asText());assertEquals("Europe/Oslo",value.path("preferences").path("zoneId").asText());
                assertTrue(value.path("account").path("administrator").asBoolean());assertEquals("Alice",value.path("account").path("displayName").asText());
                assertEquals("alice-backup@example.invalid",value.path("account").path("recoveryAddresses").get(0).path("verifiedAddress").asText());
                assertEquals("Private device",value.path("account").path("passkeyMetadata").get(0).path("name").asText());
                assertTrue(value.path("importHistory").size()>0);assertTrue(text.contains("Private import audit"));
                for(String secret:List.of("OTHER ACCOUNT MUST STAY PRIVATE","other-private@example.invalid","excluded-global-smtp-secret","excluded-opaque-handle","excluded-credential-id","excluded-public-key","1".repeat(64),alice.digest(),alice.legacyDigest())) assertFalse(text.contains(secret));
                for(String field:List.of("password_hash","public_key","credential_id","user_handle","csrf","smtpPassword","effectiveSmtpPassword","serviceSettings")) assertFalse(text.contains('"'+field+'"'));
                assertTrue(new String(zip.getInputStream(zip.getEntry("projects.csv")).readAllBytes(),StandardCharsets.UTF_8).contains("'=Private formula name"));
                assertTrue(text.contains("=Private formula name"));
            }
        }
        assertFalse(Files.exists(path));assertFalse(Files.exists(path.getParent()));
    }
    @Test void taskPlanningIsPresentInPortableJsonAndCsv() throws Exception {
        var principal=tokens.authenticate("Bearer test-read");
        var planning=new net.resheim.eclipse.timekeeper.domain.TaskPlanning(net.resheim.eclipse.timekeeper.domain.TaskStatus.COMPLETED,
                Optional.of(java.time.LocalDate.parse("2026-10-26")),Optional.of(java.time.Instant.parse("2026-10-26T11:12:13Z")));
        var checklist=new net.resheim.eclipse.timekeeper.domain.TaskChecklist(List.of(
                new net.resheim.eclipse.timekeeper.domain.TaskChecklistItem(UUID.randomUUID(),"Review archive",true,
                        Optional.of(java.time.Instant.parse("2026-10-26T11:12:13Z")),0)));
        var timing=new net.resheim.eclipse.timekeeper.domain.TaskTiming(
                Optional.of(net.resheim.eclipse.timekeeper.domain.TaskMoment.dateOnly(java.time.LocalDate.parse("2026-10-26"))),
                Optional.of(net.resheim.eclipse.timekeeper.domain.TaskMoment.zoned(java.time.LocalDate.parse("2026-10-27"),
                        java.time.LocalTime.parse("09:15"),"Pacific Standard Time")));
        var importance=new net.resheim.eclipse.timekeeper.domain.TaskImportance(
                net.resheim.eclipse.timekeeper.domain.TaskPriority.HIGH,true,true);
        var task=store.transaction(principal.owner(),()->store.service().createTask(new net.resheim.eclipse.timekeeper.service.Commands.CreateTask(
                Optional.empty(),Optional.empty(),"Portable scheduled task",Optional.empty(),Set.of(),planning,checklist,timing,importance)));
        try(var download=archives.download(principal);var zip=new ZipFile(download.file().toFile())) {
            var archive=parse(new String(zip.getInputStream(zip.getEntry("archive.json")).readAllBytes(),StandardCharsets.UTF_8));
            var restored=java.util.stream.StreamSupport.stream(archive.path("work").path("tasks").spliterator(),false)
                    .filter(value->value.path("id").asText().equals(task.id().value().toString())).findFirst().orElseThrow();
            assertEquals("COMPLETED",restored.path("status").asText());assertEquals("2026-10-26",restored.path("scheduledDate").asText());
            assertEquals("2026-10-26T11:12:13Z",restored.path("completedAt").asText());
            assertEquals(checklist.items().get(0).id().toString(),restored.path("checklistItems").get(0).path("id").asText());
            assertEquals("DATE",restored.path("start").path("kind").asText());
            assertEquals("Pacific Standard Time",restored.path("due").path("timeZone").asText());
            assertEquals("HIGH",restored.path("priority").asText());assertTrue(restored.path("flagged").asBoolean());assertTrue(restored.path("urgent").asBoolean());
            String csv=new String(zip.getInputStream(zip.getEntry("tasks.csv")).readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(csv.contains("scheduledDate"));assertTrue(csv.contains("2026-10-26T11:12:13Z"));assertTrue(csv.contains("Review archive"));assertTrue(csv.contains("Pacific Standard Time"));assertTrue(csv.contains("flagged"));
        }
    }
    @Test void downloadIncludesMoreThanTenThousandRecordsAndTwentyMebibytesWithoutPagination() throws Exception {
        var principal=tokens.authenticate("Bearer test-read");int count=11001;
        var actual=io.quarkus.arc.ClientProxy.unwrap(store);actual.stopMaintenance();
        store.transaction("a",()->{
            try(var insert=store.statement("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,'TASK',?,0,?)")) {
                for(int i=0;i<count;i++) {
                    UUID id=UUID.randomUUID();var task=object().put("id",id.toString()).put("ownerId",store.owner()).put("version",0).put("summary","A".repeat(1000)).put("url","https://example.invalid/"+"b".repeat(3900));
                    task.putNull("projectId");task.putNull("parentTaskId");task.putArray("externalReferences");
                    insert.setString(1,store.owner());insert.setObject(2,id);insert.setString(3,task.toString());insert.addBatch();if(i%256==255) insert.executeBatch();
                }insert.executeBatch();return null;
            }catch(java.sql.SQLException failure) {throw new RuntimeException(failure);}
        });
        try(var download=archives.download(principal);var zip=new ZipFile(download.file().toFile())) {
            var manifest=parse(new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(count,manifest.path("counts").path("tasks").asInt());assertTrue(zip.getEntry("archive.json").getSize()>20L*1024*1024);
            int observed=0;
            try(var parser=MAPPER.getFactory().createParser(zip.getInputStream(zip.getEntry("archive.json")))) {
                while(parser.nextToken()!=null) if(parser.currentToken()==JsonToken.FIELD_NAME && parser.currentName().equals("tasks")) {
                    assertEquals(JsonToken.START_ARRAY,parser.nextToken());while(parser.nextToken()!=JsonToken.END_ARRAY) {observed++;parser.skipChildren();}
                }
            }
            assertEquals(count,observed);
            try(var reader=new BufferedReader(new InputStreamReader(zip.getInputStream(zip.getEntry("tasks.csv")),StandardCharsets.UTF_8))) {assertEquals(count+1,reader.lines().count());}
        }
    }
    @Test void spreadsheetProtectionCoversWhitespaceControlsAndQuoting() {
        for(String value:List.of("=1+1","+cmd","-5","@SUM(A1)","  =A1","\tformula","\rformula","\nformula","\ufeff=SUM(A1)","\u00a0+cmd")) assertTrue(AccountArchive.csvCell(value).startsWith("\"'"));
        assertEquals("\"ordinary, \"\"quoted\"\"\"",AccountArchive.csvCell("ordinary, \"quoted\""));
    }
}
