package net.resheim.eclipse.timekeeper.jira.test;

import static org.junit.Assert.*;
import static net.resheim.eclipse.timekeeper.jira.test.JiraTestSupport.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;

/** Executed by a second Tycho fork against the same file-backed H2 database. */
public class JiraRestartTest {
    @Test public void reconnectAfterEclipseRestartPreservesUuidHistoryAndLabels() throws Exception {
        ready();
        var evidence = readProof();
        assertNotEquals("Acceptance must really use two Eclipse processes", evidence.getProperty("firstProcess"), Long.toString(ProcessHandle.current().pid()));
        var id = new TaskId(UUID.fromString(evidence.getProperty("task")));
        var activityId = new ActivityId(UUID.fromString(evidence.getProperty("activity")));
        var task = service().task(id).orElseThrow();
        var interval = service().activity(activityId).orElseThrow();
        assertEquals(id, interval.taskId());
        assertEquals("Edited while Jira unavailable", interval.summary());
        assertTrue(interval.end().isPresent());
        assertTrue(interval.labelIds().stream().anyMatch(label -> label.value().toString().equals(evidence.getProperty("label"))));
        try (var fixture = new JiraRestFixture(JiraRestFixture.Auth.BASIC, Integer.parseInt(evidence.getProperty("port")))) {
            fixture.moved = true;
            var repository = repository(fixture, JiraRestFixture.Auth.BASIC, JiraRestFixture.TOKEN);
            try {
                query(repository);
                var reconnected = importTask(repository, fetch(repository, "1042"));
                assertEquals("MOVED-42", reconnected.getTaskKey());
                assertEquals(task.id(), onUi(() -> MylynIntegration.getDefault().resolve(reconnected)).id());
                assertEquals(activityId, service().activity(activityId).orElseThrow().id());
                assertEquals(Optional.of("MOVED-42"), onUi(() -> MylynIntegration.getDefault().displayId(service().task(id).orElseThrow())));
                assertTrue(fixture.unexpected().toString(), fixture.unexpected().isEmpty());
            } finally { remove(repository); }
        }
        Files.writeString(proof().getParent().resolve("restart-verified.txt"), "Passed in second Eclipse process " + ProcessHandle.current().pid() + "\nTask: " + id + "\nActivity: " + activityId + "\n");
    }
}
