package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;

/** Installed released Mylyn activation events must mutate the packaged remote server. */
@SuppressWarnings("restriction")
public class RemoteMylynTest {
    @Test public void taskListActivationSwitchStopAndLateEventsUseLinkedRemoteIds() throws Exception {
        RemoteTestSupport.readyWithMylyn();RemoteTestSupport.showWorkweek();
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault()!=null && TimekeeperUiPlugin.getDefault().getTaskIntegration().isPresent(),"Mylyn adapter did not register");
        try(var api=RemoteTestSupport.client()) {
            var project=api.createProject(new CreateProject("Installed Mylyn remote project"));
            var first=api.createTask(new CreateTask(Optional.of(project.id()),"Remote Mylyn first",Optional.empty()));
            var second=api.createTask(new CreateTask(Optional.of(project.id()),"Remote Mylyn second",Optional.empty()));
            RemoteTestSupport.onUi(()->{
                TimekeeperUiPlugin.getDefault().getPreferenceStore().setValue(PreferenceConstants.PROJECT_NATIVE_TASKS,true);
                TimekeeperUiPlugin.getDefault().refreshBackend();return null;
            });
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(first).isPresent() && MylynIntegration.getDefault().linked(second).isPresent(),"Remote tasks were not projected into installed Task List");
            var firstProjection=MylynIntegration.getDefault().linked(first).orElseThrow();
            var secondProjection=MylynIntegration.getDefault().linked(second).orElseThrow();
            String marker=UUID.randomUUID().toString();
            RemoteTestSupport.onUi(()->{
                firstProjection.setAttribute("timekeeper.remote.restart-marker",marker);
                org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getTaskList().notifyElementChanged((org.eclipse.mylyn.internal.tasks.core.AbstractTask)firstProjection);
                TasksUi.getTaskActivityManager().activateTask(firstProjection);return null;
            });
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(a->a.taskId().equals(first.id())).isPresent(),"Mylyn activation did not start the linked remote task");
            var original=api.activeActivity(OwnerId.LOCAL).orElseThrow();assertEquals("remote-owner-a",original.ownerId().value());
            waitUiActive(first.id());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().activateTask(secondProjection);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(a->a.taskId().equals(second.id())).isPresent(),"Mylyn switch did not activate the second remote task");
            var successor=api.activeActivity(OwnerId.LOCAL).orElseThrow();waitUiActive(second.id());
            assertTrue(api.activity(original.id()).orElseThrow().end().isPresent());
            AtomicBoolean barrier=new AtomicBoolean();
            RemoteTestSupport.onUi(()->{
                MylynIntegration.getDefault().deactivated(firstProjection);
                TimekeeperUiPlugin.getDefault().runRead("Observe late Mylyn event",()->TimekeeperUiPlugin.getDefault().getTimekeeperService().activeActivity(OwnerId.LOCAL),value->barrier.set(true),failure->{throw new AssertionError(failure);});
                return null;
            });
            RemoteTestSupport.waitFor(barrier::get,"Late event barrier did not complete");assertEquals(successor,api.activeActivity(OwnerId.LOCAL).orElseThrow());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().deactivateTask(secondProjection);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).isEmpty(),"Mylyn deactivation did not stop remote tracking");
            assertTrue(api.activity(successor.id()).orElseThrow().end().isPresent());
            var taskUi=org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getDefault();
            var dataDirectory=java.nio.file.Path.of(taskUi.getDataDirectory()).toAbsolutePath();
            assertTrue("Mylyn fixture data must remain in the isolated test installation",dataDirectory.startsWith(RemoteBootstrap.fixtureFolder().getParent()));
            taskUi.getExternalizationManager().saveNow();
            if(TimekeeperUiPlugin.getDefault().getPreferenceStore() instanceof org.eclipse.jface.preference.IPersistentPreferenceStore persistent) persistent.save();
            var proof=RemoteBootstrap.receipt();proof.setProperty("mylyn.first",first.id().value().toString());proof.setProperty("mylyn.second",second.id().value().toString());
            proof.setProperty("mylyn.original",original.id().value().toString());proof.setProperty("mylyn.successor",successor.id().value().toString());
            proof.setProperty("mylyn.second.handle",secondProjection.getHandleIdentifier());proof.setProperty("mylyn.marker",marker);proof.setProperty("phase.mylyn","passed");RemoteBootstrap.writeProof();
        }
        RemoteTestSupport.readyWithMylyn();
    }
    static void waitUiActive(net.resheim.eclipse.timekeeper.domain.TaskId id) {
        RemoteTestSupport.waitFor(()->{
            try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().activeActivity(OwnerId.LOCAL).filter(a->a.taskId().equals(id)).isPresent());}
            catch(Exception loading) {return false;}
        },"UI snapshot did not acknowledge the remote activity");
    }
}
