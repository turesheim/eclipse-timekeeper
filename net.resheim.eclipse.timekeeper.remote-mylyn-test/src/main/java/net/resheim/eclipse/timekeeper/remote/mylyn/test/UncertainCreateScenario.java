package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
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

/** Confirmed ordinary POSTs with lost replies must not be repeated from the same draft. */
final class UncertainCreateScenario {
    private static final String UNKNOWN="The save outcome is unknown. Cancel and refresh to review the records before creating again.";
    static void run(HttpTimekeeperService api,Project project) throws Exception {
        var bot=new SWTWorkbenchBot();var view=bot.viewByTitle("Workweek");
        String prefix="Uncertain create "+UUID.randomUUID().toString().substring(0,8);
        RemoteTestSupport.readyWithMylyn();view.setFocus();
        view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).select();view.toolbarButton("Create a Timekeeper task").click();
        var taskForm=bot.shell("New task");taskForm.bot().textWithLabel("Summary").setText(prefix+" task");taskForm.bot().textWithLabel("URL (optional)").setText("https://example.invalid/uncertain-create");
        held(taskForm,"tasks","OK",()->{
            var matches=api.tasks().stream().filter(t->t.summary().equals(prefix+" task")).toList();assertEquals(1,matches.size());
            assertEquals(prefix+" task",taskForm.bot().textWithLabel("Summary").getText());assertTrue(taskForm.bot().textWithLabel("Summary").isEnabled());
            assertEquals("https://example.invalid/uncertain-create",taskForm.bot().textWithLabel("URL (optional)").getText());
        });
        var task=api.tasks().stream().filter(t->t.summary().equals(prefix+" task")).findFirst().orElseThrow();settledTask(task,project);
        var date=LocalDate.now(api.profile().zone());
        view.bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary()).contextMenu("New activity").click();
        var activityForm=bot.shell("New activity");activityForm.bot().textWithLabel("Summary").setText(prefix+" interval");
        activityForm.bot().textWithLabel("Start (yyyy-MM-dd HH:mm)").setText(date+" 08:00");activityForm.bot().textWithLabel("End (yyyy-MM-dd HH:mm)").setText(date+" 09:00");
        var query=new ActivityQuery(date.atStartOfDay(api.profile().zone()).toInstant(),date.plusDays(1).atStartOfDay(api.profile().zone()).toInstant(),Optional.of(OwnerId.LOCAL),Optional.of(task.id()),Optional.empty(),api.profile().zone());
        held(activityForm,"activities","Save",()->{
            var matches=api.activities(query).activities().stream().filter(a->a.summary().equals(prefix+" interval")).toList();assertEquals(1,matches.size());assertEquals(Duration.ofHours(1),api.activities(query).total());
            assertEquals(prefix+" interval",activityForm.bot().textWithLabel("Summary").getText());assertTrue(activityForm.bot().textWithLabel("Summary").isEnabled());
        });
        view.setFocus();view.toolbarButton("Create a Timekeeper project").click();var projectForm=bot.shell("New project");projectForm.bot().textWithLabel("Name").setText(prefix+" project");
        held(projectForm,"projects","OK",()->{assertEquals(1,api.projects().stream().filter(p->p.name().equals(prefix+" project")).count());assertEquals(prefix+" project",projectForm.bot().textWithLabel("Name").getText());assertTrue(projectForm.bot().textWithLabel("Name").isEnabled());});
        PlatformUI.getWorkbench().getDisplay().asyncExec(()->PreferencesUtil.createPreferenceDialogOn(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),"net.resheim.eclipse.timekeeper.ui.preferences.LabelPreferences",null,null).open());
        var labels=bot.shell("Preferences");labels.bot().button("Add").click();labels.bot().textWithLabel("Label:").setText(prefix+" label");
        held(labels,"labels","Apply and Close",()->{assertEquals(1,api.labels().stream().filter(l->l.name().equals(prefix+" label")).count());assertEquals(prefix+" label",labels.bot().textWithLabel("Label:").getText());assertTrue(labels.bot().textWithLabel("Label:").isEnabled());assertFalse(labels.bot().button("Apply").isEnabled());});
        // Explicit cancellation/review leaves the committed records discoverable and unique.
        var interval=api.activities(query).activities().getFirst();api.deleteActivity(new DeleteActivity(interval.id(),interval.version()));
        var current=api.task(task.id()).orElseThrow();api.deleteTask(new DeleteTask(current.id(),current.version()));
        var createdProject=api.projects().stream().filter(p->p.name().equals(prefix+" project")).findFirst().orElseThrow();api.deleteProject(new DeleteProject(createdProject.id(),createdProject.version()));
        var label=api.labels().stream().filter(l->l.name().equals(prefix+" label")).findFirst().orElseThrow();api.deleteLabel(new DeleteLabel(label.id(),label.version()));
        var cleaned=api.metadata();
        RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->{
            var service=TimekeeperUiPlugin.getDefault().getTimekeeperService();
            return service.tasks().equals(cleaned.tasks()) && service.projects().equals(cleaned.projects())
                && service.labels().equals(cleaned.labels()) && service.activeActivity(OwnerId.LOCAL).equals(cleaned.active());
        });}catch(Exception refreshing) {return false;}},"Uncertain-create cleanup did not reach the installed canonical snapshot");
        RemoteTestSupport.closedLocalStorage();
        for(String kind:List.of("tasks","projects","activities","labels")) RemoteBootstrap.receipt().setProperty("phase.create.uncertain."+kind,"passed");RemoteBootstrap.writeProof();
    }
    private static void held(SWTBotShell shell,String collection,String save,Runnable check) throws Exception {
        RemoteBootstrap.holdNextCreateReply(collection);
        try {
            shell.bot().button(save).click();RemoteBootstrap.awaitCreateReplyCommitted();
            RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->message(shell.widget,UNKNOWN) || message(shell.widget,"The server request timed out. Refresh before repeating a change."));}catch(Exception pending) {return false;}},"Committed create timeout did not require record review");
            assertTrue(shell.isOpen());assertFalse("Unknown create can be repeated from the same draft",shell.bot().button(save).isEnabled());check.run();
            RemoteTestSupport.closedLocalStorage();
        } finally {RemoteBootstrap.releaseTaskReply();}
        shell.bot().button("Cancel").click();RemoteTestSupport.waitFor(()->!shell.isOpen(),"Uncertain creation did not cancel for review");
        RemoteTestSupport.readyWithMylyn();checkAfterClose(collection);
    }
    private static void checkAfterClose(String collection) {RemoteBootstrap.receipt().setProperty("phase.create.reviewed."+collection,"passed");}
    private static void settledTask(Task task,Project project) {RemoteTestSupport.waitFor(()->{try {return new SWTWorkbenchBot().viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary())!=null;}catch(RuntimeException pending) {return false;}},"Committed task was not available for explicit review");}
    private static boolean message(Composite parent,String expected) {for(var child:parent.getChildren()) {if(child instanceof Text text && text.isVisible() && text.getText().contains(expected)) return true;if(child instanceof org.eclipse.swt.widgets.Label label && label.isVisible() && label.getText().contains(expected)) return true;if(child instanceof Composite composite && message(composite,expected)) return true;}return false;}
}
