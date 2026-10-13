package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Actual native tracking controls after confirmed commits with missing replies. */
final class UncertainTrackingScenario {
    static void run(HttpTimekeeperService api) throws Exception {
        assertTrue(api.activeActivity(OwnerId.LOCAL).isEmpty());
        RemoteBootstrap.enableReplyGate();
        RemoteTestSupport.ready();
        var project=api.createProject(new CreateProject("Unknown tracking "+UUID.randomUUID()));
        for(boolean cancel:List.of(false,true)) {
            var task=api.createTask(new CreateTask(Optional.of(project.id()),cancel ? "Cancelled tracking" : "Timed out tracking",Optional.empty()));
            awaitTask(project,task);
            var date=LocalDate.now(api.profile().zone());
            var query=new ActivityQuery(date.atStartOfDay(api.profile().zone()).toInstant(),date.plusDays(1).atStartOfDay(api.profile().zone()).toInstant(),Optional.of(OwnerId.LOCAL),Optional.of(task.id()),Optional.empty(),api.profile().zone());
            Activity started;
            RemoteBootstrap.holdNextTrackingReply(null);
            try {
                menu(project,task,"Activate task");
                RemoteBootstrap.awaitTrackingReplyCommitted(true);
                started=api.activeActivity(OwnerId.LOCAL).orElseThrow();
                assertEquals(task.id(),started.taskId());
                assertEquals(1,api.activities(query).activities().size());
                outcome(cancel,"Activate Timekeeper task","Could not activate task");
                assertEquals(started,api.activeActivity(OwnerId.LOCAL).orElseThrow());
                assertEquals(List.of(started),api.activities(query).activities());
                RemoteTestSupport.closedLocalStorage();
            } finally {RemoteBootstrap.releaseTaskReply();}
            refresh();
            var original=started;
            RemoteTestSupport.waitFor(()->snapshot().filter(original::equals).isPresent(),"Confirmed start did not reconcile to the original interval");
            // After review, the next actual menu action is Stop, never a repeated Start.
            RemoteBootstrap.holdNextTrackingReply(started.id().value());
            Activity stopped;
            try {
                menu(project,task,"Deactivate task");
                RemoteBootstrap.awaitTrackingReplyCommitted(false);
                stopped=api.activity(started.id()).orElseThrow();
                assertTrue(stopped.end().isPresent());assertEquals(started.start(),stopped.start());
                assertEquals(started.version()+1,stopped.version());
                outcome(cancel,"Update Timekeeper","Timekeeper");
                assertEquals(stopped,api.activity(started.id()).orElseThrow());
                assertTrue(api.activeActivity(OwnerId.LOCAL).isEmpty());
                assertEquals(List.of(stopped),api.activities(query).activities());
                RemoteTestSupport.closedLocalStorage();
            } finally {RemoteBootstrap.releaseTaskReply();}
            refresh();
            RemoteTestSupport.waitFor(()->snapshot().isEmpty(),"Confirmed stop remained active after review");
            assertEquals(stopped,api.activity(started.id()).orElseThrow());
            assertEquals(1,api.activities(query).activities().size());
            RemoteBootstrap.receipt().setProperty("phase.tracking."+ (cancel ? "cancel" : "timeout")+".start","passed");
            RemoteBootstrap.receipt().setProperty("phase.tracking."+ (cancel ? "cancel" : "timeout")+".stop","passed");
            api.deleteActivity(new DeleteActivity(stopped.id(),stopped.version()));
            var current=api.task(task.id()).orElseThrow();api.deleteTask(new DeleteTask(current.id(),current.version()));
        }
        var current=api.project(project.id()).orElseThrow();api.deleteProject(new DeleteProject(current.id(),current.version()));
        refresh();RemoteTestSupport.closedLocalStorage();RemoteBootstrap.writeProof();
    }
    private static Optional<Activity> snapshot() {
        try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().activeActivity(OwnerId.LOCAL));}
        catch(Exception failure) {throw new IllegalStateException(failure);}
    }
    private static void outcome(boolean cancel,String jobName,String title) throws Exception {
        Job operation=null;
        if(cancel) {
            operation=Arrays.stream(Job.getJobManager().find(null)).filter(job->job.getName().equals(jobName) && job.getState()==Job.RUNNING).findFirst().orElseThrow();
            operation.cancel();
        }
        String expected=cancel ? "The request was cancelled. Refresh to confirm whether the server completed it." : "The server request timed out. Refresh before repeating a change.";
        var bot=new SWTWorkbenchBot();var shell=bot.shell(title);
        RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->message(shell.widget,expected));}catch(Exception pending) {return false;}},"Missing tracking outcome instruction");
        shell.bot().button("OK").click();
        if(operation!=null) {
            var job=operation;RemoteTestSupport.waitFor(()->job.getState()==Job.NONE,"Cancelled tracking job did not exit");
            assertTrue(job.getResult().matches(org.eclipse.core.runtime.IStatus.CANCEL));
        }
    }
    private static boolean message(Composite parent,String expected) {
        for(var child:parent.getChildren()) {
            if(child instanceof Label label && label.isVisible() && label.getText().contains(expected)) return true;
            if(child instanceof Text text && text.isVisible() && text.getText().contains(expected)) return true;
            if(child instanceof Composite composite && message(composite,expected)) return true;
        }
        return false;
    }
    private static void awaitTask(Project project,Task task) {
        refresh();
        RemoteTestSupport.waitFor(()->{try {return new SWTWorkbenchBot().viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary())!=null;}catch(RuntimeException pending) {return false;}},"Tracking fixture did not reach native tree");
    }
    private static void menu(Project project,Task task,String name) {
        var tree=new SWTWorkbenchBot().viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree");
        RemoteTestSupport.waitFor(()->{try {return tree.getTreeItem(project.name()).expand().getNode(task.summary()).contextMenu(name).isEnabled();}catch(RuntimeException pending) {return false;}},"Tracking menu did not reflect reviewed server state");
        tree.getTreeItem(project.name()).getNode(task.summary()).contextMenu(name).click();
    }
    private static void refresh() {
        try {RemoteTestSupport.onUi(()->{TimekeeperUiPlugin.getDefault().refreshBackend();return null;});RemoteTestSupport.ready();}
        catch(Exception failure) {throw new IllegalStateException(failure);}
    }
}
