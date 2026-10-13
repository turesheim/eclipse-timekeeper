package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.http.TestHTTPResource;
import net.resheim.eclipse.timekeeper.client.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;

@QuarkusTest
class HttpClientIntegrationTest {
    @TestHTTPResource URI server;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    @jakarta.inject.Inject TokenRegistry registry;
    @Test void anEmptyPageBindsUnicodeOwnersWithoutAPriorProfileRead() throws Exception {
        var actual=io.quarkus.arc.ClientProxy.unwrap(registry);String originalFile=actual.tokensFile;
        var file=temporary.resolve("unicode-owner-registry.json");
        var rows=(com.fasterxml.jackson.databind.node.ArrayNode)JsonValues.parse(java.nio.file.Files.readString(java.nio.file.Path.of(originalFile)));
        String owner="æøå / 🧩 "+UUID.randomUUID(),nextOwner="unicode-next-"+UUID.randomUUID();
        var row=JsonValues.object();row.put("ownerId",owner);
        row.put("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("test-unicode-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        row.putArray("permissions").add("read").add("write");rows.add(row);
        java.nio.file.Files.writeString(file,rows.toString());actual.tokensFile=file.toString();
        try(var client=new HttpTimekeeperService(server,()->"test-unicode-owner",Duration.ofSeconds(3))) {
            assertTrue(client.projects().isEmpty());
            row.put("ownerId",nextOwner);java.nio.file.Files.writeString(file,rows.toString());
            try(var selected=new HttpTimekeeperService(server,()->"test-unicode-owner",Duration.ofSeconds(3))) {
                var before=selected.metadata();
                assertEquals("OWNER_CHANGED",assertThrows(RemoteFailure.class,()->client.createProject(new CreateProject("Must not silently change Unicode owner"))).code());
                assertEquals(before,selected.metadata());
            }
            row.put("ownerId",owner);java.nio.file.Files.writeString(file,rows.toString());
            assertEquals(owner,client.profile().owner().value());
            var project=client.createProject(new CreateProject("Unicode bound project"));
            client.deleteProject(new DeleteProject(project.id(),project.version()));
        } finally {actual.tokensFile=originalFile;}
    }
    @Test void aLiveClientCannotWriteUnderAReassignedTokenOwner() throws Exception {
        var actual=io.quarkus.arc.ClientProxy.unwrap(registry);String originalFile=actual.tokensFile;
        var file=temporary.resolve("live-owner-registry.json");
        var rows=(com.fasterxml.jackson.databind.node.ArrayNode)JsonValues.parse(java.nio.file.Files.readString(java.nio.file.Path.of(originalFile)));
        String owner="reassigned-"+UUID.randomUUID();
        var peerRow=JsonValues.object();peerRow.put("ownerId",owner);
        peerRow.put("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("test-owner-peer".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        peerRow.putArray("permissions").add("read").add("write");rows.add(peerRow);
        java.nio.file.Files.writeString(file,rows.toString());actual.tokensFile=file.toString();
        try(var client=new HttpTimekeeperService(server,()->"test-a",Duration.ofSeconds(3));
                var peer=new HttpTimekeeperService(server,()->"test-owner-peer",Duration.ofSeconds(3));
                var readOriginal=new HttpTimekeeperService(server,()->"test-read",Duration.ofSeconds(3))) {
            var project=client.createProject(new CreateProject("Original owner binding "+UUID.randomUUID()));
            assertEquals("a",client.profile().owner().value());
            var original=readOriginal.metadata();var before=peer.metadata();
            ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("ownerId",owner);
            java.nio.file.Files.writeString(file,rows.toString());
            var failure=assertThrows(RemoteFailure.class,()->client.createProject(new CreateProject("Must not appear under reassigned owner")));
            assertEquals(RemoteFailure.Kind.AUTHENTICATION,failure.kind());assertEquals("OWNER_CHANGED",failure.code());
            assertEquals(before,peer.metadata(),"Rejecting the client after a committed foreign write is too late");
            assertEquals(original,readOriginal.metadata());
            assertEquals(RemoteFailure.Kind.AUTHENTICATION,assertThrows(RemoteFailure.class,client::projects).kind());
            assertEquals(RemoteFailure.Kind.AUTHENTICATION,assertThrows(RemoteFailure.class,client::metadata).kind());
            try(var selected=new HttpTimekeeperService(server,()->"test-a",Duration.ofSeconds(3))) {
                assertEquals(owner,selected.profile().owner().value());
                var intended=selected.createProject(new CreateProject("Explicit new client owner"));
                selected.deleteProject(new DeleteProject(intended.id(),intended.version()));
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("ownerId","a");java.nio.file.Files.writeString(file,rows.toString());
            assertEquals(project,client.project(project.id()).orElseThrow());client.deleteProject(new DeleteProject(project.id(),project.version()));
        } finally {actual.tokensFile=originalFile;}
    }
    @Test void csvTransferPreservesRelationshipsAndPrecisionWithFreshOwnedIds() throws Exception {
        String name="CSV "+UUID.randomUUID();
        try(var source=new HttpTimekeeperService(server,()->"test-a",Duration.ofSeconds(3));
                var target=new HttpTimekeeperService(server,()->"test-b",Duration.ofSeconds(3))) {
            var project=source.createProject(new CreateProject(name+" project"));
            var parent=source.createTask(new CreateTask(Optional.of(project.id()),name+" parent",Optional.empty()));
            var reference=new ExternalTaskReference("jira","https://jira.invalid",UUID.randomUUID().toString(),Optional.of("https://jira.invalid/browse/TK-1"));
            var child=source.createTask(new CreateTask(Optional.of(project.id()),Optional.of(parent.id()),name+", \"child\"\næøå",Optional.empty(),Set.of(reference)));
            var label=source.createLabel(new CreateLabel(name+" label",Optional.of("10,20,30")));
            Instant start=Instant.parse("2026-10-12T08:00:00.123456789Z"),end=Instant.parse("2026-10-12T09:00:00.987654321Z");
            var activity=source.createActivity(new CreateActivity(child.id(),OwnerId.LOCAL,start,Optional.of(end),"",Set.of(label.id())));
            var before=target.metadata();
            var csv=temporary.resolve("export with spaces");assertTrue(source.exportCsv(csv)>=5);
            assertTrue(target.importCsv(csv,temporary.resolve("journal"))>=5);
            var imported=target.findTask(reference.key()).orElseThrow();
            assertNotEquals(child.id(),imported.id());assertEquals(child.summary(),imported.summary());
            assertEquals(Set.of(reference),imported.externalReferences());
            var importedParent=target.task(imported.parentTaskId().orElseThrow()).orElseThrow();
            assertEquals(parent.summary(),importedParent.summary());assertNotEquals(parent.id(),importedParent.id());
            assertEquals(importedParent.projectId(),imported.projectId());
            assertEquals(project.name(),target.project(imported.projectId().orElseThrow()).orElseThrow().name());
            var report=target.activities(new ActivityQuery(start,end,Optional.of(OwnerId.LOCAL),Optional.of(imported.id()),Optional.empty(),ZoneId.of("Europe/Oslo")));
            assertEquals(Duration.between(start,end),report.total());
            var recorded=report.activities().getFirst();assertNotEquals(activity.id(),recorded.id());
            assertEquals(target.profile().owner(),recorded.ownerId());assertTrue(recorded.manual());
            assertEquals(start,recorded.start());assertEquals(Optional.of(end),recorded.end());
            var importedLabel=target.label(recorded.labelIds().iterator().next()).orElseThrow();
            assertNotEquals(label.id(),importedLabel.id());assertEquals(label.name(),importedLabel.name());assertEquals(label.color(),importedLabel.color());
            assertTrue(source.task(imported.id()).isEmpty());
            var accepted=target.exportData();long revision=target.metadata().revision();
            assertEquals(RemoteFailure.Kind.CONFLICT,assertThrows(RemoteFailure.class,()->target.importCsv(csv,temporary.resolve("journal"))).kind());
            assertEquals(accepted,target.exportData());assertEquals(revision,target.metadata().revision());
            // The fixture is isolated, and cleanup preserves records that predate this import.
            var oldTasks=before.tasks().stream().map(Task::id).collect(java.util.stream.Collectors.toSet());
            var oldProjects=before.projects().stream().map(Project::id).collect(java.util.stream.Collectors.toSet());
            var oldLabels=before.labels().stream().map(Label::id).collect(java.util.stream.Collectors.toSet());
            target.deleteActivity(new DeleteActivity(recorded.id(),recorded.version()));
            target.deleteTask(new DeleteTask(imported.id(),imported.version()));target.deleteTask(new DeleteTask(importedParent.id(),importedParent.version()));
            for(var item:target.labels()) if(!oldLabels.contains(item.id()))
                target.deleteLabel(new DeleteLabel(item.id(),item.version()));
            var importedProject=target.project(imported.projectId().orElseThrow()).orElseThrow();target.deleteProject(new DeleteProject(importedProject.id(),importedProject.version()));
            assertTrue(target.tasks().stream().allMatch(task->oldTasks.contains(task.id())));
            assertTrue(target.projects().stream().allMatch(item->oldProjects.contains(item.id())));
            assertTrue(target.labels().stream().allMatch(item->oldLabels.contains(item.id())));
            source.deleteActivity(new DeleteActivity(activity.id(),activity.version()));source.deleteTask(new DeleteTask(child.id(),child.version()));
            source.deleteTask(new DeleteTask(parent.id(),parent.version()));source.deleteLabel(new DeleteLabel(label.id(),label.version()));source.deleteProject(new DeleteProject(project.id(),project.version()));
        }
    }
    @Test void javaClientPerformsOwnedCrudTrackingReportsAndProviderLinking() {
        try(var client=new HttpTimekeeperService(server,()->"test-a",Duration.ofSeconds(3))) {
            var project=client.createProject(new CreateProject("HTTP client "+UUID.randomUUID()));
            var parent=client.createTask(new CreateTask(Optional.of(project.id()),"Parent",Optional.empty()));
            var child=client.createTask(new CreateTask(Optional.of(project.id()),Optional.of(parent.id()),"Child",Optional.empty()));
            var label=client.createLabel(new CreateLabel("Client label",Optional.of("10,20,30")));
            assertEquals("10,20,30",label.color().orElseThrow());
            var reference=new ExternalTaskReference("jira","https://jira.invalid",UUID.randomUUID().toString(),Optional.empty());
            child=client.linkExternalReference(new LinkExternalReference(child.id(),child.version(),reference));
            assertEquals(child.id(),client.findTask(reference.key()).orElseThrow().id());
            var first=client.activate(child.id(),"Tracked",Set.of(label.id()),Optional.empty());
            assertEquals(client.profile().owner(),first.ownerId());
            var second=client.activate(parent.id(),"Successor",Set.of(),Optional.of(first));
            assertEquals(second.start(),client.activity(first.id()).orElseThrow().end().orElseThrow());
            var stopped=client.stopActivity(new StopActivity(second.id(),second.version()));
            assertTrue(stopped.end().isPresent());
            var manual=client.createActivity(new CreateActivity(child.id(),OwnerId.LOCAL,Instant.parse("2026-03-28T23:00:00Z"),Optional.of(Instant.parse("2026-03-29T22:00:00Z")),"DST",Set.of(label.id())));
            var report=client.activities(new ActivityQuery(manual.start(),manual.end().orElseThrow(),Optional.of(OwnerId.LOCAL),Optional.of(child.id()),Optional.empty(),ZoneId.of("Europe/Oslo")));
            assertEquals(Duration.ofHours(23),report.total());
            assertTrue(client.metadata().tasks().stream().anyMatch(task -> task.id().equals(parent.id())));
            var renamed=client.updateProject(new UpdateProject(project.id(),project.version(),"Renamed"));
            var failure=assertThrows(RemoteFailure.class,()->client.updateProject(new UpdateProject(project.id(),project.version(),"Stale")));
            assertEquals(RemoteFailure.Kind.CONFLICT,failure.kind());
            assertEquals("Renamed",client.project(project.id()).orElseThrow().name());
            client.deleteActivity(new DeleteActivity(manual.id(),manual.version()));
            var closedFirst=client.activity(first.id()).orElseThrow();client.deleteActivity(new DeleteActivity(closedFirst.id(),closedFirst.version()));
            client.deleteActivity(new DeleteActivity(stopped.id(),stopped.version()));
            client.deleteTask(new DeleteTask(child.id(),child.version()));client.deleteTask(new DeleteTask(parent.id(),parent.version()));
            client.deleteLabel(new DeleteLabel(label.id(),label.version()));client.deleteProject(new DeleteProject(renamed.id(),renamed.version()));
        }
    }
    @Test void authenticationAndForeignScopeFailuresRemainTyped() {
        try(var invalid=new HttpTimekeeperService(server,()->"invalid-test-token",Duration.ofSeconds(2));var a=new HttpTimekeeperService(server,()->"test-a",Duration.ofSeconds(2));var b=new HttpTimekeeperService(server,()->"test-b",Duration.ofSeconds(2))) {
            assertEquals(RemoteFailure.Kind.AUTHENTICATION,assertThrows(RemoteFailure.class,invalid::projects).kind());
            var project=a.createProject(new CreateProject("Private client project"));assertTrue(b.project(project.id()).isEmpty());
            assertEquals(RemoteFailure.Kind.FORBIDDEN,assertThrows(RemoteFailure.class,()->a.activeActivity(new OwnerId("foreign"))).kind());
            a.deleteProject(new DeleteProject(project.id(),project.version()));
        }
    }
}
