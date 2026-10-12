package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.client.RemoteFailure;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Live token reassignment must retain the selected owner, cached data and native draft. */
final class RemoteOwnerBindingScenario {
    static void run(HttpTimekeeperService original,Project project) throws Exception {
        try(var observer=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::observerBToken,Duration.ofSeconds(5))) {
            var task=observer.createTask(new CreateTask(Optional.of(project.id()),"Owner binding draft task",Optional.empty()));
            var bot=new SWTWorkbenchBot();var tree=bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree");
            RemoteTestSupport.waitFor(()->{
                try {return tree.getTreeItem(project.name()).expand().getNode(task.summary())!=null;}catch(RuntimeException loading) {return false;}
            },"Owner binding task did not appear");
            tree.getTreeItem(project.name()).getNode(task.summary()).contextMenu("Edit task...").click();
            var shell=bot.shell("Edit task");var controls=shell.bot();String draft="Retained through owner reassignment",url="https://example.invalid/owner-binding-draft";
            controls.textWithLabel("Summary").setText(draft);controls.textWithLabel("URL (optional)").setText(url);
            var before=observer.metadata();String identity=TimekeeperUiPlugin.getDefault().backendIdentity();
            RemoteBootstrap.setAccountBOwner(false);
            try(var selected=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::accountBToken,Duration.ofSeconds(5))) {
                assertEquals("remote-owner-c",selected.profile().owner().value());var otherBefore=selected.metadata();
                RemoteTestSupport.waitFor(()->!TimekeeperUiPlugin.getDefault().isBackendReady(),"Long polling did not reject live owner reassignment");
                RemoteTestSupport.closedLocalStorage();assertEquals(identity,TimekeeperUiPlugin.getDefault().backendIdentity());
                assertTrue(RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().task(task.id()).isPresent()));
                var failure=assertThrows(RemoteFailure.class,()->original.createProject(new CreateProject("Must not appear in another owner")));
                assertEquals(RemoteFailure.Kind.AUTHENTICATION,failure.kind());assertEquals("OWNER_CHANGED",failure.code());
                assertEquals(RemoteFailure.Kind.AUTHENTICATION,assertThrows(RemoteFailure.class,original::exportData).kind());
                controls.button("OK").click();
                RemoteTestSupport.waitFor(()->{
                    try {return controls.button("OK").isEnabled() && RemoteTestSupport.onUi(()->visibleMessage(shell.widget,"The account for this session has changed. Start a new session with the intended account."));}
                    catch(Exception loading) {return false;}
                },"Owner-changed native save did not retain its enabled draft");
                assertEquals(draft,controls.textWithLabel("Summary").getText());assertEquals(url,controls.textWithLabel("URL (optional)").getText());
                assertEquals(task,observer.task(task.id()).orElseThrow());assertEquals(before,observer.metadata());assertEquals(otherBefore,selected.metadata());
                var intentional=selected.createProject(new CreateProject("Explicit new-owner session"));
                assertTrue(observer.project(intentional.id()).isEmpty());selected.deleteProject(new DeleteProject(intentional.id(),intentional.version()));
            } finally {RemoteBootstrap.setAccountBOwner(true);}
            RemoteTestSupport.waitFor(()->TimekeeperUiPlugin.getDefault().isBackendReady(),"Restoring the original owner did not recover its existing session");
            controls.button("OK").click();RemoteTestSupport.waitFor(()->!shell.isOpen(),"Restored original owner did not save the same draft");
            var saved=observer.task(task.id()).orElseThrow();assertEquals(task.version()+1,saved.version());assertEquals(draft,saved.summary());assertEquals(Optional.of(url),saved.url());
            observer.deleteTask(new DeleteTask(saved.id(),saved.version()));
            RemoteTestSupport.waitFor(()->{
                try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().task(task.id()).isEmpty());}
                catch(Exception loading) {return false;}
            },"Owner binding task was not removed after recovery");
            RemoteBootstrap.receipt().setProperty("phase.owner.binding","passed");RemoteBootstrap.writeProof();
        }
    }
    private static boolean visibleMessage(Composite parent,String expected) {
        for(var child:parent.getChildren()) {
            if(child instanceof Text text && text.isVisible() && text.getText().trim().equals(expected)) return true;
            if(child instanceof Composite composite && visibleMessage(composite,expected)) return true;
        }
        return false;
    }
}
