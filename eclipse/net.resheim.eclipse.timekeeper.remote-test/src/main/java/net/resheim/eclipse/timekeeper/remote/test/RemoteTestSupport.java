package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

public final class RemoteTestSupport {
    public static void ready() throws Exception {ready(false);}
    public static void readyWithMylyn() throws Exception {ready(true);}
    private static void ready(boolean mylyn) throws Exception {
        waitFor(()->TimekeeperUiPlugin.getDefault()!=null && TimekeeperUiPlugin.getDefault().isBackendReady(),"Remote backend did not become ready");
        closedLocalStorage();
        if(mylyn) assertNotNull(Platform.getBundle("org.eclipse.mylyn.tasks.ui"));
        else assertNull(Platform.getBundle("org.eclipse.mylyn.tasks.ui"));

        assertTrue(Files.exists(RemoteBootstrap.folder.resolve("secure.storage")));
        assertTrue(org.eclipse.equinox.security.storage.SecurePreferencesFactory.getDefault()
                .node("/net.resheim.eclipse.timekeeper/backend").isEncrypted("token"));
        assertFalse(Files.readString(RemoteBootstrap.folder.resolve("secure.storage")).contains(RemoteBootstrap.token));
        assertFalse(org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(TimekeeperPlugin.BUNDLE_ID).get("backend-url","").contains(RemoteBootstrap.token));
    }
    public static void closedLocalStorage() throws Exception {
        assertTrue(TimekeeperUiPlugin.getDefault().isRemote());
        assertNull(TimekeeperPlugin.getDefault().getEntityManager());
        assertFalse(TimekeeperPlugin.getDefault().isReady());
        assertEquals("Remote backend selected; embedded storage is closed.",TimekeeperPlugin.getDefault().getDatabaseStatus().getMessage());
        assertEquals(RemoteBootstrap.proof.getProperty("native.sha256"),RemoteBootstrap.hash(RemoteBootstrap.folder.resolve("native-local.mv.db")));
    }
    public static Object applicationConnection() {return TimekeeperPlugin.getDefault().getEntityManager();}
    public static void embeddedStorage() {
        assertFalse(TimekeeperUiPlugin.getDefault().isRemote());
        assertNotNull(TimekeeperPlugin.getDefault().getEntityManager());
        assertTrue(TimekeeperPlugin.getDefault().isReady());
    }
    public static org.eclipse.core.runtime.preferences.IEclipsePreferences backendPreferences() {
        return org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(TimekeeperPlugin.BUNDLE_ID);
    }
    public static HttpTimekeeperService client() {return new HttpTimekeeperService(RemoteBootstrap.serverUrl,()->RemoteBootstrap.token,Duration.ofSeconds(5));}
    public static void showWorkweek() throws Exception {
        onUi(()->{
            var window=PlatformUI.getWorkbench().getActiveWorkbenchWindow();window.getShell().setSize(1100,750);window.getShell().forceActive();
            window.getActivePage().showView(WorkWeekView.VIEW_ID);return null;
        });
    }
    public static void confirmTransferResult(String success,String failure) throws Exception {
        waitFor(()->{
            try {return onUi(()->java.util.Arrays.stream(PlatformUI.getWorkbench().getDisplay().getShells())
                .anyMatch(shell->shell.isVisible() && (shell.getText().equals(success) || shell.getText().equals(failure))));}
            catch(Exception pending) {return false;}
        },"Native transfer did not report its result");
        var error=onUi(()->java.util.Arrays.stream(PlatformUI.getWorkbench().getDisplay().getShells())
            .filter(shell->shell.isVisible() && shell.getText().equals(failure)).map(RemoteTestSupport::dialogText).findFirst());
        assertFalse("Native transfer failed: "+error.orElse(""),error.isPresent());
        new SWTWorkbenchBot().shell(success).bot().button("OK").click();
    }
    private static String dialogText(org.eclipse.swt.widgets.Composite parent) {
        var text=new StringBuilder();
        for(var child:parent.getChildren()) {
            if(child instanceof org.eclipse.swt.widgets.Label label) text.append(label.getText()).append(' ');
            else if(child instanceof org.eclipse.swt.widgets.Text field && (field.getStyle() & org.eclipse.swt.SWT.PASSWORD)==0) text.append(field.getText()).append(' ');
            else if(child instanceof org.eclipse.swt.widgets.Composite nested) text.append(dialogText(nested));
        }
        return text.toString();
    }
    public static void waitFor(BooleanSupplier condition,String message) {
        new SWTWorkbenchBot().waitUntil(new DefaultCondition() {
            public boolean test() {try {return condition.getAsBoolean();}catch(RuntimeException transientState) {return false;}}
            public String getFailureMessage() {return message;}
        },30000);
    }
    public static <T> T onUi(Callable<T> action) throws Exception {
        var task=new FutureTask<>(action);PlatformUI.getWorkbench().getDisplay().asyncExec(task);return task.get(30,TimeUnit.SECONDS);
    }
}
