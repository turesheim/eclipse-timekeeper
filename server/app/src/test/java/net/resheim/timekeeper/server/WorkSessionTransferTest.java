package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.quarkus.test.junit.*;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.*;
import net.resheim.eclipse.timekeeper.client.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(WorkSessionTransferTest.Profile.class)
class WorkSessionTransferTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:work_session_transfer_tests");}
    }
    @TestHTTPResource URI endpoint;
    @Inject ServerStore store;
    @TempDir Path directory;
    private HttpTimekeeperService client(String token) {return new HttpTimekeeperService(endpoint,()->token,Duration.ofSeconds(5));}
    @Test void legacyImportIgnoresRetiredReferencesToMissingSessions() {
        try(var target=client("test-b")) {
            var input=JsonParser.parseString("""
                {"format":"timekeeper-domain-v2","zoneId":"UTC","projects":[],"tasks":[],"labels":[],"workSessions":[],
                 "activities":[{"id":"00000000-0000-4000-8000-000000000001","taskId":null,"projectId":null,
                 "workSessionId":"00000000-0000-4000-8000-000000000002","start":"2025-01-01T08:00:00Z",
                 "end":"2025-01-01T09:00:00Z","summary":"Legacy independent work","labelIds":[],"manual":true}]}
                """).getAsJsonObject();
            target.importData(input);
            var exported=target.exportData().getAsJsonArray("activities");
            var imported=java.util.stream.StreamSupport.stream(exported.spliterator(),false).map(JsonElement::getAsJsonObject)
                    .filter(value->value.get("summary").getAsString().equals("Legacy independent work")).toList();
            assertEquals(1,imported.size());assertFalse(imported.getFirst().has("workSessionId"));
            assertEquals("2025-01-01T08:00:00Z",imported.getFirst().get("start").getAsString());
            var id=new ActivityId(UUID.fromString(imported.getFirst().get("id").getAsString()));
            var record=target.activity(id).orElseThrow();target.deleteActivity(new DeleteActivity(id,record.version()));
        }
    }
    @Test void v3CsvRoundTripRemapsSessionsLabelsAndTasklessDirectProjectsAtomically() throws Exception {
        try(var source=client("test-a");var target=client("test-b")) {
            var from=Instant.parse("2025-10-25T21:59:59.123456789Z");var end=Instant.parse("2025-10-27T22:00:00.987654321Z");
            var session=source.createWorkSession(new CreateWorkSession(OwnerId.LOCAL,from,Optional.of(end),"Session, æøå"));
            var project=source.createProject(new CreateProject("Direct project, æøå"));
            var label=source.createLabel(new CreateLabel("Label, æøå",Optional.of("12,34,56")));
            source.createActivity(new CreateActivity(null,OwnerId.LOCAL,from,Optional.of(end),"Unassigned activity",Set.of(label.id()),Optional.of(session.id()),Optional.empty()));
            source.createActivity(new CreateActivity(null,OwnerId.LOCAL,from.plusSeconds(1),Optional.of(from.plusSeconds(61)),"Direct project",Set.of(),Optional.of(session.id()),Optional.of(project.id())));
            var before=source.workSessionReport(session.id());var export=source.exportData();
            assertEquals("timekeeper-domain-v3",export.get("format").getAsString());
            assertFalse(export.getAsJsonArray("workSessions").get(0).getAsJsonObject().has("state"));
            var invalid=export.deepCopy();invalid.getAsJsonArray("activities").get(0).getAsJsonObject().addProperty("projectId",UUID.randomUUID().toString());
            var unchanged=target.exportData();assertThrows(RemoteFailure.class,()->target.importData(invalid));assertEquals(unchanged,target.exportData());
            var folder=directory.resolve("export");source.exportCsv(folder);assertTrue(Files.isRegularFile(folder.resolve("workSessions.csv")));assertTrue(Files.isRegularFile(folder.resolve("clients.csv")));
            int imported=target.importCsv(folder,directory.resolve("journal"));assertTrue(imported>0);
            var sessions=target.workSessions(OwnerId.LOCAL);assertEquals(1,sessions.size());var copied=sessions.getFirst();assertNotEquals(session.id(),copied.id());
            var transferred=target.exportData();
            var copiedWire=transferred.getAsJsonArray("workSessions").get(0).getAsJsonObject();
            assertFalse(copiedWire.has("title"));assertEquals("Session, æøå",copiedWire.get("summary").getAsString());
            var sourceClient=export.getAsJsonArray("workSessions").get(0).getAsJsonObject().get("clientId");
            assertNotEquals(sourceClient,copiedWire.get("clientId"));
            assertEquals(copiedWire.get("clientId"),transferred.getAsJsonArray("projects").get(0).getAsJsonObject().get("clientId"));
            var after=target.workSessionReport(copied.id());assertEquals(before.time(),after.time());assertEquals(4,after.activities().size());assertTrue(target.tasks().isEmpty());
            var copiedProject=target.projects().stream().filter(value->value.name().equals(project.name())).findFirst().orElseThrow();assertNotEquals(project.id(),copiedProject.id());
            var copiedLabel=target.labels().stream().filter(value->value.name().equals(label.name())).findFirst().orElseThrow();assertNotEquals(label.id(),copiedLabel.id());
            for(var activity:after.activities()) {
                assertTrue(activity.task().isEmpty());assertTrue(activity.workSessionId().isEmpty());
                if(activity.summary().equals("Direct project")) assertEquals(Optional.of(copiedProject.id()),activity.projectId());
                else assertEquals(Set.of(copiedLabel.id()),activity.labelIds());
                assertTrue(activity.manual());
            }
            assertEquals(before,source.workSessionReport(session.id()),"Import changed source records");
            var bad=JsonParser.parseString("""
                {"format":"timekeeper-domain-v2","zoneId":"UTC","projects":[],"tasks":[],"labels":[],"activities":[],"workSessions":[]}
                """).getAsJsonObject();
            for(int i=0;i<2;i++) {
                var item=new JsonObject();item.addProperty("id",UUID.randomUUID().toString());item.addProperty("start",from.toString());item.add("end",JsonNull.INSTANCE);item.addProperty("summary","Open session");bad.getAsJsonArray("workSessions").add(item);
            }
            var committed=target.exportData();assertThrows(RemoteFailure.class,()->target.importData(bad));assertEquals(committed,target.exportData());
            bad.getAsJsonArray("workSessions").remove(1);
            var live=target.startWorkSession(new StartWorkSession(OwnerId.LOCAL,"Existing session"));
            assertEquals(RemoteFailure.Kind.CONFLICT,assertThrows(RemoteFailure.class,()->target.importData(bad)).kind());
            assertEquals(live,target.activeWorkSession(OwnerId.LOCAL).orElseThrow());
        }
    }
}
