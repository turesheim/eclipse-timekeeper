package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** A token saved by the real preference page must select a different owner after restart. */
@SuppressWarnings("restriction")
public class RemoteMylynAccountTest {
    @Test public void switchedAccountRetainsForeignProjectionsWithoutImportingOrMutatingThem() throws Exception {
        RemoteTestSupport.readyWithMylyn();RemoteTestSupport.showWorkweek();
        assertEquals("passed",RemoteBootstrap.receipt().getProperty("phase.mylyn.account-selected"));
        try(var api=RemoteTestSupport.client();var original=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::accountAToken,Duration.ofSeconds(5))) {
            assertEquals("remote-owner-b",api.profile().owner().value());
            var previous=original.metadata();
            assertEquals(Long.parseLong(RemoteBootstrap.receipt().getProperty("mylyn.account-a.revision")),previous.revision());
            assertEquals("remote-owner-a",previous.profile().owner().value());
            var first=original.task(new TaskId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.first")))).orElseThrow();
            var history=original.activity(new ActivityId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.original")))).orElseThrow();
            var successor=original.activity(new ActivityId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.successor")))).orElseThrow();
            assertTrue("Eclipse retained tasks from account A",RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().tasks().isEmpty()));
            var initial=api.metadata();assertTrue(initial.projects().isEmpty());assertTrue(initial.tasks().isEmpty());assertTrue(initial.labels().isEmpty());assertTrue(initial.active().isEmpty());
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault()!=null && TimekeeperUiPlugin.getDefault().getTaskIntegration().isPresent(),"Account B Mylyn integration did not start");
            var foreign=org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getTaskList().getAllTasks().stream()
                .filter(task->RemoteBootstrap.receipt().getProperty("mylyn.marker").equals(task.getAttribute("timekeeper.remote.restart-marker"))).findFirst().orElseThrow();
            assertTrue("Old account mapping leaked into selected account",MylynIntegration.getDefault().linked(first).isEmpty());
            assertThrows(IllegalStateException.class,()->MylynIntegration.getDefault().resolve(foreign));
            assertEquals("Rejecting a foreign projection changed the selected account",initial,api.metadata());
            // Reuse the visible names to ensure categories/projections are identified by scope and UUID.
            var project=api.createProject(new CreateProject("Installed Mylyn remote project"));
            var task=api.createTask(new CreateTask(Optional.of(project.id()),"Remote Mylyn first",Optional.empty()));
            RemoteTestSupport.onUi(()->{TimekeeperUiPlugin.getDefault().refreshBackend();return null;});
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).isPresent(),"Account B task was not projected");
            var projection=MylynIntegration.getDefault().linked(task).orElseThrow();assertNotEquals(foreign.getHandleIdentifier(),projection.getHandleIdentifier());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().activateTask(projection);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(a->a.taskId().equals(task.id())).isPresent(),"Account B activation did not use its own remote UUID");
            var active=api.activeActivity(OwnerId.LOCAL).orElseThrow();assertEquals("remote-owner-b",active.ownerId().value());RemoteMylynTest.waitUiActive(task.id());
            var barrier=new AtomicBoolean();
            RemoteTestSupport.onUi(()->{
                MylynIntegration.getDefault().deactivated(foreign);
                TimekeeperUiPlugin.getDefault().runRead("Observe foreign account event",()->TimekeeperUiPlugin.getDefault().getTimekeeperService().activeActivity(OwnerId.LOCAL),value->barrier.set(true),failure->{throw new AssertionError(failure);});return null;
            });
            RemoteTestSupport.waitFor(barrier::get,"Foreign event barrier did not finish");assertEquals(active,api.activeActivity(OwnerId.LOCAL).orElseThrow());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().deactivateTask(projection);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).isEmpty(),"Account B deactivation did not stop tracking");
            assertEquals(1,api.projects().size());assertEquals(1,api.tasks().size());
            assertEquals(previous,original.metadata());assertEquals(history,original.activity(history.id()).orElseThrow());assertEquals(successor,original.activity(successor.id()).orElseThrow());
            assertEquals(3,RemoteBootstrap.receipt().getProperty("server.pids").split(",").length);
            RemoteBootstrap.receipt().setProperty("mylyn.account-b.task",task.id().value().toString());
            RemoteBootstrap.receipt().setProperty("phase.mylyn.account","passed");RemoteBootstrap.writeProof();
        }
        RemoteTestSupport.readyWithMylyn();
    }
}
