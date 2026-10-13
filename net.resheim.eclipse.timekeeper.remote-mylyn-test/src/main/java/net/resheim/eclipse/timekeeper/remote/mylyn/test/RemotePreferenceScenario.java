package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

/** Actual preference controls, label command menus and report/transfer actions. */
final class RemotePreferenceScenario {
    private static final String LABELS="net.resheim.eclipse.timekeeper.ui.preferences.LabelPreferences";
    private static final String DATABASE="net.resheim.eclipse.timekeeper.ui.preferences.DatabasePreferences";
    private static final String TEMPLATES="net.resheim.eclipse.timekeeper.ui.preferences.TemplatePreferences";
    static void run(HttpTimekeeperService api) throws Exception {
        String prefix="Native transfer "+UUID.randomUUID().toString().substring(0,8);
        var project=api.createProject(new CreateProject(prefix+" project"));
        var task=api.createTask(new CreateTask(Optional.of(project.id()),prefix+" task",Optional.empty()));
        var zone=api.profile().zone();var date=LocalDate.now(zone);
        var start=date.atTime(8,0,0,123456789).atZone(zone).toInstant();
        var interval=api.createActivity(new CreateActivity(task.id(),OwnerId.LOCAL,start,Optional.of(start.plusNanos(1)),prefix+" interval",Set.of()));
        var bot=new SWTWorkbenchBot();
        try(var peer=new HttpTimekeeperService(RemoteBootstrap.endpoint(),RemoteBootstrap::observerBToken,Duration.ofSeconds(5))) {
            var emptyColor=peer.createLabel(new CreateLabel(prefix+" no color",Optional.empty()));
            awaitLabel(emptyColor);var preferences=open(LABELS);
            preferences.bot().table().select(emptyColor.name());
            assertEquals(emptyColor.name(),preferences.bot().textWithLabel("Label:").getText());
            preferences.bot().button("Add").click();preferences.bot().table().select("new label");
            preferences.bot().textWithLabel("Label:").setText(prefix+" label");preferences.bot().button("Apply and Close").click();
            RemoteTestSupport.waitFor(()->!preferences.isOpen(),"Remote label preference save did not close after acknowledgement");
            var label=api.labels().stream().filter(l->l.name().equals(prefix+" label")).findFirst().orElseThrow();assertEquals(Optional.of("0,0,0"),label.color());
            awaitLabel(label);
            var rejected=open(LABELS);rejected.bot().table().select(label.name());rejected.bot().textWithLabel("Label:").setText(prefix+" retained draft");
            var before=api.metadata();RemoteBootstrap.setAccountBWritable(false);
            try {
                rejected.bot().button("Apply and Close").click();
                RemoteTestSupport.waitFor(()->{try {return rejected.bot().textWithLabel("Label:").isEnabled() && RemoteTestSupport.onUi(()->message(rejected.widget,"This account does not have permission to perform that operation."));}catch(Exception loading) {return false;}},"Read-only label failure did not keep the editable preference draft");
                assertEquals(prefix+" retained draft",rejected.bot().textWithLabel("Label:").getText());assertEquals(before,peer.metadata());
            } finally {RemoteBootstrap.setAccountBWritable(true);}
            rejected.bot().button("Apply and Close").click();RemoteTestSupport.waitFor(()->!rejected.isOpen(),"Restored label permission did not save the same draft");
            var saved=api.label(label.id()).orElseThrow();assertEquals(label.version()+1,saved.version());assertEquals(prefix+" retained draft",saved.name());awaitLabel(saved);
            var stale=open(LABELS);stale.bot().table().select(saved.name());stale.bot().textWithLabel("Label:").setText(prefix+" stale draft");
            var changed=peer.updateLabel(new UpdateLabel(saved.id(),saved.version(),prefix+" peer label",Optional.of("19,171,205")));awaitLabel(changed);
            stale.bot().button("Apply and Close").click();
            RemoteTestSupport.waitFor(()->{try {return stale.bot().textWithLabel("Label:").isEnabled() && RemoteTestSupport.onUi(()->message(stale.widget,"The record changed. Refresh and review it before retrying."));}catch(Exception loading) {return false;}},"Concurrent label edit did not preserve its stale draft");
            assertEquals(prefix+" stale draft",stale.bot().textWithLabel("Label:").getText());assertEquals(changed,api.label(changed.id()).orElseThrow());stale.bot().button("Cancel").click();
            RemoteTestSupport.waitFor(()->!stale.isOpen(),"Stale label preferences did not cancel");
            awaitTree(project,task,interval.summary());
            bot.viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary()).expand().getNode(interval.summary()).contextMenu(changed.name()).click();
            RemoteTestSupport.waitFor(()->api.activity(interval.id()).orElseThrow().labelIds().contains(changed.id()),"Native label menu did not store the remote label");
            var labeled=api.activity(interval.id()).orElseThrow();assertEquals(interval.start(),labeled.start());assertEquals(interval.end(),labeled.end());
            copyReport(api,prefix,task);
            if(Boolean.getBoolean("timekeeper.native.dialog-tests")) nativeTransfers(api,prefix);
            else RemoteBootstrap.receipt().setProperty("phase.native.folder-dialogs","not-run-on-this-platform");
            var finalInterval=api.activity(interval.id()).orElseThrow();api.deleteActivity(new DeleteActivity(finalInterval.id(),finalInterval.version()));
            var finalTask=api.task(task.id()).orElseThrow();api.deleteTask(new DeleteTask(finalTask.id(),finalTask.version()));
            var finalProject=api.project(project.id()).orElseThrow();api.deleteProject(new DeleteProject(finalProject.id(),finalProject.version()));
            var remove=open(LABELS);remove.bot().table().select(changed.name());remove.bot().button("Remove").click();remove.bot().table().select(emptyColor.name());remove.bot().button("Remove").click();remove.bot().button("Apply and Close").click();
            RemoteTestSupport.waitFor(()->!remove.isOpen(),"Label removal preferences did not acknowledge");assertTrue(api.label(changed.id()).isEmpty());assertTrue(api.label(emptyColor.id()).isEmpty());
            RemoteTestSupport.closedLocalStorage();RemoteBootstrap.receipt().setProperty("phase.native.label-preferences","passed");RemoteBootstrap.receipt().setProperty("phase.native.report-controls","passed");RemoteBootstrap.writeProof();
        }
    }
    private static void copyReport(HttpTimekeeperService api,String prefix,Task task) throws Exception {
        var bot=new SWTWorkbenchBot();var preferences=open(TEMPLATES);preferences.bot().button("Add").click();
        var input=bot.shell("New template");input.bot().text().setText(prefix+" report");input.bot().button("OK").click();
        preferences.bot().styledText().setText("NATIVE-REPORT|${weeks[0].sum}|<#list weeks[0].projects as project,tasks>${project.name}|<#list tasks as task>${task.taskSummary}|</#list></#list>");
        preferences.bot().comboBoxWithLabel("Content type:").setSelection("Plain Text");preferences.bot().button("Apply and Close").click();RemoteTestSupport.waitFor(()->!preferences.isOpen(),"Report template preference did not close");
        var first=RemoteTestSupport.onUi(()->((WorkWeekView)PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().findView(WorkWeekView.VIEW_ID)).getFirstDayOfWeek());
        var zone=api.profile().zone();var query=new ActivityQuery(first.atStartOfDay(zone).toInstant(),first.plusDays(7).atStartOfDay(zone).toInstant(),Optional.of(OwnerId.LOCAL),Optional.empty(),Optional.empty(),zone);
        String expected="NATIVE-REPORT|"+api.activities(query).total()+"|";
        String old=clipboard();
        try {
            bot.viewByTitle("Workweek").setFocus();bot.viewByTitle("Workweek").toolbarDropDownButton("Export selected week to clipboard").menuItem("Copy as").click().menu(prefix+" report").click();
            RemoteTestSupport.waitFor(()->{try {String data=clipboard();return data!=null && data.startsWith(expected) && data.contains(task.summary());}catch(Exception loading) {return false;}},"Native clipboard report differs from selected remote week and exact total");
            if(Boolean.getBoolean("timekeeper.native.dialog-tests")) {
                Path output=RemoteBootstrap.fixtureFolder().resolve("native-week.txt");
                NativeGtkDialog.choose("Save Timekeeper report",output,()->bot.viewByTitle("Workweek").toolbarDropDownButton("Export selected week to clipboard").menuItem("Save as").click().menu(prefix+" report").click());
                RemoteTestSupport.waitFor(()->{try {return Files.readString(output).startsWith(expected) && Files.readString(output).contains(task.summary());}catch(Exception loading) {return false;}},"Native report file chooser did not produce the exact remote report");
            }
        } finally {RemoteTestSupport.onUi(()->{var clipboard=new Clipboard(PlatformUI.getWorkbench().getDisplay());try {if(old==null) clipboard.clearContents();else clipboard.setContents(new Object[]{old},new Transfer[]{TextTransfer.getInstance()});}finally {clipboard.dispose();}return null;});}
        var cleanup=open(TEMPLATES);cleanup.bot().list().select(prefix+" report");cleanup.bot().button("Remove").click();cleanup.bot().button("Apply and Close").click();RemoteTestSupport.waitFor(()->!cleanup.isOpen(),"Temporary report template did not clear");
    }
    private static void nativeTransfers(HttpTimekeeperService api,String prefix) throws Exception {
        var bot=new SWTWorkbenchBot();var before=api.metadata();var query=new ActivityQuery(Instant.parse("2000-01-01T00:00:00Z"),Instant.parse("2100-01-01T00:00:00Z"),Optional.of(OwnerId.LOCAL),Optional.empty(),Optional.empty(),before.profile().zone());
        var activities=api.activities(query).activities();Path parent=Files.createDirectories(RemoteBootstrap.fixtureFolder().resolve("native-csv"));var prefs=open(DATABASE);
        assertFalse(prefs.bot().radio("Specified by JDBC URL").isEnabled());
        NativeGtkDialog.cancel("Please choose a folder to store the exported files",()->prefs.bot().button("Export...").click());assertEquals(before,api.metadata());
        NativeGtkDialog.choose("Please choose a folder to store the exported files",parent,()->prefs.bot().button("Export...").click());bot.shell("Data exported").bot().button("OK").click();
        Path export;try(var children=Files.list(parent)) {export=children.filter(Files::isDirectory).findFirst().orElseThrow();}assertTrue(Files.isRegularFile(export.resolve("timekeeper.csv.json")));assertEquals(before,api.metadata());
        NativeGtkDialog.choose("Please choose a folder to import from",export,()->prefs.bot().button("Import...").click());bot.shell("Import server data").bot().button("Cancel").click();assertEquals(before,api.metadata());
        NativeGtkDialog.choose("Please choose a folder to import from",export,()->prefs.bot().button("Import...").click());bot.shell("Import server data").bot().button("OK").click();bot.shell("Data imported").bot().button("OK").click();
        var after=api.metadata();assertEquals(before.projects().size()*2,after.projects().size());assertEquals(before.tasks().size()*2,after.tasks().size());assertEquals(before.labels().size()*2,after.labels().size());
        var imported=api.activities(query).activities().stream().filter(a->activities.stream().noneMatch(old->old.id().equals(a.id()))).toList();assertEquals(activities.size(),imported.size());
        for(var old:activities) {assertEquals(old,api.activity(old.id()).orElseThrow());assertTrue(imported.stream().anyMatch(a->a.start().equals(old.start()) && a.end().equals(old.end()) && a.summary().equals(old.summary())));}
        for(var value:imported) api.deleteActivity(new DeleteActivity(value.id(),value.version()));
        for(var value:after.tasks()) if(before.tasks().stream().noneMatch(old->old.id().equals(value.id()))) api.deleteTask(new DeleteTask(value.id(),value.version()));
        for(var value:after.projects()) if(before.projects().stream().noneMatch(old->old.id().equals(value.id()))) api.deleteProject(new DeleteProject(value.id(),value.version()));
        for(var value:after.labels()) if(before.labels().stream().noneMatch(old->old.id().equals(value.id()))) api.deleteLabel(new DeleteLabel(value.id(),value.version()));
        prefs.bot().button("Cancel").click();RemoteTestSupport.waitFor(()->!prefs.isOpen(),"CSV preferences did not close");RemoteTestSupport.closedLocalStorage();RemoteBootstrap.receipt().setProperty("phase.native.folder-dialogs","passed");
    }
    private static String clipboard() throws Exception {return RemoteTestSupport.onUi(()->{var clipboard=new Clipboard(PlatformUI.getWorkbench().getDisplay());try {return (String)clipboard.getContents(TextTransfer.getInstance());}finally {clipboard.dispose();}});}
    private static SWTBotShell open(String page) {
        PlatformUI.getWorkbench().getDisplay().asyncExec(()->PreferencesUtil.createPreferenceDialogOn(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),page,new String[]{page},null).open());return new SWTWorkbenchBot().shell("Preferences");
    }
    private static void awaitLabel(net.resheim.eclipse.timekeeper.domain.Label label) {RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().getTimekeeperService().label(label.id()).filter(label::equals).isPresent());}catch(Exception loading) {return false;}},"Remote label did not reach native preference snapshot");}
    private static void awaitTree(Project project,Task task,String summary) {RemoteTestSupport.waitFor(()->{try {return new SWTWorkbenchBot().viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary()).expand().getNode(summary)!=null;}catch(RuntimeException loading) {return false;}},"Remote label fixture did not reach Workweek");}
    private static boolean message(Composite parent,String expected) {for(var child:parent.getChildren()) {if(child instanceof Text text && text.isVisible() && text.getText().contains(expected)) return true;if(child instanceof Composite composite && message(composite,expected)) return true;}return false;}
}
