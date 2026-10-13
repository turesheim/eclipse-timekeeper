package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Another authorized session must refresh Eclipse without replacing its open editor draft. */
@SuppressWarnings("restriction")
final class RemoteSynchronizationScenario {
    static void run(HttpTimekeeperService api,Project project) throws Exception {
        try(var peer=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::observerBToken,Duration.ofSeconds(5));
                var otherOwner=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::accountAToken,Duration.ofSeconds(5))) {
            long otherOwnerRevision=otherOwner.metadata().revision();
            var task=peer.createTask(new CreateTask(Optional.of(project.id()),"Automatically synchronized task",Optional.empty()));
            var label=peer.createLabel(new CreateLabel("Automatically synchronized label",Optional.empty()));
            awaitSnapshot(task);
            RemoteTestSupport.waitFor(()->{
                try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().label(label.id()).isPresent());}
                catch(Exception loading) {return false;}
            },"Peer label did not arrive without manual refresh");
            var bot=new SWTWorkbenchBot();var tree=bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree");
            RemoteTestSupport.waitFor(()->{
                try {return tree.getTreeItem(project.name()).expand().getNode(task.summary())!=null;}
                catch(RuntimeException loading) {return false;}
            },"Peer task was not rendered automatically");
            var date=LocalDate.now(ZoneOffset.UTC);
            var start=date.atTime(8,0,0,123456789).toInstant(ZoneOffset.UTC);
            var end=date.atTime(9,0,0,987654321).toInstant(ZoneOffset.UTC);
            var activity=peer.createActivity(new CreateActivity(task.id(),OwnerId.LOCAL,start,Optional.of(end),"Automatically synchronized interval",Set.of(label.id())));
            var query=new ActivityQuery(date.atStartOfDay().toInstant(ZoneOffset.UTC),date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),Optional.of(OwnerId.LOCAL),Optional.of(task.id()),Optional.empty(),ZoneId.of("UTC"));
            RemoteTestSupport.waitFor(()->{
                try {return tree.getTreeItem(project.name()).getNode(task.summary()).expand().getNode(activity.summary())!=null;}
                catch(RuntimeException loading) {return false;}
            },"Peer interval was not rendered automatically");
            awaitReport(query,Optional.of(activity));
            tree.getTreeItem(project.name()).getNode(task.summary()).contextMenu("Edit task...").click();
            var shell=bot.shell("Edit task");var editor=shell.bot();
            String draft="Open draft retained through notification",url="https://example.invalid/live-notification-draft";
            editor.textWithLabel("Summary").setText(draft);editor.textWithLabel("URL (optional)").setText(url);
            var changed=peer.updateTask(new UpdateTask(task.id(),task.version(),task.projectId(),task.parentTaskId(),"Peer changed task while editor open",Optional.of("https://example.invalid/live-peer")));
            var changedActivity=peer.updateActivity(new UpdateActivity(activity.id(),activity.version(),start,Optional.of(end.plusSeconds(60)),"Peer changed interval",Set.of(label.id())));
            awaitSnapshot(changed);
            awaitReport(query,Optional.of(changedActivity));
            var foreignPage=otherOwner.changeBatch(otherOwnerRevision,0);
            assertFalse(foreignPage.changed());assertFalse(foreignPage.resynchronize());assertEquals(otherOwnerRevision,foreignPage.nextSequence());
            assertEquals(draft,editor.textWithLabel("Summary").getText());assertEquals(url,editor.textWithLabel("URL (optional)").getText());
            assertEquals(changed,api.task(task.id()).orElseThrow());editor.button("OK").click();
            RemoteTestSupport.waitFor(()->{
                try {return editor.button("OK").isEnabled() && RemoteTestSupport.onUi(()->visibleMessage(shell.widget,"This record has changed. Close and reopen the editor before saving."));}
                catch(Exception loading) {return false;}
            },"Live notification upgraded the editor or failed to preserve its conflict");
            assertEquals(draft,editor.textWithLabel("Summary").getText());assertEquals(url,editor.textWithLabel("URL (optional)").getText());
            assertEquals(changed,api.task(task.id()).orElseThrow());editor.button("Cancel").click();
            RemoteTestSupport.waitFor(()->!shell.isOpen(),"Live-conflict editor did not close after cancel");

            RemoteBootstrap.setAccountBEnabled(false);
            Task missed;
            try {
                RemoteTestSupport.waitFor(()->!TimekeeperUiPlugin.getDefault().isBackendReady(),"Long-poll token revocation was not reflected in connection state");
                RemoteTestSupport.closedLocalStorage();
                missed=peer.createTask(new CreateTask(Optional.of(project.id()),"Missed event during revoked session",Optional.empty()));
            } finally {RemoteBootstrap.setAccountBEnabled(true);}
            awaitSnapshot(missed);RemoteTestSupport.readyWithMylyn();
            assertEquals(missed,api.task(missed.id()).orElseThrow());
            peer.deleteActivity(new DeleteActivity(changedActivity.id(),changedActivity.version()));
            awaitReport(query,Optional.empty());
            peer.deleteTask(new DeleteTask(changed.id(),changed.version()));peer.deleteTask(new DeleteTask(missed.id(),missed.version()));
            peer.deleteLabel(new DeleteLabel(label.id(),label.version()));
            RemoteTestSupport.waitFor(()->{
                try {return RemoteTestSupport.onUi(()->{
                    var service=TimekeeperUiPlugin.getDefault().getTimekeeperService();
                    return service.task(changed.id()).isEmpty() && service.task(missed.id()).isEmpty() && service.label(label.id()).isEmpty();
                });}catch(Exception loading) {return false;}
            },"Peer deletions were not removed from the Eclipse cache automatically");
            awaitReport(query,Optional.empty());
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(changed).isEmpty() && MylynIntegration.getDefault().linked(missed).isEmpty(),"Live peer deletion left stale Mylyn projections");
            RemoteBootstrap.receipt().setProperty("phase.changes.owner-scope","passed");
            RemoteBootstrap.receipt().setProperty("phase.changes.activity-report","passed");
            RemoteBootstrap.receipt().setProperty("phase.changes.live-draft","passed");
            RemoteBootstrap.receipt().setProperty("phase.changes.reconnect","passed");RemoteBootstrap.writeProof();
        }
    }
    private static void awaitReport(ActivityQuery query,Optional<net.resheim.eclipse.timekeeper.domain.Activity> expected) {
        RemoteTestSupport.waitFor(()->{
            try {return RemoteTestSupport.onUi(()->{
                var report=TimekeeperUiPlugin.getDefault().getTimekeeperService().activities(query);
                return report.activities().equals(expected.stream().toList()) && report.total().equals(expected.map(activity->Duration.between(activity.start(),activity.end().orElseThrow())).orElse(Duration.ZERO));
            });}catch(Exception loading) {return false;}
        },"Peer interval change did not refresh the cached report and exact total");
    }
    private static void awaitSnapshot(Task expected) {
        RemoteTestSupport.waitFor(()->{
            try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().task(expected.id()).filter(expected::equals).isPresent());}
            catch(Exception loading) {return false;}
        },"Peer task revision did not reach the Eclipse snapshot without manual refresh");
    }
    private static boolean visibleMessage(Composite parent,String expected) {
        for(var child:parent.getChildren()) {
            if(child instanceof Text text && text.isVisible() && text.getText().trim().equals(expected)) return true;
            if(child instanceof Composite composite && visibleMessage(composite,expected)) return true;
        }
        return false;
    }
}
