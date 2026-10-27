package net.resheim.eclipse.timekeeper.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class CsvSnapshotTest {
    @TempDir Path temporary;

    @Test void taskExtrasPreservePlanningTimingNotesOrderAliasesAndRecurrenceInMixedRows() throws Exception {
        var data=JsonParser.parseString("""
            {"format":"timekeeper-domain-v3","zoneId":"UTC","clients":[],"projects":[],"labels":[],"activities":[],"workSessions":[],
             "tasks":[{"id":"new","projectId":null,"parentTaskId":null,"summary":"Future","url":null,"externalReferences":[],
                       "status":"NOT_STARTED","scheduledDate":"2026-10-26","completedAt":null,"checklistItems":[],
                       "start":null,"due":{"kind":"DATE","date":"2026-10-27"},"priority":"HIGH","flagged":true,"urgent":false,
                       "body":{"format":"TEXT","content":"Keep me"},"siblingOrder":7,
                       "externalAliases":[{"providerId":"microsoft","repositoryId":"account/list-a","externalId":"previous"}],
                       "recurrence":{"series":{"rule":"FREQ=WEEKLY;BYDAY=MO","end":"COUNT","until":null,"count":8},
                                     "occurrences":[{"original":{"kind":"DATE","date":"2026-10-26"},"movedTo":null,"state":"COMPLETED","completedAt":"2026-10-26T12:00:00Z"}]}},
                      {"id":"old","projectId":null,"parentTaskId":null,"summary":"Earlier","url":null,"externalReferences":[]}]}
            """).getAsJsonObject();
        var folder=temporary.resolve("task-extras");assertEquals(2,CsvSnapshot.write(data,folder));assertEquals(data,CsvSnapshot.read(folder));
        assertTrue(Files.readString(folder.resolve("tasks.csv")).contains("taskExtras"));
        Files.writeString(folder.resolve("tasks.csv"),Files.readString(folder.resolve("tasks.csv")).replace(",\"taskExtras\"", ""));
        assertThrows(IOException.class,()->CsvSnapshot.read(folder));
    }

    @Test void providerAccountsAndListsRoundTripWithoutChangingOlderCsvExports() throws Exception {
        var data=JsonParser.parseString("""
            {"format":"timekeeper-domain-v3","zoneId":"UTC","clients":[],"projects":[],"tasks":[],"labels":[],"activities":[],"workSessions":[],
             "taskConnections":[{"id":"account","providerId":"google","remoteAccountId":"user@example.com","name":"Personal","readable":true,"writable":false,"enabled":true}],
             "taskLists":[{"id":"list","connectionId":"account","remoteListId":"remote","name":"Tasks","kind":"BUILT_IN","readable":true,"writable":false,"shared":false,"owner":true,"present":true,"position":3}]}
            """).getAsJsonObject();
        var folder=temporary.resolve("providers");assertEquals(2,CsvSnapshot.write(data,folder));assertEquals(data,CsvSnapshot.read(folder));
        Files.delete(folder.resolve("taskLists.csv"));assertThrows(IOException.class,()->CsvSnapshot.read(folder));
        Files.delete(folder.resolve("taskConnections.csv"));assertThrows(IOException.class,()->CsvSnapshot.read(folder));
        data.remove("taskConnections");data.remove("taskLists");
        var legacy=temporary.resolve("legacy");CsvSnapshot.write(data,legacy);assertEquals(data,CsvSnapshot.read(legacy));
    }

    @Test void v3CsvRetainsTheClientGraphAndCannotBeSilentlyDowngraded() throws Exception {
        var data=JsonParser.parseString("""
            {"format":"timekeeper-domain-v3","zoneId":"UTC",
             "clients":[{"id":"client","name":"Client, æøå","color":"#ffDD00"}],
             "projects":[{"id":"project","name":"Project","clientId":"client"}],
             "workSessions":[{"id":"session","start":"2025-10-20T08:00:00.123456789Z","end":null,"summary":"Description","clientId":"client","title":"Planning"}],
             "tasks":[],"labels":[],"activities":[]}
            """).getAsJsonObject();
        var folder=temporary.resolve("clients");assertEquals(3,CsvSnapshot.write(data,folder));assertEquals(data,CsvSnapshot.read(folder));
        data.addProperty("format","timekeeper-domain-v2");assertThrows(IOException.class,()->CsvSnapshot.write(data,temporary.resolve("downgrade")));
        Files.delete(folder.resolve("clients.csv"));assertThrows(IOException.class,()->CsvSnapshot.read(folder));
    }
    @Test void optionalProjectIconsRoundTripAlongsideEarlierProjectsWithoutIcons() throws Exception {
        var data=JsonParser.parseString("""
            {"format":"timekeeper-domain-v3","zoneId":"UTC","clients":[],"tasks":[],"labels":[],"activities":[],"workSessions":[],
             "projects":[{"id":"first","name":"Code","clientId":"client","icon":"code"},{"id":"legacy","name":"Earlier","clientId":"client"}]}
            """).getAsJsonObject();
        var folder=temporary.resolve("icons");CsvSnapshot.write(data,folder);assertEquals(data,CsvSnapshot.read(folder));
        assertTrue(Files.readString(folder.resolve("projects.csv")).startsWith("\"id\",\"name\",\"clientId\",\"icon\""));
        data.getAsJsonArray("projects").get(0).getAsJsonObject().remove("icon");
        var legacy=temporary.resolve("earlier-project-header");CsvSnapshot.write(data,legacy);assertEquals(data,CsvSnapshot.read(legacy));
        assertTrue(Files.readString(legacy.resolve("projects.csv")).startsWith("\"id\",\"name\",\"clientId\"\r\n"));
    }
    @Test void v3CsvRoundTripsTitlelessSessionsAndMixedLegacyTitles() throws Exception {
        var data=JsonParser.parseString("""
            {"format":"timekeeper-domain-v3","zoneId":"UTC","clients":[],"projects":[],"tasks":[],"labels":[],"activities":[],
             "workSessions":[{"id":"session","start":"2025-10-20T08:00:00Z","end":null,"summary":"Only a summary","clientId":"client"}]}
            """).getAsJsonObject();
        var current=temporary.resolve("summary-only");CsvSnapshot.write(data,current);assertEquals(data,CsvSnapshot.read(current));
        assertTrue(Files.readString(current.resolve("workSessions.csv")).startsWith("\"id\",\"start\",\"end\",\"summary\",\"clientId\"\r\n"));
        var legacy=data.getAsJsonArray("workSessions").get(0).getAsJsonObject().deepCopy();legacy.addProperty("id","legacy");legacy.addProperty("title","Earlier title");data.getAsJsonArray("workSessions").add(legacy);
        var mixed=temporary.resolve("mixed");CsvSnapshot.write(data,mixed);assertEquals(data,CsvSnapshot.read(mixed));
    }
    @Test void v3CsvRetainsOptionalWorkSessionLabelsAndStillReadsTheEarlierHeader() throws Exception {
        var data=JsonParser.parseString("""
            {"format":"timekeeper-domain-v3","zoneId":"UTC","clients":[],"projects":[],"tasks":[],"labels":[],"activities":[],
             "workSessions":[{"id":"session","start":"2025-10-20T08:00:00Z","end":null,"summary":"","clientId":"client","title":"Labelled","labelIds":["first","second"]}]}
            """).getAsJsonObject();
        var folder=temporary.resolve("labelled");CsvSnapshot.write(data,folder);assertEquals(data,CsvSnapshot.read(folder));
        assertTrue(Files.readString(folder.resolve("workSessions.csv")).startsWith("\"id\",\"start\",\"end\",\"summary\",\"clientId\",\"title\",\"labelIds\""));
        data.getAsJsonArray("workSessions").get(0).getAsJsonObject().remove("labelIds");
        var legacy=temporary.resolve("legacy");CsvSnapshot.write(data,legacy);assertEquals(data,CsvSnapshot.read(legacy));
        assertTrue(Files.readString(legacy.resolve("workSessions.csv")).startsWith("\"id\",\"start\",\"end\",\"summary\",\"clientId\",\"title\"\r\n"));
    }
    @Test void v3CsvRetainsOptionalWorkSessionProjectsWithOrWithoutLabels() throws Exception {
        var data=JsonParser.parseString("""
            {"format":"timekeeper-domain-v3","zoneId":"UTC","clients":[],"projects":[],"tasks":[],"labels":[],"activities":[],
             "workSessions":[{"id":"session","start":"2025-10-20T08:00:00Z","end":null,"summary":"","clientId":"client","title":"Delivery","projectId":"project"}]}
            """).getAsJsonObject();
        var folder=temporary.resolve("project-only");CsvSnapshot.write(data,folder);assertEquals(data,CsvSnapshot.read(folder));
        assertTrue(Files.readString(folder.resolve("workSessions.csv")).startsWith("\"id\",\"start\",\"end\",\"summary\",\"clientId\",\"title\",\"projectId\"\r\n"));
        var session=data.getAsJsonArray("workSessions").get(0).getAsJsonObject();session.add("labelIds",new com.google.gson.JsonArray());session.add("projectId",com.google.gson.JsonNull.INSTANCE);
        var both=temporary.resolve("project-labels");CsvSnapshot.write(data,both);assertEquals(data,CsvSnapshot.read(both));
    }
    @Test void v2CsvPreservesTasklessProjectAndSessionRelationsAndRequiresItsSessionFile() throws Exception {
        var data=JsonParser.parseString("""
            {"format":"timekeeper-domain-v2","zoneId":"UTC","projects":[],"tasks":[],"labels":[],
             "workSessions":[{"id":"00000000-0000-4000-8000-000000000001","start":"2025-10-25T22:00:00.123456789Z","end":null,"summary":"Session, æøå"}],
             "activities":[{"id":"00000000-0000-4000-8000-000000000002","taskId":null,"start":"2025-10-25T22:00:00.123456789Z","end":null,"summary":"Meeting","labelIds":[],"manual":false,
                            "workSessionId":"00000000-0000-4000-8000-000000000001","projectId":null}]}
            """).getAsJsonObject();
        var root=Files.createTempDirectory("timekeeper-session-csv-");
        try {
            var export=root.resolve("export");assertEquals(2,CsvSnapshot.write(data,export));assertEquals(data,CsvSnapshot.read(export));
            assertTrue(Files.isRegularFile(export.resolve("workSessions.csv")));
            Files.delete(export.resolve("workSessions.csv"));assertThrows(java.io.IOException.class,()->CsvSnapshot.read(export));
            data.addProperty("format","timekeeper-domain-v1");assertThrows(java.io.IOException.class,()->CsvSnapshot.write(data,root.resolve("invalid")));
        } finally {
            try(var paths=Files.walk(root)) {for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
        }
    }
    @Test void roundTripPreservesUnicodeQuotesNewlinesNullsIdsAndNanoseconds() throws Exception {
        JsonObject original=snapshot();Path export=temporary.resolve("folder with spaces");
        assertEquals(5,CsvSnapshot.write(original,export));
        assertEquals(original,CsvSnapshot.read(export));
        assertTrue(Files.readString(export.resolve("projects.csv")).contains("\"\"quoted\"\""));
        assertFalse(Files.readString(export.resolve("activities.csv")).contains("ownerId"));
        assertFalse(Files.readString(export.resolve("tasks.csv")).contains("version"));
    }

    @Test void exportRefusesToOverwriteAnEarlierFolder() throws Exception {
        Path export=temporary.resolve("existing");Files.createDirectory(export);Files.writeString(export.resolve("keep.txt"),"keep");
        assertThrows(IOException.class,()->CsvSnapshot.write(snapshot(),export));
        assertEquals("keep",Files.readString(export.resolve("keep.txt")));
    }

    @Test void missingFileMalformedCsvAndInvalidNestedValuesAreRejected() throws Exception {
        Path missing=temporary.resolve("missing");CsvSnapshot.write(snapshot(),missing);Files.delete(missing.resolve("labels.csv"));
        assertThrows(IOException.class,()->CsvSnapshot.read(missing));
        Path invalid=temporary.resolve("invalid");CsvSnapshot.write(snapshot(),invalid);
        Files.writeString(invalid.resolve("projects.csv"),"\"id\",\"name\"\r\n\"unterminated");
        assertThrows(IOException.class,()->CsvSnapshot.read(invalid));
        Path nested=temporary.resolve("nested");CsvSnapshot.write(snapshot(),nested);
        Files.writeString(nested.resolve("tasks.csv"),"\"id\",\"projectId\",\"parentTaskId\",\"summary\",\"url\",\"externalReferences\"\r\n\"id\",,,\"summary\",,\"{}\"\r\n");
        assertThrows(IOException.class,()->CsvSnapshot.read(nested));
    }

    @Test void corruptDescriptorIsReportedAsAUserFacingIoFailure() throws Exception {
        Path export=temporary.resolve("descriptor");CsvSnapshot.write(snapshot(),export);
        Files.writeString(export.resolve("timekeeper.csv.json"),"{}");
        assertEquals("Invalid or incomplete Timekeeper domain CSV export.",assertThrows(IOException.class,()->CsvSnapshot.read(export)).getMessage());
    }

    @Test void pendingImportSurvivesClientRestartAndIsScopedByBackendOwnerAndPayload() throws Exception {
        Path journal=temporary.resolve("journal");String payload=snapshot().toString();
        String first=new ImportJournal(journal,"https://one.invalid/api/v1","alice",payload).pending();
        assertEquals(first,new ImportJournal(journal,"https://one.invalid/api/v1","alice",payload).pending());
        assertNotEquals(first,new ImportJournal(journal,"https://two.invalid/api/v1","alice",payload).pending());
        assertNotEquals(first,new ImportJournal(journal,"https://one.invalid/api/v1","bob",payload).pending());
        assertNotEquals(first,new ImportJournal(journal,"https://one.invalid/api/v1","alice",payload+" ").pending());
        new ImportJournal(journal,"https://one.invalid/api/v1","alice",payload).acknowledged(first);
        assertNotEquals(first,new ImportJournal(journal,"https://one.invalid/api/v1","alice",payload).pending());
        try(var files=Files.list(journal)) {
            for(Path file:files.toList()) {
                assertFalse(file.getFileName().toString().contains("alice"));
                String contents=Files.readString(file);
                if(!contents.isEmpty()) assertDoesNotThrow(()->UUID.fromString(contents));
            }
        }
    }

    static JsonObject snapshot() {
        JsonObject value=JsonParser.parseString("""
            {"format":"timekeeper-domain-v1","zoneId":"Europe/Oslo",
             "projects":[{"id":"11111111-1111-4111-8111-111111111111","name":"Økonomi, \\"quoted\\"\\nnext line"}],
             "tasks":[
              {"id":"22222222-2222-4222-8222-222222222222","projectId":"11111111-1111-4111-8111-111111111111","parentTaskId":null,"summary":"Parent","url":null,"externalReferences":[]},
              {"id":"33333333-3333-4333-8333-333333333333","projectId":"11111111-1111-4111-8111-111111111111","parentTaskId":"22222222-2222-4222-8222-222222222222","summary":"Child","url":"https://example.invalid/æ","externalReferences":[{"providerId":"jira","repositoryId":"https://jira.invalid","externalId":"TK-1","externalUrl":null}]}],
             "labels":[{"id":"44444444-4444-4444-8444-444444444444","name":"","color":null}],
             "activities":[{"id":"55555555-5555-4555-8555-555555555555","taskId":"33333333-3333-4333-8333-333333333333","start":"2026-10-12T08:00:00.123456789Z","end":"2026-10-12T09:00:00.987654321Z","summary":"","labelIds":["44444444-4444-4444-8444-444444444444"],"manual":true}]}
            """).getAsJsonObject();
        return value;
    }
}
