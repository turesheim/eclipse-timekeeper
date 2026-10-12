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
