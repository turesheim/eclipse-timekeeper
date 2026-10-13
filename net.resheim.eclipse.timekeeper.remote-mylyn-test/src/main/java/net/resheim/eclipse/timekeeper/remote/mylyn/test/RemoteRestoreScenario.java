package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.time.Duration;
import java.util.*;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Restores the real server file while both real clients hold the later history. */
final class RemoteRestoreScenario {
    static void run(HttpTimekeeperService api,Project project) throws Exception {
        String prefix="Restore "+UUID.randomUUID().toString().substring(0,8);
        try(var peer=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::observerBToken,Duration.ofSeconds(5))) {
            var baseline=peer.createTask(new CreateTask(Optional.of(project.id()),prefix+" baseline",Optional.empty()));
            awaitTask(baseline);
            RemoteBootstrap.backupFixtureServer();
            assertEquals(baseline,peer.task(baseline.id()).orElseThrow());
            var later=peer.updateTask(new UpdateTask(baseline.id(),baseline.version(),baseline.projectId(),baseline.parentTaskId(),prefix+" before restore",Optional.of("https://example.invalid/before-restore")));
            // Ensure both cursors are ahead of the restored change history.
            var temporaryLabels=new ArrayList<net.resheim.eclipse.timekeeper.domain.Label>();
            for(int index=0;index<10;index++) temporaryLabels.add(peer.createLabel(new CreateLabel(prefix+" history "+index,Optional.of("0,0,0"))));
            awaitTask(later);
            var bot=new SWTWorkbenchBot();var tree=bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree");
            RemoteTestSupport.waitFor(()->{try {return tree.getTreeItem(project.name()).expand().getNode(later.summary())!=null;}catch(RuntimeException pending) {return false;}},"Pre-restore record did not appear in Workweek");
            tree.getTreeItem(project.name()).getNode(later.summary()).contextMenu("Edit task...").click();
            var nativeEditor=bot.shell("Edit task");String nativeDraft=prefix+" native draft",nativeUrl="https://example.invalid/native-restore-draft";
            nativeEditor.bot().textWithLabel("Summary").setText(nativeDraft);nativeEditor.bot().textWithLabel("URL (optional)").setText(nativeUrl);
            try(var browser=new RemoteWebScenario.BrowserPeer()) {
                browser.command(Map.of("action","attach","project",project.name(),"summary",later.summary()));
                String webDraft=prefix+" browser draft",webUrl="https://example.invalid/browser-restore-draft";
                browser.command(Map.of("action","draft","summary",webDraft,"url",webUrl));
                long oldHead=peer.metadata().revision();
                RemoteBootstrap.stopFixtureServer();
                RemoteTestSupport.waitFor(()->!TimekeeperUiPlugin.getDefault().isBackendReady(),"Actual server shutdown was not visible to installed client");
                browser.command(Map.of("action","offline-draft","draft",webDraft,"url",webUrl));
                assertEquals(nativeDraft,nativeEditor.bot().textWithLabel("Summary").getText());assertEquals(nativeUrl,nativeEditor.bot().textWithLabel("URL (optional)").getText());RemoteTestSupport.closedLocalStorage();
                RemoteBootstrap.restoreStoppedFixtureServer();
                assertEquals(RemoteBootstrap.receipt().getProperty("server.backup.sha256"),RemoteBootstrap.receipt().getProperty("server.restored.sha256"));
                var restored=peer.task(baseline.id()).orElseThrow();assertEquals(baseline,restored);
                var current=peer.updateTask(new UpdateTask(restored.id(),restored.version(),restored.projectId(),restored.parentTaskId(),prefix+" after restore",Optional.of("https://example.invalid/after-restore")));
                assertEquals(later.version(),current.version());assertNotEquals(later,current);assertTrue(peer.metadata().revision()<oldHead);
                awaitTask(current);RemoteTestSupport.readyWithMylyn();
                for(var label:temporaryLabels) assertTrue(peer.label(label.id()).isEmpty());
                assertEquals(nativeDraft,nativeEditor.bot().textWithLabel("Summary").getText());assertEquals(nativeUrl,nativeEditor.bot().textWithLabel("URL (optional)").getText());
                nativeEditor.bot().button("OK").click();
                RemoteTestSupport.waitFor(()->{try {return nativeEditor.bot().button("OK").isEnabled() && RemoteTestSupport.onUi(()->message(nativeEditor.widget,"This record has changed. Close and reopen the editor before saving."));}catch(Exception pending) {return false;}},"Restored same-version history silently upgraded the native draft");
                assertEquals(nativeDraft,nativeEditor.bot().textWithLabel("Summary").getText());assertEquals(nativeUrl,nativeEditor.bot().textWithLabel("URL (optional)").getText());assertEquals(current,peer.task(current.id()).orElseThrow());
                browser.command(Map.of("action","conflict","summary",current.summary(),"draft",webDraft,"draftUrl",webUrl));assertEquals(current,peer.task(current.id()).orElseThrow());
                nativeEditor.bot().button("Cancel").click();RemoteTestSupport.waitFor(()->!nativeEditor.isOpen(),"Restored-history draft did not cancel");
                RemoteTestSupport.waitFor(()->{try {return tree.getTreeItem(project.name()).getNode(current.summary())!=null;}catch(RuntimeException pending) {return false;}},"Restored record did not reach native tree");
                tree.getTreeItem(project.name()).getNode(current.summary()).contextMenu("Edit task...").click();var reviewed=bot.shell("Edit task");
                assertEquals(current.summary(),reviewed.bot().textWithLabel("Summary").getText());reviewed.bot().textWithLabel("Summary").setText(prefix+" reviewed");reviewed.bot().button("OK").click();
                RemoteTestSupport.waitFor(()->peer.task(current.id()).filter(t->t.summary().equals(prefix+" reviewed") && t.version()==current.version()+1).isPresent(),"Explicitly reviewed restored record was not saved once");RemoteTestSupport.waitFor(()->!reviewed.isOpen(),"Reviewed restore editor did not close");
                var accepted=peer.task(current.id()).orElseThrow();peer.deleteTask(new DeleteTask(accepted.id(),accepted.version()));
                RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().task(accepted.id()).isEmpty());}catch(Exception pending) {return false;}},"Restore test task did not clear from installed cache");
                for(String part:List.of("cold-file","history-reset","native-draft","web-draft","reviewed-save")) RemoteBootstrap.receipt().setProperty("phase.restore."+part,"passed");RemoteBootstrap.writeProof();
            }
        }
    }
    private static void awaitTask(Task expected) {RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().task(expected.id()).filter(expected::equals).isPresent());}catch(Exception loading) {return false;}},"Actual restored history did not automatically reach installed snapshot");}
    private static boolean message(Composite parent,String expected) {for(var child:parent.getChildren()) {if(child instanceof Text text && text.isVisible() && text.getText().trim().equals(expected)) return true;if(child instanceof Composite composite && message(composite,expected)) return true;}return false;}
}
