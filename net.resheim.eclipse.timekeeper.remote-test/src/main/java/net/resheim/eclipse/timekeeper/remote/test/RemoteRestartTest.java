package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.domain.*;

/** A second installed Eclipse JVM decrypts credentials and reconnects to a second packaged-server JVM. */
public class RemoteRestartTest {
    @Test public void restartReusesEncryptedTokenAndPreservesOwnedRecordsWithoutOpeningLocalStorage() throws Exception {
        RemoteTestSupport.ready();RemoteTestSupport.showWorkweek();assertEquals("passed",RemoteBootstrap.proof.getProperty("phase.native"));
        try(var api=RemoteTestSupport.client()) {
            var project=api.project(new ProjectId(UUID.fromString(RemoteBootstrap.proof.getProperty("project.id")))).orElseThrow();
            var task=api.task(new TaskId(UUID.fromString(RemoteBootstrap.proof.getProperty("task.id")))).orElseThrow();
            assertEquals(Optional.of(project.id()),task.projectId());assertEquals("Edited remote task",task.summary());
            var stopped=api.activity(new ActivityId(UUID.fromString(RemoteBootstrap.proof.getProperty("stopped.id")))).orElseThrow();assertTrue(stopped.end().isPresent());
            var precise=api.activity(new ActivityId(UUID.fromString(RemoteBootstrap.proof.getProperty("manual.id")))).orElseThrow();
            assertEquals(Instant.parse(RemoteBootstrap.proof.getProperty("manual.start")),precise.start());assertEquals(Optional.of(Instant.parse(RemoteBootstrap.proof.getProperty("manual.end"))),precise.end());
            assertEquals("remote-owner-a",precise.ownerId().value());assertEquals("Edited precise interval",precise.summary());
            String[] processes=RemoteBootstrap.proof.getProperty("server.pids").split(",");assertEquals("Initial server, reply-gate relocation and retained-workspace restart",3,processes.length);assertEquals(3,java.util.Set.of(processes).size());
            RemoteBootstrap.proof.setProperty("phase.restart","passed");RemoteBootstrap.writeProof();
        }
        RemoteTestSupport.ready();
    }
}
