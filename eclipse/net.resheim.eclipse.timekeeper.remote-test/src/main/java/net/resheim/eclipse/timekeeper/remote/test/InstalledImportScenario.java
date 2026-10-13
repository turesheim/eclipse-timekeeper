package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** A real preference import with a lost reply must reuse its receipt after installed restart. */
final class InstalledImportScenario {
    private static final String EXPORT="Please choose a folder to store the exported files",IMPORT="Please choose a folder to import from";
    private static final String UNKNOWN="The server request timed out. Refresh before repeating a change.";
    static void prepare(HttpTimekeeperService api) throws Exception {
        if(!enabled()) {mark("phase.import.native","not-run-on-this-platform");return;}
        assertTrue("Previous successful imports retained an unresolved key",keys().isEmpty());
        var before=api.metadata();var originals=api.activities(query(api)).activities();
        var prefs=open();
        Path parent=Files.createDirectories(RemoteBootstrap.fixtureFolder().resolve("lost-import-export"));
        NativeGtkDialog.choose(EXPORT,parent,()->prefs.bot().button("Export...").click());
        shell("Data exported").bot().button("OK").click();
        Path exported;try(var files=Files.list(parent)) {exported=files.filter(Files::isDirectory).findFirst().orElseThrow();}
        assertTrue(Files.isRegularFile(exported.resolve("timekeeper.csv.json")));assertEquals(before,api.metadata());
        put("import.csv.folder",exported.toString());put("import.csv.sha256",csvHash(exported));
        RemoteBootstrap.holdNextImportReply();
        try {
            selectImport(prefs,exported);RemoteBootstrap.awaitImportReplyCommitted();
            var committed=api.metadata();assertEquals(before.projects().size()*2,committed.projects().size());
            assertEquals(before.tasks().size()*2,committed.tasks().size());assertEquals(before.labels().size()*2,committed.labels().size());
            assertEquals(originals.size()*2,api.activities(query(api)).activities().size());
            for(var value:before.projects()) assertEquals(value,api.project(value.id()).orElseThrow());
            for(var value:before.tasks()) assertEquals(value,api.task(value.id()).orElseThrow());
            for(var value:before.labels()) assertEquals(value,api.label(value.id()).orElseThrow());
            for(var value:originals) assertEquals(value,api.activity(value.id()).orElseThrow());
            Path snapshot=RemoteBootstrap.fixtureFolder().resolve("lost-import-committed");
            api.exportCsv(snapshot);put("import.committed.csv.sha256",csvHash(snapshot));put("import.committed.state.sha256",stateHash(api));
            var error=shell("Could not import data");
            assertTrue(RemoteTestSupport.onUi(()->message(error.widget,UNKNOWN)));
            assertTrue(prefs.bot().button("Import...").isEnabled());
            error.bot().button("OK").click();
            var pending=keys();assertEquals("Unknown import must retain one durable receipt key",1,pending.size());
            put("import.pending.sha256",RemoteBootstrap.hash(pending.getFirst()));
            assertEquals(get("import.committed.state.sha256"),stateHash(api));RemoteTestSupport.closedLocalStorage();
            mark("phase.import.committed-timeout","passed");
        } finally {RemoteBootstrap.releaseTaskReply();}
        prefs.bot().button("Cancel").click();RemoteTestSupport.waitFor(()->!prefs.isOpen(),"Import preferences did not close for restart");
        RemoteTestSupport.ready();RemoteBootstrap.writeProof();
    }
    static void recover(HttpTimekeeperService api) throws Exception {
        if(!enabled()) {assertEquals("not-run-on-this-platform",get("phase.import.native"));return;}
        assertEquals("passed",get("phase.import.committed-timeout"));
        assertEquals("Server restart changed the committed import",get("import.committed.state.sha256"),stateHash(api));
        var pending=keys();assertEquals(1,pending.size());assertEquals(get("import.pending.sha256"),RemoteBootstrap.hash(pending.getFirst()));
        Path exported=Path.of(get("import.csv.folder"));assertEquals(get("import.csv.sha256"),csvHash(exported));
        Path before=RemoteBootstrap.fixtureFolder().resolve("lost-import-before-replay");api.exportCsv(before);
        assertEquals(get("import.committed.csv.sha256"),csvHash(before));
        mark("phase.import.retained-key","passed");
        var prefs=open();selectImport(prefs,exported);
        RemoteTestSupport.confirmTransferResult("Data imported","Could not import data");
        assertTrue("Acknowledged retry did not clear the pending receipt key",keys().isEmpty());
        assertEquals("Retry created another import or changed record versions",get("import.committed.state.sha256"),stateHash(api));
        Path after=RemoteBootstrap.fixtureFolder().resolve("lost-import-after-replay");api.exportCsv(after);
        assertEquals("Retry changed exact IDs, graph or instants",get("import.committed.csv.sha256"),csvHash(after));
        prefs.bot().button("Cancel").click();RemoteTestSupport.waitFor(()->!prefs.isOpen(),"Reviewed import preferences did not close");
        var canonical=api.metadata();
        RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->{
            var service=TimekeeperUiPlugin.getDefault().getTimekeeperService();
            return service.projects().equals(canonical.projects()) && service.tasks().equals(canonical.tasks()) && service.labels().equals(canonical.labels());
        });}catch(Exception refreshing) {return false;}},"Replayed import did not reach the installed snapshot");
        RemoteTestSupport.closedLocalStorage();mark("phase.import.replayed-once","passed");mark("phase.import.native","passed");RemoteBootstrap.writeProof();
    }
    private static boolean enabled() {return Boolean.getBoolean("timekeeper.native.dialog-tests");}
    private static void selectImport(SWTBotShell prefs,Path folder) throws Exception {
        NativeGtkDialog.choose(IMPORT,folder,()->prefs.bot().button("Import...").click());
        shell("Import server data").bot().button("OK").click();
    }
    private static SWTBotShell open() {
        PlatformUI.getWorkbench().getDisplay().asyncExec(()->PreferencesUtil.createPreferenceDialogOn(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),"net.resheim.eclipse.timekeeper.ui.preferences.DatabasePreferences",null,null).open());
        return shell("Preferences");
    }
    private static SWTBotShell shell(String title) {
        var bot=new SWTWorkbenchBot();
        RemoteTestSupport.waitFor(()->Arrays.stream(bot.shells()).anyMatch(shell->shell.getText().equals(title)),"Expected import dialog did not appear: "+title);
        return bot.shell(title);
    }
    private static List<Path> keys() throws Exception {
        Path folder=TimekeeperUiPlugin.getDefault().getStateLocation().toFile().toPath().resolve("pending-imports");
        if(!Files.exists(folder)) return List.of();
        try(var files=Files.list(folder)) {return files.filter(p->p.getFileName().toString().endsWith(".key")).sorted().toList();}
    }
    private static String csvHash(Path folder) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        for(String name:List.of("projects.csv","tasks.csv","labels.csv","activities.csv","workSessions.csv","timekeeper.csv.json")) {
            byte[] data=Files.readAllBytes(folder.resolve(name));digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update(java.nio.ByteBuffer.allocate(8).putLong(data.length).array());digest.update(data);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String stateHash(HttpTimekeeperService api) throws Exception {
        var metadata=api.metadata();var state=new TreeMap<String,String>();
        state.put("revision",Long.toString(metadata.revision()));state.put("profile",metadata.profile().toString());
        assertTrue("Fixture must have no active interval",metadata.active().isEmpty());
        metadata.projects().forEach(value->state.put("project:"+value.id().value(),Long.toString(value.version())));
        metadata.tasks().forEach(value->state.put("task:"+value.id().value(),Long.toString(value.version())));
        metadata.labels().forEach(value->state.put("label:"+value.id().value(),Long.toString(value.version())));
        api.workSessions(OwnerId.LOCAL).forEach(value->state.put("work-session:"+value.id().value(),Long.toString(value.version())));
        api.activities(query(api)).activities().forEach(value->state.put("activity:"+value.id().value(),Long.toString(value.version())));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private static ActivityQuery query(HttpTimekeeperService api) {return new ActivityQuery(Instant.parse("2000-01-01T00:00:00Z"),Instant.parse("2100-01-01T00:00:00Z"),Optional.of(OwnerId.LOCAL),Optional.empty(),Optional.empty(),api.profile().zone());}
    private static boolean message(Composite parent,String expected) {
        for(var child:parent.getChildren()) {
            if(child instanceof Label label && label.isVisible() && label.getText().contains(expected)) return true;
            if(child instanceof Text text && text.isVisible() && text.getText().contains(expected)) return true;
            if(child instanceof Composite composite && message(composite,expected)) return true;
        }
        return false;
    }
    private static void put(String key,String value) {RemoteBootstrap.receipt().setProperty(key,value);}
    private static String get(String key) {return RemoteBootstrap.receipt().getProperty(key);}
    private static void mark(String key,String value) {put(key,value);}
}
