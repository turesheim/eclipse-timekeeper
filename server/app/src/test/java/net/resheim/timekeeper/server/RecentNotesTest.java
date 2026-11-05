package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

@QuarkusTest @TestProfile(RecentNotesTest.Profile.class)
class RecentNotesTest {
    public static class Profile implements QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return VaultTestDatabase.configuration("recent",Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:vault_recent_test","timekeeper.notes.max-page-size","2"));}}
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    @Inject MockBackendClock clock;
    @Inject ContractInputs inputs;
    @AfterEach void restoreClock(){clock.set(null);}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void civilWindowsIncludeTodayAndRespectDaylightSaving(){
        var zone=ZoneId.of("Europe/Oslo");
        var spring=RecentNotes.window(Instant.parse("2026-03-29T12:00:00Z"),zone,1);
        assertEquals(Instant.parse("2026-03-28T23:00:00Z"),spring.from());assertEquals(23,Duration.between(spring.from(),spring.until()).toHours());
        var autumn=RecentNotes.window(Instant.parse("2026-10-25T12:00:00Z"),zone,1);
        assertEquals(25,Duration.between(autumn.from(),autumn.until()).toHours());
        var seven=RecentNotes.window(Instant.parse("2026-11-05T00:30:00Z"),ZoneId.of("America/Los_Angeles"),7);
        assertEquals(LocalDate.parse("2026-10-29"),seven.first());assertEquals(LocalDate.parse("2026-11-04"),seven.last());
    }
    @Test void onlyEligibleMetadataIsReturnedInStableEditOrderWithGuardedPages(){
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        store.transaction("recent-owner",()->{
            store.updateProfile("Europe/Oslo");var vault=new VaultCollection(store,limits);vault.initialize();
            vault.mkdir(new VaultPath("Notes"));
            for(var pair:List.of(new String[]{"Notes/Old.md","2026-10-29T22:59:59.999999999Z"},new String[]{"Notes/Boundary.md","2026-10-29T23:00:00Z"},
                    new String[]{"Notes/B.md","2026-11-05T09:00:00.000000001Z"},new String[]{"Notes/A.md","2026-11-05T09:00:00.000000001Z"},
                    new String[]{"Notes/Later today.md","2026-11-05T22:59:59.999999999Z"},new String[]{"Notes/Tomorrow.md","2026-11-05T23:00:00Z"}))
                VaultImportDates.parse(null,pair[1]).apply(store,vault.put(new VaultPath(pair[0]),null,true,bytes("private body")));
            vault.put(new VaultPath("Notes/image.png"),null,true,new byte[]{0,1,2});
            var deleted=vault.put(new VaultPath("Notes/Deleted.md"),null,true,bytes("deleted"));vault.delete(deleted,vault.tag(deleted));
            vault.put(new VaultPath("journal/2026-11-05.md"),null,true,bytes("day"));vault.put(new VaultPath("journal/2026-W45.md"),null,true,bytes("week"));
            vault.put(new VaultPath("journal/Private.md"),null,true,bytes("private journal"));
            vault.configure(etag(new VaultSettings(store).get()),object().put("journalFolder","Private/Journal"));
            var key=new EventNotes.Key(UUID.randomUUID(),"ab".repeat(32));var events=new EventNotes(store);
            var event=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event()).put("body","meeting note");event.set("event",object().put("uid","uid").put("recurrenceId","").put("title","Meeting"));
            events.saveDocument(key,etag(events.get(key)),EventNotes.document(event));
            var recent=new RecentNotes(store,limits);var first=recent.page(0,2,null);inputs.validate("RecentNotesPage",first);
            assertEquals("Notes/Later today.md",first.path("items").get(0).path("path").asText());assertEquals("EVENT",first.path("items").get(1).path("kind").asText());
            assertEquals("Meeting",first.path("items").get(1).path("title").asText());
            assertFalse(first.toString().contains("private body"));assertFalse(first.toString().contains("meeting note"));
            assertEquals(7,first.path("days").asInt());assertEquals("2026-10-30",first.path("fromDate").asText());assertEquals("2026-11-05",first.path("toDate").asText());
            String snapshot=first.path("snapshot").asText();var second=recent.page(2,2,snapshot);assertEquals("Notes/A.md",second.path("items").get(0).path("path").asText());assertEquals("Notes/B.md",second.path("items").get(1).path("path").asText());
            var last=recent.page(4,2,snapshot);assertEquals(1,last.path("items").size());assertEquals("Notes/Boundary.md",last.path("items").get(0).path("path").asText());assertFalse(last.has("nextOffset"));
            assertEquals(428,assertThrows(ApiFailure.class,()->recent.page(2,2,null)).status);
            vault.put(new VaultPath("Notes/New.md"),null,true,bytes("new"));assertEquals(412,assertThrows(ApiFailure.class,()->recent.page(2,2,snapshot)).status);return null;
        });
        store.transaction("other-recent-owner",()->{new VaultCollection(store,limits).initialize();assertTrue(new RecentNotes(store,limits).page(0,2,null).path("items").isEmpty());return null;});
    }
    @Test void midnightZoneAndSettingsInvalidateSnapshotsWithoutDocumentEdits(){
        clock.set(Instant.parse("2026-11-05T22:59:59Z"));
        String before=store.transaction("recent-midnight",()->{store.updateProfile("Europe/Oslo");var vault=new VaultCollection(store,limits);vault.initialize();return new RecentNotes(store,limits).page(0,1,null).path("snapshot").asText();});
        clock.set(Instant.parse("2026-11-05T23:00:00Z"));
        store.transaction("recent-midnight",()->{
            var recent=new RecentNotes(store,limits);assertEquals(412,assertThrows(ApiFailure.class,()->recent.page(0,1,before)).status);
            String midnight=recent.page(0,1,null).path("snapshot").asText();store.updateProfile("UTC");assertEquals(412,assertThrows(ApiFailure.class,()->recent.page(0,1,midnight)).status);
            String zone=recent.page(0,1,null).path("snapshot").asText();new VaultCollection(store,limits).configure(etag(new VaultSettings(store).get()),object().put("recentNotesDays",1));
            assertEquals(412,assertThrows(ApiFailure.class,()->recent.page(0,1,zone)).status);return null;
        });
    }
    @Test void emptyHttpReadsDoNotMaterializeBusinessRecordsOrChangeRevision(){
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        var before=store.transaction("b",()->{
            assertTrue(store.find(VaultState.TYPE,VaultState.ID).isEmpty());assertTrue(store.list(VaultIndex.ENTRY).isEmpty());
            try(var query=store.statement("SELECT COUNT(*) FROM tk_resource WHERE owner_id=?",store.owner());var rows=query.executeQuery()){assertTrue(rows.next());return new long[]{rows.getLong(1),store.revision()};}
            catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}
        });
        var initialSettings=given().header("Authorization","Bearer test-b").get("/api/v1/notes/settings");assertEquals(200,initialSettings.statusCode());
        var response=given().header("Authorization","Bearer test-b").get("/api/v1/notes/recent");assertEquals(200,response.statusCode());assertTrue(response.jsonPath().getList("items").isEmpty());
        store.transaction("b",()->{
            assertTrue(store.find(VaultState.TYPE,VaultState.ID).isEmpty());assertTrue(store.find(VaultSettings.TYPE,VaultSettings.ID).isEmpty());assertTrue(store.list(VaultIndex.ENTRY).isEmpty());assertEquals(before[1],store.revision());
            try(var query=store.statement("SELECT COUNT(*) FROM tk_resource WHERE owner_id=?",store.owner());var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(before[0],rows.getLong(1));}
            catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}return null;
        });
        var saved=given().header("Authorization","Bearer test-b").header("If-Match",initialSettings.header("ETag")).contentType("application/json").body("{\"editorCursor\":\"solid\"}").put("/api/v1/notes/settings");assertEquals(200,saved.statusCode());assertEquals("solid",saved.jsonPath().getString("editorCursor"));
        assertEquals(412,given().header("Authorization","Bearer test-b").header("If-Match",initialSettings.header("ETag")).contentType("application/json").body("{\"editorCursor\":\"line\"}").put("/api/v1/notes/settings").statusCode());
    }
    @Test void preCollectionEventNotesAreBackfilledWhileJournalOnlyAccountsStayUnmaterialized(){
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        store.transaction("recent-legacy-events",()->{
            var key=new EventNotes.Key(UUID.randomUUID(),"cd".repeat(32));var events=new EventNotes(store);
            var value=object().put("subscriptionId",key.subscription().toString()).put("eventKey",key.event()).put("body","Existing event");value.set("event",object().put("uid","uid").put("recurrenceId","").put("title","Existing meeting"));events.saveDocument(key,etag(events.get(key)),EventNotes.document(value));
            assertFalse(new VaultState(store,limits).get().path("initialized").asBoolean());var recent=new RecentNotes(store,limits).page(0,2,null);
            assertEquals(1,recent.path("items").size());assertEquals("EVENT",recent.path("items").get(0).path("kind").asText());return null;
        });
        store.transaction("recent-legacy-journal",()->{
            var notes=new DayNotes(store);var day=DayNotes.date("2026-11-05");notes.saveDocument(day,etag(notes.getContent(day)),bytes("Journal body"));
            assertTrue(new RecentNotes(store,limits).page(0,2,null).path("items").isEmpty());assertTrue(store.find(VaultState.TYPE,VaultState.ID).isEmpty());return null;
        });
    }
    @Test void httpReadsAreOwnerPrivateAndAllowReadOnlyTokens(){
        var path="Recent-"+UUID.randomUUID()+".md";
        assertEquals(201,given().header("Authorization","Bearer test-a").header("If-None-Match","*").contentType("application/octet-stream").queryParam("path",path).body(bytes("# Owner only title\n\nsecret body")).put("/api/v1/notes/files").statusCode());
        var reader=given().header("Authorization","Bearer test-read").get("/api/v1/notes/recent?limit=1");assertEquals(200,reader.statusCode());assertTrue(reader.header("Cache-Control").contains("no-store"));assertEquals(path,reader.jsonPath().getString("items[0].path"));assertEquals("Owner only title",reader.jsonPath().getString("items[0].title"));assertFalse(reader.asString().contains("secret body"));inputs.validate("RecentNotesPage",parse(reader.asString()));
        assertEquals(0,given().header("Authorization","Bearer test-b").get("/api/v1/notes/recent").jsonPath().getList("items").size());
        assertEquals(401,given().get("/api/v1/notes/recent").statusCode());assertEquals(428,given().header("Authorization","Bearer test-read").get("/api/v1/notes/recent?offset=1").statusCode());
        assertEquals(422,given().header("Authorization","Bearer test-read").get("/api/v1/notes/recent?limit=0").statusCode());
        var settings=given().header("Authorization","Bearer test-read").get("/api/v1/notes/settings");assertEquals(403,given().header("Authorization","Bearer test-read").header("If-Match",settings.header("ETag")).contentType("application/json").body("{\"recentNotesDays\":1}").put("/api/v1/notes/settings").statusCode());
    }
    @Test void displayTitlesFollowCanonicalNotesWithoutMutatingBytesVersionsOrReadGuards(){
        store.transaction("note-titles-owner",()->{
            var vault=new VaultCollection(store,limits);vault.initialize();
            String text="---\ntitle: Ignored metadata title\n---\n\n## Project **review**\n\nPrivate body\n";
            var entry=vault.put(new VaultPath("document.md"),null,true,bytes(text));
            String contentTag=vault.tag(entry),metadataTag=vault.metadataTag(entry),snapshot=vault.generation();long revision=store.revision();
            var metadata=vault.metadata(entry);inputs.validate("VaultEntry",metadata);assertEquals("Project review",metadata.path("title").asText());
            assertArrayEquals(bytes(text),vault.read(entry));assertEquals(contentTag,vault.tag(entry));assertEquals(metadataTag,vault.metadataTag(entry));assertEquals(snapshot,vault.generation());assertEquals(revision,store.revision());
            assertFalse(metadata.has("body"));assertFalse(metadata.toString().contains("Private body"));
            var recent=new RecentNotes(store,limits).page(0,2,null);assertEquals("Project review",recent.path("items").get(0).path("title").asText());
            var saved=vault.put(VaultCollection.path(entry),contentTag,false,bytes("First replacement\n=================\n\nBody\n"));
            assertEquals("First replacement",vault.metadata(saved).path("title").asText());
            vault.delete(saved,vault.tag(saved));var trash=vault.get(VaultCollection.id(saved));assertEquals("First replacement",vault.metadata(trash).path("title").asText());
            var restored=vault.restore(trash,vault.tag(trash));assertEquals("First replacement",vault.metadata(restored).path("title").asText());
            assertFalse(vault.metadata(vault.put(new VaultPath("plain.md"),null,true,bytes("No heading"))).has("title"));
            assertFalse(vault.metadata(vault.put(new VaultPath("journal/2026-11-05.md"),null,true,bytes("# Journal heading"))).has("title"));
            return null;
        });
    }
}
