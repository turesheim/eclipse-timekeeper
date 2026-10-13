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
            verifyRejectedDeletions(api,project,task,active.id());
            verifyReadOnlyDeletions(api,project);
            RemoteTaskFailureScenario.run(api,project);
            RemoteSynchronizationScenario.run(api,project);
            RemoteOwnerBindingScenario.run(api,project);
            RemoteWebScenario.run(api);
            RemotePreferenceScenario.run(api);
            RemoteRestoreScenario.run(api,project);
            assertEquals(1,api.projects().size());assertEquals(1,api.tasks().size());
            assertEquals(previous,original.metadata());assertEquals(history,original.activity(history.id()).orElseThrow());assertEquals(successor,original.activity(successor.id()).orElseThrow());
            assertEquals(3,RemoteBootstrap.receipt().getProperty("server.pids").split(",").length);
            RemoteBootstrap.receipt().setProperty("mylyn.account-b.task",task.id().value().toString());
            RemoteBootstrap.receipt().setProperty("mylyn.account-b.interval",active.id().value().toString());
            RemoteBootstrap.receipt().setProperty("phase.mylyn.account","passed");RemoteBootstrap.writeProof();
        }
        RemoteTestSupport.readyWithMylyn();
        BackendSwitchScenario.prepareEndpointSwitch();
    }
    private static void verifyRejectedDeletions(HttpTimekeeperService api,Project project,Task task,ActivityId intervalId) throws Exception {
        var list=org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getTaskList();
        var bot=new org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot();
        var interval=api.activity(intervalId).orElseThrow();assertTrue(interval.end().isPresent());
        var before=api.metadata();
        var projected=MylynIntegration.getDefault().linked(task).orElseThrow();
        RemoteTestSupport.onUi(()->{list.deleteTask(projected);return null;});
        bot.shell("Could not delete Timekeeper record").bot().button("OK").click();
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).isPresent(),"Rejected task deletion did not restore its projection");
        assertEquals(before,api.metadata());assertEquals(interval,api.activity(intervalId).orElseThrow());

        String categoryHandle=net.resheim.eclipse.timekeeper.mylyn.MylynBackendScope.current().category(project.id());
        var category=(org.eclipse.mylyn.internal.tasks.core.TaskCategory)list.getContainerForHandle(categoryHandle);
        assertNotNull(category);
        RemoteTestSupport.onUi(()->{list.deleteCategory(category);return null;});
        bot.shell("Could not delete Timekeeper record").bot().button("OK").click();
        RemoteTestSupport.waitFor(()->list.getContainerForHandle(categoryHandle)!=null && MylynIntegration.getDefault().linked(task).isPresent(),"Rejected project deletion did not restore its category/task hierarchy");
        assertEquals(before,api.metadata());assertEquals(interval,api.activity(intervalId).orElseThrow());
        var restored=MylynIntegration.getDefault().linked(task).orElseThrow();
        assertTrue(list.getContainerForHandle(categoryHandle).getChildren().contains(restored));

        // A subsequent successful deletion proves rejected commands do not keep blocking the queue.
        var empty=api.createTask(new CreateTask(Optional.of(project.id()),"Disposable remote deletion witness",Optional.empty()));
        RemoteTestSupport.onUi(()->{TimekeeperUiPlugin.getDefault().refreshBackend();return null;});
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(empty).isPresent(),"Witness task did not project after rejected deletions");
        var disposable=MylynIntegration.getDefault().linked(empty).orElseThrow();
        RemoteTestSupport.onUi(()->{list.deleteTask(disposable);return null;});
        RemoteTestSupport.waitFor(()->api.task(empty.id()).isEmpty(),"Rejected deletions blocked a later valid deletion");
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(empty).isEmpty() && TimekeeperUiPlugin.getDefault().isBackendReady(),"Valid deletion did not finish refreshing the UI");
        assertEquals(project,api.project(project.id()).orElseThrow());assertEquals(task,api.task(task.id()).orElseThrow());
        assertEquals(interval,api.activity(intervalId).orElseThrow());
        RemoteBootstrap.receipt().setProperty("phase.mylyn.deletion","passed");RemoteBootstrap.writeProof();
    }
    private static void verifyReadOnlyDeletions(HttpTimekeeperService api,Project project) throws Exception {
        var list=org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getTaskList();
        var bot=new org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot();
        var task=api.createTask(new CreateTask(Optional.of(project.id()),"Read-only deletion task",Optional.empty()));
        var emptyProject=api.createProject(new CreateProject("Read-only deletion project"));
        RemoteTestSupport.onUi(()->{TimekeeperUiPlugin.getDefault().refreshBackend();return null;});
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).isPresent()
            && net.resheim.eclipse.timekeeper.mylyn.MylynBackendScope.current().category(emptyProject.id())!=null,"Read-only fixtures were not projected");
        var before=api.metadata();
        RemoteBootstrap.setAccountBWritable(false);
        try {
            verifyReadOnlyTaskEditor(api,project,task);
            var projection=MylynIntegration.getDefault().linked(task).orElseThrow();
            RemoteTestSupport.onUi(()->{list.deleteTask(projection);return null;});
            dismissPermissionRejection(bot);
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).isPresent(),"Permission-rejected task deletion did not restore its projection");
            assertEquals(before,api.metadata());
            String handle=net.resheim.eclipse.timekeeper.mylyn.MylynBackendScope.current().category(emptyProject.id());
            var category=(org.eclipse.mylyn.internal.tasks.core.TaskCategory)list.getContainerForHandle(handle);assertNotNull(category);
            RemoteTestSupport.onUi(()->{list.deleteCategory(category);return null;});
            dismissPermissionRejection(bot);
            RemoteTestSupport.waitFor(()->list.getContainerForHandle(handle)!=null,"Permission-rejected project deletion did not restore its category");
            assertEquals(before,api.metadata());
            RemoteTestSupport.readyWithMylyn();
        } finally {RemoteBootstrap.setAccountBWritable(true);}

        // New backend data forces another reconciliation after writes become available.
        // A stale queued deletion must not silently remove either restored record.
        var witness=api.createTask(new CreateTask(Optional.of(project.id()),"Write-permission recovery witness",Optional.empty()));
        RemoteTestSupport.onUi(()->{TimekeeperUiPlugin.getDefault().refreshBackend();return null;});
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(witness).isPresent(),"Reconciliation did not recover after write permission returned");
        assertEquals(task,api.task(task.id()).orElseThrow());assertEquals(emptyProject,api.project(emptyProject.id()).orElseThrow());
        var restored=MylynIntegration.getDefault().linked(task).orElseThrow();
        var disposable=MylynIntegration.getDefault().linked(witness).orElseThrow();
        RemoteTestSupport.onUi(()->{list.deleteTask(restored);list.deleteTask(disposable);return null;});
        RemoteTestSupport.waitFor(()->api.task(task.id()).isEmpty() && api.task(witness.id()).isEmpty(),"Explicit task deletions did not succeed after permission recovery");
        String handle=net.resheim.eclipse.timekeeper.mylyn.MylynBackendScope.current().category(emptyProject.id());
        var category=(org.eclipse.mylyn.internal.tasks.core.TaskCategory)list.getContainerForHandle(handle);assertNotNull(category);
        RemoteTestSupport.onUi(()->{list.deleteCategory(category);return null;});
        RemoteTestSupport.waitFor(()->api.project(emptyProject.id()).isEmpty(),"Explicit project deletion did not succeed after permission recovery");
        RemoteBootstrap.receipt().setProperty("phase.mylyn.permission","passed");RemoteBootstrap.writeProof();
    }
    private static void verifyReadOnlyTaskEditor(HttpTimekeeperService api,Project project,Task task) throws Exception {
        var bot=new org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot();
        var tree=bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree");
        RemoteTestSupport.waitFor(()->{
            if(!TimekeeperUiPlugin.getDefault().isBackendReady()) return false;
            try {return tree.getTreeItem(project.name()).expand().getNode(task.summary())!=null;}
            catch(RuntimeException loading) {return false;}
        },"Read-only task did not appear in Workweek");
        tree.getTreeItem(project.name()).getNode(task.summary()).contextMenu("Edit task...").click();
        var shell=bot.shell("Edit task");var editor=shell.bot();
        String draft="Unsaved read-only task draft";editor.textWithLabel("Summary").setText(draft);editor.button("OK").click();
        RemoteTestSupport.waitFor(()->editor.button("OK").isEnabled()
            && TimekeeperUiPlugin.getDefault().backendStatus().contains("does not permit"),"Permission-rejected editor did not release the form");
        assertTrue(shell.isOpen());assertTrue(editor.textWithLabel("Summary").isEnabled());
        assertEquals(draft,editor.textWithLabel("Summary").getText());
        assertTrue("Editor did not display the permission error",RemoteTestSupport.onUi(()->hasVisibleText(shell.widget,"This token does not permit the requested change.")));
        assertEquals(task,api.task(task.id()).orElseThrow());editor.button("Cancel").click();
        RemoteTestSupport.waitFor(()->!shell.isOpen(),"Cancelled read-only draft did not close");
    }
    private static boolean hasVisibleText(org.eclipse.swt.widgets.Composite parent,String expected) {
        for(var child:parent.getChildren()) {
            if(child instanceof org.eclipse.swt.widgets.Text text && text.isVisible() && text.getText().trim().equals(expected)) return true;
            if(child instanceof org.eclipse.swt.widgets.Composite composite && hasVisibleText(composite,expected)) return true;
        }
        return false;
    }
    private static void dismissPermissionRejection(org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot bot) {
        var dialog=bot.shell("Could not delete Timekeeper record").bot();
        assertTrue(dialog.label("This token does not permit the requested change.").isVisible());
        dialog.button("OK").click();
    }
}
