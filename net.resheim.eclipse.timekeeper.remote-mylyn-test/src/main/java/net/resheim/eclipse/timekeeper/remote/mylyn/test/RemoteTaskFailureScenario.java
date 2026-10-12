package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.client.RemoteFailure;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Real token revocation and a second HTTP client exercise the installed task editor. */
@SuppressWarnings("restriction")
final class RemoteTaskFailureScenario {
    private static final String AUTH="Sign in to the Timekeeper server with a valid token.";
    private static final String CONFLICT="The record or active task changed on the server. Refresh and review your changes.";
    private static final String STALE="This record has changed. Close and reopen the editor before saving.";
    static void run(HttpTimekeeperService api,Project project) throws Exception {
        authentication(api,project);
        optimisticConflict(api,project);
    }
    private static void authentication(HttpTimekeeperService api,Project project) throws Exception {
        var task=api.createTask(new CreateTask(Optional.of(project.id()),"Authentication task fixture",Optional.empty()));
        var shell=open(project,task);var editor=shell.bot();
        String draft="Draft retained through token revocation",url="https://example.invalid/authentication-draft";
        editor.textWithLabel("Summary").setText(draft);editor.textWithLabel("URL (optional)").setText(url);
        var before=api.metadata();
        RemoteBootstrap.setAccountBEnabled(false);
        try {
            assertEquals(RemoteFailure.Kind.AUTHENTICATION,assertThrows(RemoteFailure.class,api::profile).kind());
            editor.button("OK").click();
            rejected(shell,AUTH,AUTH,draft,url);
            assertFalse(TimekeeperUiPlugin.getDefault().isBackendReady());RemoteTestSupport.closedLocalStorage();
        } finally {RemoteBootstrap.setAccountBEnabled(true);}
        assertEquals(before,api.metadata());assertEquals(task,api.task(task.id()).orElseThrow());
        // Retry the same still-open draft after restoring authorization; it must save once.
        editor.button("OK").click();
        RemoteTestSupport.waitFor(()->!shell.isOpen() && api.task(task.id()).filter(t->t.summary().equals(draft)).isPresent(),"Authorized retry did not save the original editor draft");
        var saved=api.task(task.id()).orElseThrow();assertEquals(task.id(),saved.id());assertEquals(task.version()+1,saved.version());assertEquals(Optional.of(url),saved.url());
        settled(saved);remove(api,saved);
        RemoteBootstrap.receipt().setProperty("phase.task.authentication","passed");RemoteBootstrap.writeProof();
    }
    private static void optimisticConflict(HttpTimekeeperService api,Project project) throws Exception {
        var task=api.createTask(new CreateTask(Optional.of(project.id()),"Concurrent task fixture",Optional.empty()));
        var shell=open(project,task);var editor=shell.bot();
        String draft="Unsaved conflicting task draft",url="https://example.invalid/conflicting-draft";
        editor.textWithLabel("Summary").setText(draft);editor.textWithLabel("URL (optional)").setText(url);
        Task otherEdit;
        try(var other=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::fixtureToken,Duration.ofSeconds(5))) {
            var original=other.task(task.id()).orElseThrow();
            otherEdit=other.updateTask(new UpdateTask(original.id(),original.version(),original.projectId(),original.parentTaskId(),"Other client task edit",Optional.of("https://example.invalid/other-client")));
        }
        editor.button("OK").click();
        rejected(shell,CONFLICT,STALE,draft,url);
        assertEquals(otherEdit,api.task(task.id()).orElseThrow());assertTrue(TimekeeperUiPlugin.getDefault().isBackendReady());RemoteTestSupport.closedLocalStorage();
        // A refreshed cache must not upgrade this still-open editor's captured expected version.
        refresh();
        RemoteTestSupport.waitFor(()->{
            try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().task(task.id()).filter(t->t.version()==otherEdit.version()).isPresent());}
            catch(Exception unavailable) {return false;}
        },"Concurrent task revision did not reach the Eclipse snapshot");
        assertEquals(draft,editor.textWithLabel("Summary").getText());assertEquals(url,editor.textWithLabel("URL (optional)").getText());
        editor.button("OK").click();
        rejected(shell,CONFLICT,STALE,draft,url);
        assertEquals(otherEdit,api.task(task.id()).orElseThrow());editor.button("Cancel").click();
        RemoteTestSupport.waitFor(()->!shell.isOpen(),"Conflicting task editor did not close after cancel");
        var reviewed=open(project,otherEdit);var controls=reviewed.bot();
        assertEquals(otherEdit.summary(),controls.textWithLabel("Summary").getText());assertEquals(otherEdit.url().orElseThrow(),controls.textWithLabel("URL (optional)").getText());
        controls.textWithLabel("Summary").setText("Reviewed after conflict");controls.button("OK").click();
        RemoteTestSupport.waitFor(()->!reviewed.isOpen() && api.task(task.id()).filter(t->t.summary().equals("Reviewed after conflict")).isPresent(),"Reviewed task editor did not save the current revision");
        var saved=api.task(task.id()).orElseThrow();assertEquals(otherEdit.version()+1,saved.version());assertEquals(otherEdit.url(),saved.url());settled(saved);remove(api,saved);
        RemoteBootstrap.receipt().setProperty("phase.task.conflict","passed");RemoteBootstrap.writeProof();
    }
    private static SWTBotShell open(Project project,Task task) throws Exception {
        refresh();var bot=new SWTWorkbenchBot();var tree=bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree");
        RemoteTestSupport.waitFor(()->{
            if(!TimekeeperUiPlugin.getDefault().isBackendReady()) return false;
            try {return tree.getTreeItem(project.name()).expand().getNode(task.summary())!=null;}
            catch(RuntimeException loading) {return false;}
        },"Task fixture did not appear in Workweek");
        tree.getTreeItem(project.name()).getNode(task.summary()).contextMenu("Edit task...").click();return bot.shell("Edit task");
    }
    private static void rejected(SWTBotShell shell,String status,String message,String summary,String url) throws Exception {
        var controls=shell.bot();RemoteTestSupport.waitFor(()->controls.button("OK").isEnabled() && TimekeeperUiPlugin.getDefault().backendStatus().equals(status),"Rejected task save did not release its editor");
        assertTrue(shell.isOpen());assertTrue(controls.textWithLabel("Summary").isEnabled());assertTrue(controls.textWithLabel("URL (optional)").isEnabled());
        assertEquals(summary,controls.textWithLabel("Summary").getText());assertEquals(url,controls.textWithLabel("URL (optional)").getText());
        assertTrue("Task editor did not display the rejection",RemoteTestSupport.onUi(()->visibleMessage(shell.widget,message)));
    }
    private static boolean visibleMessage(Composite parent,String expected) {
        for(var child:parent.getChildren()) {
            if(child instanceof Text text && text.isVisible() && text.getText().trim().equals(expected)) return true;
            if(child instanceof Composite composite && visibleMessage(composite,expected)) return true;
        }
        return false;
    }
    private static void settled(Task task) {
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).filter(t->t.getSummary().equals(task.summary())).isPresent(),"Saved task did not reach its original Mylyn projection");
    }
    private static void remove(HttpTimekeeperService api,Task task) throws Exception {
        api.deleteTask(new DeleteTask(task.id(),task.version()));refresh();
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).isEmpty(),"Removed fixture task retained a Mylyn projection");RemoteTestSupport.readyWithMylyn();
    }
    private static void refresh() throws Exception {RemoteTestSupport.onUi(()->{TimekeeperUiPlugin.getDefault().refreshBackend();return null;});}
}
