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
    @Test void v2CsvRoundTripRemapsSessionsLabelsAndTasklessDirectProjectsAtomically() throws Exception {
        try(var source=client("test-a");var target=client("test-b")) {
            var from=Instant.parse("2025-10-25T21:59:59.123456789Z");var end=Instant.parse("2025-10-27T22:00:00.987654321Z");
            var session=source.createWorkSession(new CreateWorkSession(OwnerId.LOCAL,from,Optional.of(end),"Session, æøå"));
            var project=source.createProject(new CreateProject("Direct project, æøå"));
            var label=source.createLabel(new CreateLabel("Label, æøå",Optional.of("12,34,56")));
            source.createActivity(new CreateActivity(null,OwnerId.LOCAL,from,Optional.of(end),"Unassigned activity",Set.of(label.id()),Optional.of(session.id()),Optional.empty()));
            source.createActivity(new CreateActivity(null,OwnerId.LOCAL,from.plusSeconds(1),Optional.of(from.plusSeconds(61)),"Direct project",Set.of(),Optional.of(session.id()),Optional.of(project.id())));
            var before=source.workSessionReport(session.id());var export=source.exportData();
            assertEquals("timekeeper-domain-v2",export.get("format").getAsString());
            assertFalse(export.getAsJsonArray("workSessions").get(0).getAsJsonObject().has("state"));
            var invalid=export.deepCopy();invalid.getAsJsonArray("activities").get(0).getAsJsonObject().addProperty("workSessionId",UUID.randomUUID().toString());
            var unchanged=target.exportData();assertThrows(RemoteFailure.class,()->target.importData(invalid));assertEquals(unchanged,target.exportData());
            var folder=directory.resolve("export");source.exportCsv(folder);assertTrue(Files.isRegularFile(folder.resolve("workSessions.csv")));
            int imported=target.importCsv(folder,directory.resolve("journal"));assertTrue(imported>0);
            var sessions=target.workSessions(OwnerId.LOCAL);assertEquals(1,sessions.size());var copied=sessions.getFirst();assertNotEquals(session.id(),copied.id());
            var after=target.workSessionReport(copied.id());assertEquals(before.time(),after.time());assertEquals(4,after.activities().size());assertTrue(target.tasks().isEmpty());
            var copiedProject=target.projects().stream().filter(value->value.name().equals(project.name())).findFirst().orElseThrow();assertNotEquals(project.id(),copiedProject.id());
            var copiedLabel=target.labels().stream().filter(value->value.name().equals(label.name())).findFirst().orElseThrow();assertNotEquals(label.id(),copiedLabel.id());
            for(var activity:after.activities()) {
                assertTrue(activity.task().isEmpty());assertEquals(Optional.of(copied.id()),activity.workSessionId());
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
