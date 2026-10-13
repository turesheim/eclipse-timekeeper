package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.time.Duration;
import java.util.*;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Real preference drafts must reject a restored label with reused numeric version. */
final class RemoteLabelRestoreScenario {
    private static final String STALE="This record has changed. Close and reopen the editor before saving.";
    static void run(HttpTimekeeperService api) throws Exception {
        String prefix="Label restore "+UUID.randomUUID().toString().substring(0,8);
        // Keep the preceding task/browser cold-restore receipt distinct.
        for(String suffix:List.of("backup","restored"))
            RemoteBootstrap.receipt().setProperty("task.restore."+suffix+".sha256",RemoteBootstrap.receipt().getProperty("server."+suffix+".sha256"));
        try(var peer=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::observerBToken,Duration.ofSeconds(5))) {
            var baseline=peer.createLabel(new CreateLabel(prefix+" baseline",Optional.of("0,0,0")));
            await(baseline);RemoteBootstrap.backupFixtureServer();
            RemoteBootstrap.receipt().setProperty("label.restore.backup.sha256",RemoteBootstrap.receipt().getProperty("server.backup.sha256"));
            var later=peer.updateLabel(new UpdateLabel(baseline.id(),baseline.version(),prefix+" before restore",Optional.of("1,2,3")));
            var history=new ArrayList<net.resheim.eclipse.timekeeper.domain.Label>();
            for(int index=0;index<10;index++) history.add(peer.createLabel(new CreateLabel(prefix+" history "+index,Optional.empty())));
            await(later);long oldHead=peer.metadata().revision();
            var prefs=open();prefs.bot().table().select(later.name());String draft=prefix+" retained draft";
            prefs.bot().textWithLabel("Label:").setText(draft);
            try {
                RemoteBootstrap.stopFixtureServer();
                RemoteTestSupport.waitFor(()->!TimekeeperUiPlugin.getDefault().isBackendReady(),"Label restore shutdown did not reach the installed client");
                assertEquals(draft,prefs.bot().textWithLabel("Label:").getText());RemoteTestSupport.closedLocalStorage();
                RemoteBootstrap.restoreStoppedFixtureServer();peer.invalidateVersions();
                assertEquals(baseline,peer.label(baseline.id()).orElseThrow());
                var current=peer.updateLabel(new UpdateLabel(baseline.id(),baseline.version(),prefix+" after restore",Optional.of("4,5,6")));
                assertEquals(later.version(),current.version());assertNotEquals(later,current);assertTrue(peer.metadata().revision()<oldHead);
                await(current);RemoteTestSupport.readyWithMylyn();
                for(var item:history) assertTrue(peer.label(item.id()).isEmpty());
                assertEquals(draft,prefs.bot().textWithLabel("Label:").getText());
                prefs.bot().button("Apply").click();
                RemoteTestSupport.waitFor(()->{
                    try {return !peer.label(current.id()).orElseThrow().equals(current)
                        || RemoteTestSupport.onUi(()->message(prefs.widget,STALE));}
                    catch(Exception loading) {return false;}
                },"Label preference outcome did not finish");
                assertEquals("Restored same-version label was overwritten without review",current,peer.label(current.id()).orElseThrow());
                assertTrue(RemoteTestSupport.onUi(()->message(prefs.widget,STALE)));
                assertEquals(draft,prefs.bot().textWithLabel("Label:").getText());assertTrue(prefs.bot().textWithLabel("Label:").isEnabled());
                // A retained deletion intent must compare the same captured DTO too.
                prefs.bot().button("Remove").click();prefs.bot().button("Apply").click();
                var deletionFinished=new java.util.concurrent.atomic.AtomicBoolean();
                RemoteTestSupport.onUi(()->{
                    TimekeeperUiPlugin.getDefault().runRead("Observe label deletion outcome",()->TimekeeperUiPlugin.getDefault().getTimekeeperService().labels(),value->deletionFinished.set(true),failure->{throw new AssertionError(failure);});return null;
                });
                RemoteTestSupport.waitFor(deletionFinished::get,"Label deletion worker barrier did not finish");
                RemoteTestSupport.waitFor(()->{
                    try {return prefs.bot().button("Apply").isEnabled() && (peer.label(current.id()).isEmpty()
                        || RemoteTestSupport.onUi(()->message(prefs.widget,STALE)));}
                    catch(Exception loading) {return false;}
                },"Stale label deletion did not finish");
                assertEquals("Restored same-version label was deleted without review",current,peer.label(current.id()).orElseThrow());
                assertTrue(RemoteTestSupport.onUi(()->message(prefs.widget,STALE)));
                prefs.bot().button("Cancel").click();RemoteTestSupport.waitFor(()->!prefs.isOpen(),"Stale label preferences did not cancel");
                var reviewed=open();reviewed.bot().table().select(current.name());assertEquals(current.name(),reviewed.bot().textWithLabel("Label:").getText());
                reviewed.bot().textWithLabel("Label:").setText(prefix+" reviewed");reviewed.bot().button("Apply and Close").click();
                RemoteTestSupport.waitFor(()->!reviewed.isOpen(),"Reviewed restored label did not save");
                var accepted=peer.label(current.id()).orElseThrow();assertEquals(current.version()+1,accepted.version());assertEquals(prefix+" reviewed",accepted.name());
                await(accepted);var remove=open();remove.bot().table().select(accepted.name());remove.bot().button("Remove").click();remove.bot().button("Apply and Close").click();
                RemoteTestSupport.waitFor(()->!remove.isOpen() && peer.label(accepted.id()).isEmpty(),"Reviewed restored label did not delete once");
                RemoteBootstrap.receipt().setProperty("label.restore.restored.sha256",RemoteBootstrap.receipt().getProperty("server.restored.sha256"));
                assertEquals(RemoteBootstrap.receipt().getProperty("label.restore.backup.sha256"),RemoteBootstrap.receipt().getProperty("label.restore.restored.sha256"));
                for(String part:List.of("cold-file","update-draft","delete-draft","reviewed-update","reviewed-delete"))
                    RemoteBootstrap.receipt().setProperty("phase.label.restore."+part,"passed");
                RemoteTestSupport.closedLocalStorage();RemoteBootstrap.writeProof();
            } finally {
                if(prefs.isOpen()) prefs.bot().button("Cancel").click();
            }
        }
    }
    private static void await(net.resheim.eclipse.timekeeper.domain.Label label) {
        RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().label(label.id()).filter(label::equals).isPresent());}catch(Exception loading) {return false;}},"Restored label did not reach the canonical installed snapshot");
    }
    private static SWTBotShell open() {
        PlatformUI.getWorkbench().getDisplay().asyncExec(()->PreferencesUtil.createPreferenceDialogOn(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),"net.resheim.eclipse.timekeeper.ui.preferences.LabelPreferences",null,null).open());
        var bot=new SWTWorkbenchBot();RemoteTestSupport.waitFor(()->Arrays.stream(bot.shells()).anyMatch(shell->shell.getText().equals("Preferences")),"Label preferences did not open");return bot.shell("Preferences");
    }
    private static boolean message(Composite parent,String expected) {
        for(var child:parent.getChildren()) {
            if(child instanceof Label label && label.isVisible() && label.getText().contains(expected)) return true;
            if(child instanceof Text text && text.isVisible() && text.getText().contains(expected)) return true;
            if(child instanceof Composite composite && message(composite,expected)) return true;
        }
        return false;
    }
}
