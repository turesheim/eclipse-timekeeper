package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.util.UUID;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;

public class RemoteMylynRestartTest {
    @Test public void restartedTaskListReusesItsRemoteMappingAndPreservesHistory() throws Exception {
        RemoteTestSupport.readyWithMylyn();assertEquals("passed",RemoteBootstrap.receipt().getProperty("phase.mylyn"));
        try(var api=RemoteTestSupport.client()) {
            var first=api.task(new TaskId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.first")))).orElseThrow();
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault()!=null && MylynIntegration.getDefault().linked(first).isPresent(),"Persisted remote Mylyn projection was not reconnected");
            var projection=MylynIntegration.getDefault().linked(first).orElseThrow();
            assertEquals(RemoteBootstrap.receipt().getProperty("mylyn.marker"),projection.getAttribute("timekeeper.remote.restart-marker"));
            var original=api.activity(new ActivityId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.original")))).orElseThrow();
            var successor=api.activity(new ActivityId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.successor")))).orElseThrow();
            assertTrue(original.end().isPresent());assertTrue(successor.end().isPresent());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().activateTask(projection);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(a->a.taskId().equals(first.id())).isPresent(),"Reconnected projection did not start its original remote UUID");
            var current=api.activeActivity(OwnerId.LOCAL).orElseThrow();assertNotEquals(original.id(),current.id());
            RemoteMylynTest.waitUiActive(first.id());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().deactivateTask(projection);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).isEmpty(),"Reconnected projection did not stop remotely");
            assertEquals(original,api.activity(original.id()).orElseThrow());assertEquals(successor,api.activity(successor.id()).orElseThrow());
            String[] pids=RemoteBootstrap.receipt().getProperty("server.pids").split(",");assertEquals(2,pids.length);assertNotEquals(pids[0],pids[1]);
            RemoteBootstrap.receipt().setProperty("phase.mylyn.restart","passed");RemoteBootstrap.writeProof();
        }
        RemoteTestSupport.readyWithMylyn();
    }
}
