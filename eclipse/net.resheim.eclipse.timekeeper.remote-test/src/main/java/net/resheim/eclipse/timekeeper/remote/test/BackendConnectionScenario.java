package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.client.BackendCredentials;

/** Probe unsaved connection settings in a real installed preference page. */
final class BackendConnectionScenario {
    static void run() throws Exception {
        var preferences=InstanceScope.INSTANCE.getNode(TimekeeperPlugin.BUNDLE_ID);
        String mode=preferences.get("backend-mode",""),url=preferences.get("backend-url","");
        String credential=BackendCredentials.read(),identity=TimekeeperUiPlugin.getDefault().backendIdentity();
        try(var api=RemoteTestSupport.client()) {
            var before=api.metadata();
            PlatformUI.getWorkbench().getDisplay().asyncExec(()->PreferencesUtil.createPreferenceDialogOn(
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                "net.resheim.eclipse.timekeeper.ui.preferences.backend",null,null).open());
            var dialog=new SWTWorkbenchBot().shell("Preferences");var controls=dialog.bot();
            controls.radio("Local database").click();assertFalse(controls.button("Test connection").isEnabled());
            controls.radio("Timekeeper server").click();
            controls.textWithId("backend-url").setText("http://remote.invalid/api/v1");
            controls.textWithId("backend-token").setText("unsaved-invalid-token");controls.button("Test connection").click();
            assertTrue(controls.labelWithId("backend-test-status").getText().contains("Use HTTPS"));
            controls.textWithId("backend-url").setText(RemoteBootstrap.serverUrl.toString());
            controls.button("Test connection").click();
            RemoteTestSupport.waitFor(()->controls.labelWithId("backend-test-status").getText().contains("valid token"),"Invalid token was not reported by the connection test");
            assertTrue(controls.labelWithId("backend-test-status").getText().contains(RemoteBootstrap.serverUrl.toString()));
            assertTrue(controls.labelWithId("backend-test-status").getText().contains("expired or been revoked"));
            assertTrue(controls.labelWithId("backend-test-status").getText().contains("HTTP 401"));
            assertTrue(controls.labelWithId("backend-test-status").getText().contains("Server URL matches the browser"));
            assertTrue(controls.button("Test connection").isEnabled());
            controls.textWithId("backend-token").setText("  "+RemoteBootstrap.observerBToken()+"  ");controls.button("Test connection").click();
            RemoteTestSupport.waitFor(()->controls.labelWithId("backend-test-status").getText().startsWith("Connected to remote-owner-b ("),"Unsaved read-only account did not pass the connection test");
            assertEquals(identity,TimekeeperUiPlugin.getDefault().backendIdentity());assertEquals(before,api.metadata());
            assertEquals(mode,preferences.get("backend-mode",""));assertEquals(url,preferences.get("backend-url",""));
            assertEquals(credential,BackendCredentials.read());
            // A late result must not describe edited settings or reopen a closed page.
            controls.button("Test connection").click();controls.textWithId("backend-token").setText("another-unsaved-token");
            assertEquals("",controls.labelWithId("backend-test-status").getText());controls.button("Cancel").click();
            RemoteTestSupport.waitFor(()->!dialog.isOpen(),"Connection test preferences did not cancel");
            assertEquals(credential,BackendCredentials.read());assertEquals(identity,TimekeeperUiPlugin.getDefault().backendIdentity());
            RemoteTestSupport.closedLocalStorage();
            RemoteBootstrap.proof.setProperty("phase.backend.connection-test","passed");RemoteBootstrap.writeProof();
        }
    }
}
