package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Chromium and the actual p2-installed Workweek exchange changes through one server. */
@SuppressWarnings("restriction")
final class RemoteWebScenario {
    static void run(HttpTimekeeperService api) throws Exception {
        String prefix="Web Eclipse "+UUID.randomUUID().toString().substring(0,8);
        ZoneId originalZone=api.profile().zone(),zone=ZoneId.of("Europe/Oslo");
        LocalDate date=LocalDate.now(zone),first=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant start=date.atTime(8,0,0,123456789).atZone(zone).toInstant(),end=start.plusNanos(1);
        var query=new ActivityQuery(first.atStartOfDay(zone).toInstant(),first.plusDays(7).atStartOfDay(zone).toInstant(),Optional.of(OwnerId.LOCAL),Optional.empty(),Optional.empty(),zone);
        try(var peer=new BrowserPeer()) {
            peer.command(Map.of("action","create","prefix",prefix,"zone",zone.getId(),"date",date.toString(),"start",start.toString(),"end",end.toString()));
            var project=api.projects().stream().filter(p->p.name().equals(prefix+" project")).findFirst().orElseThrow();
            var task=api.tasks().stream().filter(t->t.summary().equals(prefix+" task")).findFirst().orElseThrow();
            var label=api.labels().stream().filter(l->l.name().equals(prefix+" label")).findFirst().orElseThrow();
            assertEquals(Optional.of(project.id()),task.projectId());assertEquals("remote-owner-b",api.profile().owner().value());
            var taskQuery=new ActivityQuery(query.fromInclusive(),query.toExclusive(),query.ownerId(),Optional.of(task.id()),Optional.empty(),zone);
            var manual=api.activities(taskQuery).activities().getFirst();assertEquals(start,manual.start());assertEquals(Optional.of(end),manual.end());assertEquals(Set.of(label.id()),manual.labelIds());
            assertEquals(Duration.ofNanos(1),api.activities(taskQuery).total());
            awaitSnapshot(task);awaitCalendarZone(zone);awaitReport(taskQuery,Duration.ofNanos(1));
            var bot=new SWTWorkbenchBot();var tree=bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree");
            awaitTree(project,task);
            RemoteTestSupport.waitFor(()->{try {return tree.getTreeItem(project.name()).getNode(task.summary()).expand().getNode(manual.summary())!=null;}catch(RuntimeException loading) {return false;}},"Browser interval was not rendered in installed Workweek");
            tree.getTreeItem(project.name()).getNode(task.summary()).contextMenu("Edit task...").click();
            var shell=bot.shell("Edit task");var controls=shell.bot();String nativeDraft=prefix+" native draft";
            controls.textWithLabel("Summary").setText(nativeDraft);
            peer.command(Map.of("action","edit","summary",prefix+" browser edit","url","https://example.invalid/browser-edit"));
            var changed=api.task(task.id()).orElseThrow();awaitSnapshot(changed);assertEquals(prefix+" browser edit",changed.summary());
            assertEquals(nativeDraft,controls.textWithLabel("Summary").getText());controls.button("OK").click();
            RemoteTestSupport.waitFor(()->{try {return controls.button("OK").isEnabled() && RemoteTestSupport.onUi(()->message(shell.widget,"This record has changed. Close and reopen the editor before saving."));}catch(Exception pending) {return false;}},"Browser edit replaced or silently upgraded the Eclipse draft");
            assertEquals(nativeDraft,controls.textWithLabel("Summary").getText());assertEquals(changed,api.task(task.id()).orElseThrow());controls.button("Cancel").click();
            RemoteTestSupport.waitFor(()->!shell.isOpen(),"Stale Eclipse editor did not close");

            String webDraft=prefix+" web draft",nativeSummary=prefix+" native reviewed edit";
            peer.command(Map.of("action","draft","summary",webDraft,"url","https://example.invalid/web-draft"));
            awaitTree(project,changed);tree.getTreeItem(project.name()).getNode(changed.summary()).contextMenu("Edit task...").click();
            var reviewed=bot.shell("Edit task");reviewed.bot().textWithLabel("Summary").setText(nativeSummary);reviewed.bot().textWithLabel("URL (optional)").setText("https://example.invalid/native-review");reviewed.bot().button("OK").click();
            RemoteTestSupport.waitFor(()->api.task(task.id()).filter(t->t.summary().equals(nativeSummary)).isPresent(),"Reviewed native edit was not committed");
            RemoteTestSupport.waitFor(()->!reviewed.isOpen(),"Reviewed native editor did not close");
            var accepted=api.task(task.id()).orElseThrow();assertEquals(changed.version()+1,accepted.version());
            peer.command(Map.of("action","conflict","summary",nativeSummary,"draft",webDraft));assertEquals(accepted,api.task(task.id()).orElseThrow());

            awaitTree(project,accepted);tree.getTreeItem(project.name()).getNode(accepted.summary()).contextMenu("Activate task").click();
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(a->a.taskId().equals(task.id())).isPresent(),"Native activation did not start browser-created task");
            var nativeActive=api.activeActivity(OwnerId.LOCAL).orElseThrow();assertEquals("remote-owner-b",nativeActive.ownerId().value());
            peer.command(Map.of("action","observe-active"));peer.command(Map.of("action","stop"));
            RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().activeActivity(OwnerId.LOCAL).isEmpty());}catch(Exception pending) {return false;}},"Browser stop did not refresh installed tracking state");
            assertTrue(api.activity(nativeActive.id()).orElseThrow().end().isPresent());
            peer.command(Map.of("action","start"));
            RemoteTestSupport.waitFor(()->{try {return tree.getTreeItem(project.name()).getNode(accepted.summary()).contextMenu("Deactivate task").isEnabled();}catch(RuntimeException pending) {return false;}},"Browser start did not update native Deactivate action");
            var webActive=api.activeActivity(OwnerId.LOCAL).orElseThrow();assertEquals(task.id(),webActive.taskId());assertEquals(Set.of(label.id()),webActive.labelIds());
            RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().activeActivity(OwnerId.LOCAL).filter(webActive::equals).isPresent());}catch(Exception pending) {return false;}},"Browser start did not reach actual installed activity snapshot");
            tree.getTreeItem(project.name()).getNode(accepted.summary()).contextMenu("Deactivate task").click();
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).isEmpty(),"Native action did not stop browser-started interval");peer.command(Map.of("action","observe-stopped"));
            var ownReport=api.activities(taskQuery);assertEquals(3,ownReport.activities().size());awaitReport(taskQuery,ownReport.total());
            var weekly=api.activities(query);awaitReport(query,weekly.total());
            var days=new ArrayList<String>();
            for(int index=0;index<7;index++) {
                LocalDate day=first.plusDays(index);var daily=new ActivityQuery(day.atStartOfDay(zone).toInstant(),day.plusDays(1).atStartOfDay(zone).toInstant(),query.ownerId(),Optional.empty(),Optional.empty(),zone);
                Duration total=api.activities(daily).total();awaitReport(daily,total);
                days.add("{\"date\":"+json(day.toString())+",\"total\":"+json(total.toString())+"}");
            }
            peer.command(Map.of("action","report","zone",zone.getId(),"total",weekly.total().toString(),"days","["+String.join(",",days)+"]"));
            RemoteTestSupport.closedLocalStorage();
            peer.command(Map.of("action","cleanup","zone",originalZone.getId()));
            RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->{var s=TimekeeperUiPlugin.getDefault().getTimekeeperService();return s.task(task.id()).isEmpty() && s.project(project.id()).isEmpty() && s.label(label.id()).isEmpty();});}catch(Exception pending) {return false;}},"Browser cleanup did not remove installed records");
            RemoteTestSupport.waitFor(()->MylynIntegration.getDefault().linked(accepted).isEmpty(),"Browser deletion retained a Mylyn projection");awaitCalendarZone(originalZone);
            for(String flag:List.of("records","drafts","tracking","reports","cleanup")) RemoteBootstrap.receipt().setProperty("phase.web.eclipse."+flag,"passed");RemoteBootstrap.writeProof();
        }
    }
    private static void awaitTree(Project project,Task task) {
        RemoteTestSupport.waitFor(()->{try {return new SWTWorkbenchBot().viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary())!=null;}catch(RuntimeException loading) {return false;}},"Shared task did not appear in installed Workweek");
    }
    private static void awaitSnapshot(Task task) {
        RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().task(task.id()).filter(task::equals).isPresent());}catch(Exception loading) {return false;}},"Browser change did not reach installed snapshot automatically");
    }
    private static void awaitCalendarZone(ZoneId zone) {
        RemoteTestSupport.waitFor(()->TimekeeperUiPlugin.getCalendarZone().equals(zone),"Browser time-zone preference did not reach installed calendar");
    }
    private static void awaitReport(ActivityQuery query,Duration total) {
        RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().activities(query).total().equals(total));}catch(Exception loading) {return false;}},"Installed cached report differs from exact browser/server range");
    }
    private static boolean message(Composite parent,String expected) {
        for(var child:parent.getChildren()) {if(child instanceof Text text && text.isVisible() && text.getText().trim().equals(expected)) return true;if(child instanceof Composite composite && message(composite,expected)) return true;}return false;
    }
    private static String json(String value) {
        var result=new StringBuilder("\"");
        for(char c:value.toCharArray()) {if(c=='"' || c=='\\') result.append('\\').append(c);else if(c<32) result.append(String.format("\\u%04x",(int)c));else result.append(c);}return result.append('"').toString();
    }
    static final class BrowserPeer implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;
        private final BufferedWriter input;
        private final ExecutorService reader=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Timekeeper browser proof reader");t.setDaemon(true);return t;});
        BrowserPeer() throws Exception {
            Path root=Path.of(System.getProperty("timekeeper.web.root")).toAbsolutePath();
            var builder=new ProcessBuilder("node",root.resolve("web/tests/eclipse-peer.js").toString()).directory(root.toFile());
            builder.environment().put("TIMEKEEPER_WEB_URL",RemoteBootstrap.endpoint().toString().replaceFirst("/api/v1/?$",""));
            builder.environment().put("TIMEKEEPER_WEB_TOKEN",RemoteBootstrap.observerBToken());
            builder.environment().put("TIMEKEEPER_WEB_FAILURE_IMAGE",RemoteBootstrap.fixtureFolder().resolve("browser-peer-failure.png").toString());
            builder.environment().put("TIMEKEEPER_WEB_FAILURE_TEXT",RemoteBootstrap.fixtureFolder().resolve("browser-peer-failure.txt").toString());
            builder.environment().put("TIMEKEEPER_WEB_FAILURE_RECORDS",RemoteBootstrap.fixtureFolder().resolve("browser-peer-failure-records.json").toString());
            builder.redirectError(RemoteBootstrap.fixtureFolder().resolve("browser-peer.log").toFile());process=builder.start();
            input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));output=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
            RemoteBootstrap.receipt().setProperty("web.browser-driver.pid",Long.toString(process.pid()));
            String pids=RemoteBootstrap.receipt().getProperty("web.browser-driver.pids","");
            RemoteBootstrap.receipt().setProperty("web.browser-driver.pids",pids.isEmpty() ? Long.toString(process.pid()) : pids+","+process.pid());
        }
        void command(Map<String,String> values) throws Exception {
            var fields=values.entrySet().stream().map(e->json(e.getKey())+":"+json(e.getValue())).toList();input.write("{"+String.join(",",fields)+"}\n");input.flush();
            String reply=reader.submit(output::readLine).get(45,TimeUnit.SECONDS);
            assertEquals("Browser peer action "+values.get("action")+" failed: "+reply,"{\"ok\":true}",reply);
        }
        @Override public void close() throws Exception {
            try {if(process.isAlive()) {input.write("{\"action\":\"quit\"}\n");input.flush();input.close();if(!process.waitFor(10,TimeUnit.SECONDS)) {process.descendants().forEach(ProcessHandle::destroy);process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly();}}}
            finally {reader.shutdownNow();input.close();output.close();}
            assertFalse("Browser driver did not exit",process.isAlive());assertEquals("Browser driver reported a failed action",0,process.exitValue());
        }
    }
}
