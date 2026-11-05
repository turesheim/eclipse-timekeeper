package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(VaultImportExportTest.Profile.class)
class VaultImportExportTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return ArchiveTestDatabase.configuration("notesexport","jdbc:h2:mem:notes_export_test");}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject AccountArchive archives;
    @Inject TokenRegistry tokens;
    private static final String CREATED="2020-01-02T03:04:05.123456789Z",MODIFIED="2021-02-03T04:05:06.987654321Z";
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void originalDatesSurviveAnEditAndJournalImportDoesNotChangeTheLabel(){
        store.transaction("import-dates",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();var dates=VaultImportDates.parse(CREATED,MODIFIED);
            var folder=dates.apply(store,vault.mkdir(new VaultPath("Notes")));assertEquals(MODIFIED,folder.path("modifiedAt").asText());
            var file=dates.apply(store,vault.put(new VaultPath("Notes/Exact.md"),null,true,bytes("Original\r\n")));
            assertEquals(CREATED,file.path("createdAt").asText());assertEquals(MODIFIED,file.path("modifiedAt").asText());assertEquals(1,file.path("contentVersion").asLong());
            assertEquals(412,assertThrows(ApiFailure.class,()->dates.apply(store,vault.put(new VaultPath("Notes/Exact.md"),null,true,bytes("Wrong")))).status);
            assertEquals(MODIFIED,vault.get(VaultCollection.id(file)).path("modifiedAt").asText());
            file=vault.put(new VaultPath("Notes/Exact.md"),vault.tag(file),false,bytes("Edited"));assertEquals(CREATED,file.path("createdAt").asText());assertTrue(Instant.parse(file.path("modifiedAt").asText()).isAfter(Instant.parse(MODIFIED)));
            var day=DayNotes.date("2026-11-04");var labels=new DayLabels(store);labels.save(day,etag(labels.get(day)),"Independent label");
            var journal=dates.apply(store,vault.put(new VaultPath("journal/2026-11-04.md"),null,true,bytes("- [ ] Exact task\r\n")));
            assertEquals(MODIFIED,new DayNotes(store).modifiedAt(day).toString());assertEquals(CREATED,journal.path("createdAt").asText());assertEquals("Independent label",labels.get(day).path("text").asText());return null;
        });
    }
    @Test void privateZipHasPortableExactFilesDatesAndEmptyFoldersAndExcludesTrashAndOtherOwners()throws Exception{
        var principal=tokens.authenticate("Bearer test-a");tokens.authenticate("Bearer test-b");
        byte[] document=bytes("---\r\nunknown: [x, true]\r\n---\r\n[[Note]]  \r\n"),binary={0,(byte)255,4};
        var event=new EventNotes.Key(UUID.randomUUID(),"cd".repeat(32));
        var eventValue=object().put("subscriptionId",event.subscription().toString()).put("eventKey",event.event()).put("body","Event body\r\n");eventValue.set("event",object().put("uid","event-uid").put("recurrenceId","2026-11-04T10:00:00Z").put("title","Meeting"));byte[] eventBytes=EventNotes.document(eventValue);
        store.transaction("a",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();vault.mkdir(new VaultPath("Export"));vault.mkdir(new VaultPath("Export/Empty"));
            VaultImportDates.parse(CREATED,MODIFIED).apply(store,vault.put(new VaultPath("Export/Ærlig.md"),null,true,document));
            vault.put(new VaultPath("Export/image.bin"),null,true,binary);vault.put(new VaultPath("journal/2026-W45.md"),null,true,bytes("Week\r\n"));
            vault.put(new VaultPath("journal/2026-11-03.md"),null,true,document);new JournalAssets(store).save("export.bin",null,binary);
            var events=new EventNotes(store);events.saveDocument(event,etag(events.get(event)),eventBytes);
            var trash=vault.put(new VaultPath("Export/Removed.md"),null,true,bytes("Deleted secret"));vault.delete(trash,vault.tag(trash));
            vault.configure(etag(new VaultSettings(store).get()),object().put("journalFolder","Private/Journal").put("eventsFolder","Meetings").put("hideJournalFolder",true));return null;
        });
        store.transaction("b",()->{var vault=new VaultCollection(store,limits);vault.initialize();vault.put(new VaultPath("Other-owner.md"),null,true,bytes("Other owner secret"));return null;});
        java.nio.file.Path temporary;
        try(var download=archives.notesDownload(principal);var zip=new ZipFile(download.file().toFile())){
            temporary=download.file();assertEquals(download.length(),java.nio.file.Files.size(temporary));
            assertArrayEquals(document,zip.getInputStream(zip.getEntry("Export/Ærlig.md")).readAllBytes());assertArrayEquals(binary,zip.getInputStream(zip.getEntry("Export/image.bin")).readAllBytes());
            assertNotNull(zip.getEntry("Export/Empty/"));assertNotNull(zip.getEntry("Private/Journal/2026-W45.md"));assertNotNull(zip.getEntry("Private/Journal/2026-11-03.md"));assertNotNull(zip.getEntry("Private/Journal/assets/export.bin"));
            assertArrayEquals(eventBytes,zip.getInputStream(zip.getEntry("Meetings/"+event.filename())).readAllBytes());
            assertNull(zip.getEntry("Export/Removed.md"));assertNull(zip.getEntry("Other-owner.md"));assertNull(zip.getEntry("archive.json"));
            var manifest=parse(new String(zip.getInputStream(zip.getEntry(VaultExport.MANIFEST)).readAllBytes(),StandardCharsets.UTF_8));assertEquals(VaultExport.FORMAT,manifest.path("format").asText());
            var metadata=java.util.stream.StreamSupport.stream(manifest.path("entries").spliterator(),false).filter(row->row.path("path").asText().equals("Export/Ærlig.md")).findFirst().orElseThrow();
            assertEquals(CREATED,metadata.path("createdAt").asText());assertEquals(MODIFIED,metadata.path("modifiedAt").asText());assertEquals(ArchiveJournalFiles.digest(document),metadata.path("sha256").asText());
            assertEquals(Instant.parse(MODIFIED).getEpochSecond(),zip.getEntry("Export/Ærlig.md").getLastModifiedTime().toInstant().getEpochSecond());assertFalse(manifest.toString().contains("ownerId"));
        }
        assertFalse(java.nio.file.Files.exists(temporary));
        store.transaction("a",()->{var vault=new VaultCollection(store,limits);vault.put(new VaultPath(VaultExport.MANIFEST),null,true,bytes("A real user file"));return null;});
        try(var download=archives.notesDownload(principal);var zip=new ZipFile(download.file().toFile())){
            assertArrayEquals(bytes("A real user file"),zip.getInputStream(zip.getEntry(VaultExport.MANIFEST)).readAllBytes());
            var metadata=zip.stream().filter(entry->entry.getName().matches("timekeeper-notes-metadata-[a-f0-9-]{36}\\.json")).findFirst().orElseThrow();
            assertEquals(VaultExport.FORMAT,parse(new String(zip.getInputStream(metadata).readAllBytes(),StandardCharsets.UTF_8)).path("format").asText());
        }
    }
    @Test void httpDatesAreCreateOnlyValidatedAndExportRequiresAnAuthorizedReader(){
        String path="HTTP-"+UUID.randomUUID()+".md";
        var created=given().header("Authorization","Bearer test-a").header("If-None-Match","*").contentType("application/octet-stream").queryParam("path",path).queryParam("createdAt",CREATED).queryParam("modifiedAt",MODIFIED).body(bytes("HTTP body")).put("/api/v1/notes/files");
        assertEquals(201,created.statusCode());assertEquals(CREATED,created.jsonPath().getString("createdAt"));assertEquals(MODIFIED,created.jsonPath().getString("modifiedAt"));
        assertEquals(412,given().header("Authorization","Bearer test-a").header("If-None-Match","*").contentType("application/octet-stream").queryParam("path",path).queryParam("modifiedAt","2024-01-01T00:00:00Z").body(bytes("Wrong")).put("/api/v1/notes/files").statusCode());
        for(String invalid:List.of("not-a-date","0000-01-01T00:00:00Z","0001-01-01T00:00:00+14:00","9999-12-31T23:59:59-14:00"))assertEquals(422,given().header("Authorization","Bearer test-a").header("If-None-Match","*").contentType("application/octet-stream").queryParam("path","Invalid.md").queryParam("createdAt",invalid).body(bytes("Wrong")).put("/api/v1/notes/files").statusCode());
        assertEquals(404,given().header("Authorization","Bearer test-a").queryParam("path","Invalid.md").get("/api/v1/notes/resolve").statusCode());
        assertEquals(401,given().get("/api/v1/notes/export").statusCode());
        var download=given().header("Authorization","Bearer test-read").get("/api/v1/notes/export");assertEquals(200,download.statusCode());assertTrue(download.contentType().startsWith("application/zip"));assertTrue(download.header("Cache-Control").contains("no-store"));assertTrue(download.header("Content-Disposition").contains("timekeeper-notes-"));
    }
}
