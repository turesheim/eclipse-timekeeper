package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.swt.SWT;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.ui.PlatformUI;

import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Retained workspace: server A -> server B -> embedded -> server A -> embedded. */
@SuppressWarnings("restriction")
final class BackendSwitchScenario {
    private static final String MARKER="timekeeper.backend-switch-marker";
    private static final String PROJECT="Installed Mylyn remote project",TASK="Remote Mylyn first";

    static void prepareEndpointSwitch() throws Exception {
        assertEquals("passed",proof("phase.mylyn.account"));
        try(var primary=primaryB();var original=primaryA();var secondary=secondary()) {
            var task=primary.task(taskId("mylyn.account-b.task")).orElseThrow();
            mark("primary",task);
            put("switch.primary.interval",proof("mylyn.account-b.interval"));
            remember("switch.primary.interval-state",primary.activity(intervalId("switch.primary.interval")).orElseThrow());
            remember("switch.primary-a",original.metadata());remember("switch.primary-b",primary.metadata());
            assertTrue(secondary.metadata().tasks().isEmpty());remember("switch.secondary",secondary.metadata());
        }
        saveTaskList();select(true,RemoteBootstrap.alternateEndpoint());
        passed("phase.backend.server-selected");
    }

    static void server() throws Exception {
        remoteReady(RemoteBootstrap.alternateEndpoint());assertEquals("passed",proof("phase.backend.server-selected"));
        assertServersUnchanged();
        try(var api=selected();var primary=primaryB()) {
            var initial=api.metadata();assertTrue(initial.projects().isEmpty());assertTrue(initial.tasks().isEmpty());
            assertTrue(initial.labels().isEmpty());assertTrue(initial.active().isEmpty());
            assertForeign("primary",primary.task(taskId("switch.primary.task")).orElseThrow());
            assertEquals(initial,api.metadata());
            var task=createThroughWorkweek();assertNotEquals(taskId("switch.primary.task"),task.id());
            var interval=track(task);assertEquals("remote-owner-b",interval.ownerId().value());
            mark("secondary",task);put("switch.secondary.interval",interval.id().value().toString());remember("switch.secondary.interval-state",interval);
            assertNotEquals(proof("switch.primary.identity"),ui().backendIdentity());
            remember("switch.secondary",api.metadata());
        }
        saveTaskList();assertServersUnchanged();select(false,RemoteBootstrap.alternateEndpoint());passed("phase.backend.server");
    }

    static void local() throws Exception {
        localReady();assertEquals("passed",proof("phase.backend.server"));assertServersUnchanged();
        assertTrue(RemoteTestSupport.onUi(()->ui().getTimekeeperService().tasks().isEmpty()));
        assertEquals(1L,(long)RemoteTestSupport.onUi(()->ui().getTimekeeperService().projects().stream().filter(project->project.name().equals("Preserve this local project")).count()));
        try(var primary=primaryB();var secondary=secondary()) {
            assertForeign("primary",primary.task(taskId("switch.primary.task")).orElseThrow());
            assertForeign("secondary",secondary.task(taskId("switch.secondary.task")).orElseThrow());
        }
        var task=createThroughWorkweek();var interval=track(task);assertEquals(OwnerId.LOCAL,interval.ownerId());
        assertNotEquals(taskId("switch.primary.task"),task.id());assertNotEquals(taskId("switch.secondary.task"),task.id());
        mark("local",task);put("switch.local.interval",interval.id().value().toString());remember("switch.local.interval-state",interval);
        // Recording time updates the JPA task's version through its activity relation.
        var recorded=RemoteTestSupport.onUi(()->ui().getTimekeeperService().task(task.id()).orElseThrow());
        assertEquals(task.summary(),recorded.summary());assertEquals(task.url(),recorded.url());assertEquals(task.projectId(),recorded.projectId());
        remember("switch.local.task-state",recorded);saveTaskList();assertServersUnchanged();
        select(true,RemoteBootstrap.endpoint());
        assertNotNull("Applying remote settings must keep the running local connection",RemoteTestSupport.applicationConnection());
        passed("phase.backend.local");
    }

    static void returned() throws Exception {
        remoteReady(RemoteBootstrap.endpoint());assertEquals("passed",proof("phase.backend.local"));assertServersUnchanged();
        assertNotEquals(proof("native.initial.sha256"),proof("native.sha256"));
        assertEquals(proof("native.after-embedded.sha256"),proof("native.sha256"));
        try(var api=selected();var secondary=secondary()) {
            var task=api.task(taskId("switch.primary.task")).orElseThrow();assertOriginalProjection("primary",task);
            assertEquals(proof("switch.primary.identity"),ui().backendIdentity());
            verify("switch.primary.interval-state",api.activity(intervalId("switch.primary.interval")).orElseThrow());
            assertForeign("secondary",secondary.task(taskId("switch.secondary.task")).orElseThrow());
            var localProjection=projection("local");assertThrows(IllegalStateException.class,()->MylynIntegration.getDefault().resolve(localProjection));
            assertTrue(api.task(taskId("switch.local.task")).isEmpty());
            assertTrue(RemoteTestSupport.onUi(()->ui().getTimekeeperService().task(taskId("switch.local.task")).isEmpty()));
        }
        assertServersUnchanged();saveTaskList();select(false,RemoteBootstrap.endpoint());passed("phase.backend.return");
    }

    static void localRestart() throws Exception {
        localReady();assertEquals("passed",proof("phase.backend.return"));assertServersUnchanged();
        var task=RemoteTestSupport.onUi(()->ui().getTimekeeperService().task(taskId("switch.local.task")).orElseThrow());
        verify("switch.local.task-state",task);assertOriginalProjection("local",task);
        assertEquals(proof("switch.local.identity"),ui().backendIdentity());
        verify("switch.local.interval-state",RemoteTestSupport.onUi(()->ui().getTimekeeperService().activity(intervalId("switch.local.interval")).orElseThrow()));
        assertTrue(RemoteTestSupport.onUi(()->ui().getTimekeeperService().task(taskId("switch.primary.task")).isEmpty()));
        assertTrue(RemoteTestSupport.onUi(()->ui().getTimekeeperService().task(taskId("switch.secondary.task")).isEmpty()));
        try(var primary=primaryB();var secondary=secondary()) {
            assertForeign("primary",primary.task(taskId("switch.primary.task")).orElseThrow());
            assertForeign("secondary",secondary.task(taskId("switch.secondary.task")).orElseThrow());
        }
        assertServersUnchanged();assertEquals(7,proof("server.pids").split(",").length);assertEquals(7,proof("server.alternate-pids").split(",").length);
        passed("phase.backend.local-restart");
    }

    private static void remoteReady(URI endpoint) throws Exception {
        RemoteTestSupport.readyWithMylyn();RemoteTestSupport.showWorkweek();assertEquals(endpoint,RemoteBootstrap.selectedEndpoint());
        assertTrue(ui().isRemote());
    }
    private static void localReady() throws Exception {
        RemoteTestSupport.waitFor(()->ui()!=null && ui().isBackendReady() && MylynIntegration.getDefault()!=null,"Embedded backend and Mylyn did not become ready");
        RemoteTestSupport.embeddedStorage();
        RemoteTestSupport.showWorkweek();
    }
    private static Task createThroughWorkweek() throws Exception {
        var bot=new SWTWorkbenchBot();var view=bot.viewByTitle("Workweek");
        view.toolbarButton("Create a Timekeeper project").click();
        bot.shell("New project").bot().textWithLabel("Name").setText(PROJECT);bot.shell("New project").bot().button("OK").click();
        RemoteTestSupport.waitFor(()->{
            try {return view.bot().treeWithId("workweek-editor-tree").getTreeItem(PROJECT)!=null;}catch(RuntimeException loading) {return false;}
        },"New backend project did not render");
        view.bot().treeWithId("workweek-editor-tree").getTreeItem(PROJECT).select();view.toolbarButton("Create a Timekeeper task").click();
        bot.shell("New task").bot().textWithLabel("Summary").setText(TASK);bot.shell("New task").bot().button("OK").click();
        RemoteTestSupport.waitFor(()->{
            try {return RemoteTestSupport.onUi(()->ui().getTimekeeperService().tasks().stream().anyMatch(task->task.summary().equals(TASK)));}
            catch(Exception loading) {return false;}
        },"New backend task was not saved");
        return RemoteTestSupport.onUi(()->ui().getTimekeeperService().tasks().stream().filter(task->task.summary().equals(TASK)).findFirst().orElseThrow());
    }
    private static Activity track(Task task) throws Exception {
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).isPresent(),"Selected backend task did not project into Mylyn");
        var projection=MylynIntegration.getDefault().linked(task).orElseThrow();
        RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().activateTask(projection);return null;});
        RemoteTestSupport.waitFor(()->{
            try {return RemoteTestSupport.onUi(()->ui().getTimekeeperService().activeActivity(OwnerId.LOCAL).filter(interval->interval.taskId().equals(task.id())).isPresent());}
            catch(Exception loading) {return false;}
        },"Selected backend did not start its own activity");
        var started=RemoteTestSupport.onUi(()->ui().getTimekeeperService().activeActivity(OwnerId.LOCAL).orElseThrow());
        RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().deactivateTask(projection);return null;});
        RemoteTestSupport.waitFor(()->{
            try {return RemoteTestSupport.onUi(()->ui().getTimekeeperService().activeActivity(OwnerId.LOCAL).isEmpty());}
            catch(Exception loading) {return false;}
        },"Selected backend did not stop its own activity");
        Activity stopped;
        if(ui().isRemote()) try(var api=selected()) {stopped=api.activity(started.id()).orElseThrow();}
        else stopped=RemoteTestSupport.onUi(()->ui().getTimekeeperService().activity(started.id()).orElseThrow());
        assertEquals(task.id(),stopped.taskId());assertTrue(stopped.end().isPresent());return stopped;
    }
    private static void mark(String prefix,Task task) throws Exception {
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).isPresent(),"Projection missing before persisting backend marker");
        var projected=MylynIntegration.getDefault().linked(task).orElseThrow();String marker=UUID.randomUUID().toString();
        RemoteTestSupport.onUi(()->{projected.setAttribute(MARKER,marker);return null;});
        put("switch."+prefix+".task",task.id().value().toString());put("switch."+prefix+".handle",projected.getHandleIdentifier());
        put("switch."+prefix+".marker",marker);put("switch."+prefix+".identity",ui().backendIdentity());
    }
    private static ITask projection(String prefix) {
        return org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getTaskList().getAllTasks().stream()
                .filter(task->proof("switch."+prefix+".marker").equals(task.getAttribute(MARKER))).findFirst().orElseThrow();
    }
    private static void assertForeign(String prefix,Task task) {
        var foreign=projection(prefix);assertTrue(MylynIntegration.getDefault().linked(task).isEmpty());
        assertThrows(IllegalStateException.class,()->MylynIntegration.getDefault().resolve(foreign));
    }
    private static void assertOriginalProjection(String prefix,Task task) {
        RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(task).isPresent(),"Returning backend mapping did not reconnect");
        var projected=MylynIntegration.getDefault().linked(task).orElseThrow();
        assertEquals(proof("switch."+prefix+".handle"),projected.getHandleIdentifier());
        assertEquals(proof("switch."+prefix+".marker"),projected.getAttribute(MARKER));
    }
    private static void select(boolean remote,URI endpoint) throws Exception {
        String identity=ui().backendIdentity();boolean runningRemote=ui().isRemote();var manager=RemoteTestSupport.applicationConnection();
        var tasks=RemoteTestSupport.onUi(()->ui().getTimekeeperService().tasks());
        PlatformUI.getWorkbench().getDisplay().asyncExec(()->org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),"net.resheim.eclipse.timekeeper.ui.preferences.backend",null,null).open());
        var bot=new SWTWorkbenchBot();var dialog=bot.shell("Preferences");var controls=dialog.bot();
        controls.radio(remote ? "Timekeeper server" : "Local database").click();
        assertEquals(remote,controls.textWithId("backend-url").isEnabled());assertEquals(remote,controls.textWithId("backend-token").isEnabled());
        assertTrue(RemoteTestSupport.onUi(()->(controls.textWithId("backend-token").widget.getStyle() & SWT.PASSWORD)!=0));
        if(remote) controls.textWithId("backend-url").setText(endpoint.toString());
        controls.button("Apply and Close").click();bot.shell("Restart Eclipse").bot().button("OK").click();
        RemoteTestSupport.waitFor(()->!dialog.isOpen(),"Backend settings did not close");
        assertEquals(identity,ui().backendIdentity());assertEquals(runningRemote,ui().isRemote());assertSame(manager,RemoteTestSupport.applicationConnection());
        assertEquals(tasks,RemoteTestSupport.onUi(()->ui().getTimekeeperService().tasks()));
        var secure=org.eclipse.equinox.security.storage.SecurePreferencesFactory.getDefault().node("/net.resheim.eclipse.timekeeper/backend");
        assertTrue("Backend token must remain encrypted",secure.isEncrypted("token"));
        assertTrue("Mode/endpoint switching must retain the selected account token",RemoteBootstrap.accountBToken().equals(secure.get("token","")));
        var preferences=RemoteTestSupport.backendPreferences();
        assertEquals(remote ? "remote" : "embedded",preferences.get("backend-mode",""));
        if(remote) assertEquals(endpoint.toString(),preferences.get("backend-url",""));
        preferences.flush();
    }
    private static void saveTaskList() throws Exception {
        var plugin=org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin.getDefault();
        assertTrue(java.nio.file.Path.of(plugin.getDataDirectory()).toAbsolutePath().startsWith(RemoteBootstrap.fixtureFolder().getParent()));
        plugin.getExternalizationManager().saveNow();
        if(ui().getPreferenceStore() instanceof org.eclipse.jface.preference.IPersistentPreferenceStore persistent) persistent.save();
    }
    private static void assertServersUnchanged() throws Exception {
        try(var a=primaryA();var b=primaryB();var secondary=secondary()) {
            verify("switch.primary-a",a.metadata());verify("switch.primary-b",b.metadata());verify("switch.secondary",secondary.metadata());
            verify("switch.primary.interval-state",b.activity(intervalId("switch.primary.interval")).orElseThrow());
            if(RemoteBootstrap.receipt().containsKey("switch.secondary.interval-state")) verify("switch.secondary.interval-state",secondary.activity(intervalId("switch.secondary.interval")).orElseThrow());
        }
    }
    private static HttpTimekeeperService primaryA() {return new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::accountAToken,Duration.ofSeconds(5));}
    private static HttpTimekeeperService primaryB() {return new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::accountBToken,Duration.ofSeconds(5));}
    private static HttpTimekeeperService secondary() {return new HttpTimekeeperService(RemoteBootstrap.alternateEndpoint(),RemoteBootstrap::accountBToken,Duration.ofSeconds(5));}
    private static HttpTimekeeperService selected() {return new HttpTimekeeperService(RemoteBootstrap.selectedEndpoint(),RemoteBootstrap::fixtureToken,Duration.ofSeconds(5));}
    private static TimekeeperUiPlugin ui() {return TimekeeperUiPlugin.getDefault();}
    private static TaskId taskId(String key) {return new TaskId(UUID.fromString(proof(key)));}
    private static ActivityId intervalId(String key) {return new ActivityId(UUID.fromString(proof(key)));}
    private static String proof(String key) {return RemoteBootstrap.receipt().getProperty(key);}
    private static void put(String key,String value) {RemoteBootstrap.receipt().setProperty(key,value);}
    private static String digest(Object value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
    private static void remember(String key,Object value) throws Exception {put(key,digest(value));}
    private static void verify(String key,Object value) throws Exception {assertEquals(key,proof(key),digest(value));}
    private static void passed(String phase) throws Exception {put(phase,"passed");RemoteBootstrap.writeProof();}
}
