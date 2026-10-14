package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Actual native deletions with confirmed 204 commits and missing replies. */
final class UncertainDeletionScenario {
    static void run(HttpTimekeeperService api) throws Exception {
        var original=api.metadata();
        // Keep the fixture in the past even when CI runs before 08:00.
        var day=LocalDate.now(api.profile().zone()).minusWeeks(1);
        var query=new ActivityQuery(day.atStartOfDay(api.profile().zone()).toInstant(),day.plusDays(1).atStartOfDay(api.profile().zone()).toInstant(),Optional.of(OwnerId.LOCAL),Optional.empty(),Optional.empty(),api.profile().zone());
        var originalActivities=api.activities(query);
        var view=new SWTWorkbenchBot().viewByTitle("Workweek");
        view.toolbarButton("Show current week").click();
        view.toolbarButton("Show previous week").click();
        for(boolean cancel:List.of(false,true)) {
            String prefix="Unknown deletion "+UUID.randomUUID().toString().substring(0,8);
            var project=api.createProject(new CreateProject(prefix+" project"));
            var task=api.createTask(new CreateTask(Optional.of(project.id()),prefix+" task",Optional.empty()));
            var activity=api.createActivity(new CreateActivity(task.id(),OwnerId.LOCAL,day.atTime(8,0).atZone(api.profile().zone()).toInstant(),Optional.of(day.atTime(9,0).atZone(api.profile().zone()).toInstant()),prefix+" activity",Set.of()));
            refresh();
            var bot=new SWTWorkbenchBot();var tree=bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree");
            RemoteTestSupport.waitFor(()->{try {return tree.getTreeItem(project.name()).expand().getNode(task.summary()).expand().getNode(activity.summary())!=null;}catch(RuntimeException loading) {return false;}},"Deletion fixture interval did not reach the real native tree");
            held("activities",activity.id().value(),cancel,
                ()->tree.getTreeItem(project.name()).getNode(task.summary()).getNode(activity.summary()).contextMenu("Delete activity").click(),
                ()->api.activity(activity.id()).isEmpty(),
                ()->cachedMissing(activity));
            assertEquals(task,api.task(task.id()).orElseThrow());
            assertEquals(project,api.project(project.id()).orElseThrow());
            held("tasks",task.id().value(),cancel,()->{
                tree.getTreeItem(project.name()).getNode(task.summary()).contextMenu("Delete task...").click();
                bot.shell("Delete task").bot().button("OK").click();
            },()->api.task(task.id()).isEmpty(),()->cachedMissing(task));
            assertEquals(project,api.project(project.id()).orElseThrow());
            held("projects",project.id().value(),cancel,()->{
                tree.getTreeItem(project.name()).contextMenu("Delete project...").click();
                bot.shell("Delete project").bot().button("OK").click();
            },()->api.project(project.id()).isEmpty(),()->cachedMissing(project));
            var after=api.metadata();
            assertEquals(original.projects(),after.projects());assertEquals(original.tasks(),after.tasks());
            assertEquals(original.labels(),after.labels());assertEquals(original.active(),after.active());assertEquals(original.profile(),after.profile());
            assertEquals(originalActivities.activities(),api.activities(query).activities());assertEquals(originalActivities.total(),api.activities(query).total());
            RemoteTestSupport.closedLocalStorage();
        }
        view.toolbarButton("Show current week").click();
        RemoteBootstrap.writeProof();
    }
    private static void held(String collection,UUID id,boolean cancel,Runnable delete,BooleanSupplier deleted,BooleanSupplier reconciled) throws Exception {
        RemoteBootstrap.holdNextDeleteReply(collection,id);
        try {
            delete.run();RemoteBootstrap.awaitDeleteReplyCommitted();
            assertTrue("Server did not commit the requested deletion",deleted.getAsBoolean());
            UncertainTrackingScenario.outcome(cancel,"Update Timekeeper","Timekeeper");
            assertTrue("Unknown deletion recreated its committed record",deleted.getAsBoolean());
            RemoteTestSupport.closedLocalStorage();
        } finally {RemoteBootstrap.releaseTaskReply();}
        refresh();
        RemoteTestSupport.waitFor(reconciled,"Committed deletion did not disappear from the installed canonical snapshot");
        assertTrue(deleted.getAsBoolean());
        RemoteBootstrap.receipt().setProperty("phase.deletion."+(cancel ? "cancel" : "timeout")+"."+collection,"passed");
    }
    private static boolean cachedMissing(Object record) {
        try {return RemoteTestSupport.onUi(()->{
            var service=TimekeeperUiPlugin.getDefault().getTimekeeperService();
            if(record instanceof Activity value) return service.activity(value.id()).isEmpty();
            if(record instanceof Task value) return service.task(value.id()).isEmpty();
            if(record instanceof Project value) return service.project(value.id()).isEmpty();
            throw new IllegalArgumentException("Unsupported deletion witness");
        });}catch(Exception refreshing) {return false;}
    }
    private static void refresh() {
        try {RemoteTestSupport.onUi(()->{TimekeeperUiPlugin.getDefault().refreshBackend();return null;});RemoteTestSupport.ready();}
        catch(Exception failure) {throw new IllegalStateException(failure);}
    }
}
