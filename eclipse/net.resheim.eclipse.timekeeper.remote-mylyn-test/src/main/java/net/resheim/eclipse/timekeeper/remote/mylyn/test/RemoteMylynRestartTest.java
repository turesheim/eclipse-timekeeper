package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.util.UUID;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;

public class RemoteMylynRestartTest {
    @Test public void restartedTaskListReusesItsRemoteMappingAndPreservesHistory() throws Exception {
        RemoteTestSupport.readyWithMylyn();assertEquals("passed",RemoteBootstrap.receipt().getProperty("phase.mylyn"));
        try(var api=RemoteTestSupport.client()) {
            var first=api.task(new TaskId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.first")))).orElseThrow();
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault()!=null && MylynIntegration.getDefault().linked(first).isPresent(),"Persisted remote Mylyn projection was not reconnected");
            var projection=MylynIntegration.getDefault().linked(first).orElseThrow();
            assertEquals(RemoteBootstrap.receipt().getProperty("mylyn.marker"),projection.getAttribute("timekeeper.remote.restart-marker"));
            var second=api.task(new TaskId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.second")))).orElseThrow();
            long changedVersion=Long.parseLong(RemoteBootstrap.receipt().getProperty("mylyn.between-launch-version"));
            assertEquals("Server edit between Eclipse launches",second.summary());assertEquals(changedVersion,second.version());
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(second).filter(t->t.getSummary().equals(second.summary())).isPresent(),"Persisted projection overwrote or failed to display a server edit made between launches");
            assertEquals(RemoteBootstrap.receipt().getProperty("mylyn.second.handle"),MylynIntegration.getDefault().linked(second).orElseThrow().getHandleIdentifier());
            assertEquals(second,api.task(second.id()).orElseThrow());
            RemoteBootstrap.receipt().setProperty("phase.mylyn.persisted-baseline","passed");
            var original=api.activity(new ActivityId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.original")))).orElseThrow();
            var successor=api.activity(new ActivityId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.successor")))).orElseThrow();
            assertTrue(original.end().isPresent());assertTrue(successor.end().isPresent());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().activateTask(projection);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(a->a.taskId().equals(first.id())).isPresent(),"Reconnected projection did not start its original remote UUID");
            var current=api.activeActivity(OwnerId.LOCAL).orElseThrow();assertNotEquals(original.id(),current.id());
            RemoteMylynTest.waitUiActive(first.id());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().deactivateTask(projection);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).isEmpty(),"Reconnected projection did not stop remotely");
            assertEquals(original,api.activity(original.id()).orElseThrow());assertEquals(successor,api.activity(successor.id()).orElseThrow());
            String[] pids=RemoteBootstrap.receipt().getProperty("server.pids").split(",");assertEquals(2,pids.length);assertNotEquals(pids[0],pids[1]);
            RemoteBootstrap.receipt().setProperty("phase.mylyn.restart","passed");RemoteBootstrap.writeProof();
        }
        RemoteTestSupport.readyWithMylyn();
        selectSecondAccount();
    }
    private static void selectSecondAccount() throws Exception {
        var ui=net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin.getDefault();
        String runningIdentity=ui.backendIdentity();
        var originalId=new TaskId(UUID.fromString(RemoteBootstrap.receipt().getProperty("mylyn.first")));
        assertTrue(RemoteTestSupport.onUi(()->ui.getTimekeeperService().task(originalId).isPresent()));
        var bot=new org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot();
        org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().asyncExec(()->org.eclipse.ui.dialogs.PreferencesUtil
            .createPreferenceDialogOn(org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                "net.resheim.eclipse.timekeeper.ui.preferences.backend",null,null).open());
        var preferences=bot.shell("Preferences");
        var controls=preferences.bot();
        assertTrue(controls.radio("Timekeeper server").isSelected());
        assertEquals(RemoteBootstrap.endpoint().toString(),controls.textWithId("backend-url").getText());
        controls.textWithId("backend-token").setText(RemoteBootstrap.accountBToken());
        controls.button("Apply and Close").click();
        bot.shell("Restart Eclipse").bot().button("OK").click();
        RemoteTestSupport.waitFor(()->!preferences.isOpen(),"Backend preferences did not close");
        var secure=org.eclipse.equinox.security.storage.SecurePreferencesFactory.getDefault().node("/net.resheim.eclipse.timekeeper/backend");
        // Avoid assertions that could include either credential in a failure report.
        assertTrue("New account token was not saved by the preference page",RemoteBootstrap.accountBToken().equals(secure.get("token","")));
        assertTrue(secure.isEncrypted("token"));
        assertEquals("Applying a token must wait for restart before switching runtime identity",runningIdentity,ui.backendIdentity());
        assertTrue(RemoteTestSupport.onUi(()->ui.getTimekeeperService().task(originalId).isPresent()));
        try(var original=RemoteTestSupport.client()) {
            assertEquals("remote-owner-a",original.profile().owner().value());
            RemoteBootstrap.receipt().setProperty("mylyn.account-a.revision",String.valueOf(original.metadata().revision()));
        }
        RemoteBootstrap.receipt().setProperty("phase.mylyn.account-selected","passed");RemoteBootstrap.writeProof();
    }
}
