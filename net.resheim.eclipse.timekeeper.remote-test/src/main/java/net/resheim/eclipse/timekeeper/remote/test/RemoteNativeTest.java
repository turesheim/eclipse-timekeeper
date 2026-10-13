package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Actual installed Workweek controls against the packaged Quarkus server, without Mylyn. */
public class RemoteNativeTest {
    @Test public void installedRemoteProjectTaskActivityTrackingAndCsvRoundTrip() throws Exception {
        RemoteTestSupport.ready();RemoteTestSupport.showWorkweek();
        var bot=new SWTWorkbenchBot();var view=bot.viewByTitle("Workweek");view.setFocus();
        try(var api=RemoteTestSupport.client()) {
            view.toolbarButton("Create a Timekeeper project").click();
            bot.shell("New project").bot().textWithLabel("Name").setText("Installed remote project");bot.shell("New project").bot().button("OK").click();
            RemoteTestSupport.waitFor(()->api.projects().stream().anyMatch(p->p.name().equals("Installed remote project")),"Project was not stored remotely");
            var createdProject=api.projects().stream().filter(p->p.name().equals("Installed remote project")).findFirst().orElseThrow();
            var project=createdProject;
            RemoteTestSupport.waitFor(()->{try {return view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name())!=null;}catch(RuntimeException loading) {return false;}},"Remote project did not appear");
            view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).select();
            view.toolbarButton("Create a Timekeeper task").click();
            bot.shell("New task").bot().textWithLabel("Summary").setText("Installed remote task");bot.shell("New task").bot().button("OK").click();
            RemoteTestSupport.waitFor(()->api.tasks().stream().anyMatch(t->t.summary().equals("Installed remote task")),"Task was not stored remotely");
            var createdTask=api.tasks().stream().filter(t->t.summary().equals("Installed remote task")).findFirst().orElseThrow();assertEquals(Optional.of(project.id()),createdTask.projectId());
            RemoteTestSupport.waitFor(()->{try {return view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(createdTask.summary())!=null;}catch(RuntimeException loading) {return false;}},"Created task did not appear");
            view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(createdTask.summary()).contextMenu("Edit task...").click();
            bot.shell("Edit task").bot().textWithLabel("Summary").setText("Edited remote task");bot.shell("Edit task").bot().textWithLabel("URL (optional)").setText("https://example.invalid/remote-task");
            bot.shell("Edit task").bot().button("OK").click();
            RemoteTestSupport.waitFor(()->api.task(createdTask.id()).orElseThrow().summary().equals("Edited remote task"),"Task edit was not stored remotely");
            var task=api.task(createdTask.id()).orElseThrow();assertEquals(createdTask.id(),task.id());assertEquals(Optional.of("https://example.invalid/remote-task"),task.url());
            RemoteTestSupport.waitFor(()->{try {return view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary())!=null;}catch(RuntimeException loading) {return false;}},"Remote task did not appear");
            view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary()).contextMenu("Activate task").click();
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(a->a.taskId().equals(task.id())).isPresent(),"Remote tracking was not started");
            var started=api.activeActivity(OwnerId.LOCAL).orElseThrow();assertEquals("remote-owner-a",started.ownerId().value());assertFalse(started.manual());
            RemoteTestSupport.waitFor(()->TimekeeperUiPlugin.getDefault().isBackendReady(),"Tracking refresh did not finish");
            // Wait for the actual UI snapshot before the next menu operation.
            RemoteTestSupport.waitFor(()->{
                try {return view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary()).contextMenu("Deactivate task").isEnabled();}
                catch(RuntimeException loading) {return false;}
            },"Deactivate action did not reflect the server activity");
            view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary()).contextMenu("Deactivate task").click();
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).isEmpty(),"Remote tracking was not stopped");
            var stopped=api.activity(started.id()).orElseThrow();assertTrue(stopped.end().isPresent());
            // Seed a precise interval through the same HTTP port and prove it is retained on restart.
            var date=java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            var from=date.atTime(8,0,0,123456789).toInstant(java.time.ZoneOffset.UTC);
            var until=date.atTime(9,0,0,987654321).toInstant(java.time.ZoneOffset.UTC);
            var manual=api.createActivity(new CreateActivity(task.id(),OwnerId.LOCAL,from,Optional.of(until),"Precise remote interval",Set.of()));
            RemoteTestSupport.onUi(()->{TimekeeperUiPlugin.getDefault().refreshBackend();return null;});
            RemoteTestSupport.waitFor(()->{try {return view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary()).expand().getNode("Precise remote interval")!=null;}catch(RuntimeException loading) {return false;}},"Precise interval did not appear in Workweek");
            view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary()).getNode("Precise remote interval").contextMenu("Edit activity...").click();
            var activityEditor=bot.shell("Edit activity").bot();activityEditor.textWithLabel("Summary").setText("Edited precise interval");
            activityEditor.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(date+" 07:00");activityEditor.button("Save").click();
            RemoteTestSupport.waitFor(()->TimekeeperUiPlugin.getDefault().backendStatus().contains("server rejected") && activityEditor.button("Save").isEnabled(),"Rejected interval did not restore the editor");
            assertEquals(manual,api.activity(manual.id()).orElseThrow());assertEquals("Edited precise interval",activityEditor.textWithLabel("Summary").getText());
            activityEditor.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(date+" 09:00");activityEditor.button("Save").click();
            RemoteTestSupport.waitFor(()->api.activity(manual.id()).orElseThrow().summary().equals("Edited precise interval"),"Activity edit was not stored remotely");
            var edited=api.activity(manual.id()).orElseThrow();assertEquals(from,edited.start());assertEquals(Optional.of(until),edited.end());
            var exported=RemoteBootstrap.folder.resolve("csv-export");
            assertTrue(TimekeeperUiPlugin.getDefault().exportRemoteCsv(exported)>=4);
            assertTrue(TimekeeperUiPlugin.getDefault().importRemoteCsv(exported)>=4);
            RemoteBootstrap.proof.setProperty("manual.start",from.toString());RemoteBootstrap.proof.setProperty("manual.end",until.toString());
            RemoteBootstrap.proof.setProperty("project.id",project.id().value().toString());RemoteBootstrap.proof.setProperty("task.id",task.id().value().toString());
            RemoteBootstrap.proof.setProperty("stopped.id",stopped.id().value().toString());RemoteBootstrap.proof.setProperty("manual.id",manual.id().value().toString());
            UncertainTrackingScenario.run(api);
            RemoteBootstrap.proof.setProperty("phase.native","passed");RemoteBootstrap.writeProof();
        }
        RemoteTestSupport.ready();
        RemoteTestSupport.onUi(()->{
            var page=org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            var closed=(net.resheim.eclipse.timekeeper.ui.views.WorkWeekView)page.findView(net.resheim.eclipse.timekeeper.ui.views.WorkWeekView.VIEW_ID);
            assertNotNull(closed);page.hideView(closed);
            // A queued editor completion must tolerate its view having already closed.
            closed.refreshAll();closed.refresh(null);return null;
        });
    }
}
