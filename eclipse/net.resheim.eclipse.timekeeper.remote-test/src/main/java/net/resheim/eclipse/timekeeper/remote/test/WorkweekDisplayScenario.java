package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.time.Duration;
import java.util.*;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

/** Real weekly cells, hierarchy controls and duration preferences over owned records. */
final class WorkweekDisplayScenario {
    static void run() throws Exception {
        var bot=new SWTWorkbenchBot();var view=bot.viewByTitle("Workweek");
        boolean original=TimekeeperUiPlugin.getDefault().getPreferenceStore().getBoolean(PreferenceConstants.DECIMAL_HOURS);
        view.toolbarButton("Show previous week").click();
        var first=RemoteTestSupport.onUi(()->((WorkWeekView)PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().findView(WorkWeekView.VIEW_ID)).getFirstDayOfWeek());
        try(var api=RemoteTestSupport.client()) {
            var zone=api.profile().zone();var start=first.atTime(9,0).atZone(zone).toInstant();
            var project=api.createProject(new CreateProject("Weekly display fixture"));
            var parent=api.createTask(new CreateTask(Optional.of(project.id()),"Weekly parent",Optional.empty()));
            var child=api.createTask(new CreateTask(Optional.of(project.id()),Optional.of(parent.id()),"Weekly child",Optional.empty()));
            var rounding=api.createTask(new CreateTask(Optional.of(project.id()),"Weekly rounding",Optional.empty()));
            var records=new ArrayList<Activity>();
            records.add(api.createActivity(new CreateActivity(parent.id(),OwnerId.LOCAL,start,Optional.of(start.plusSeconds(5400)),"Parent interval",Set.of())));
            records.add(api.createActivity(new CreateActivity(child.id(),OwnerId.LOCAL,start,Optional.of(start.plusSeconds(2700)),"Child interval",Set.of())));
            records.add(api.createActivity(new CreateActivity(rounding.id(),OwnerId.LOCAL,start,Optional.of(start.plusSeconds(9)),"First rounding interval",Set.of())));
            var tomorrow=first.plusDays(1).atTime(9,0).atZone(zone).toInstant();
            records.add(api.createActivity(new CreateActivity(rounding.id(),OwnerId.LOCAL,tomorrow,Optional.of(tomorrow.plusSeconds(9)),"Second rounding interval",Set.of())));
            var tree=view.bot().treeWithId("workweek-editor-tree");
            RemoteTestSupport.waitFor(()->{try {return tree.getTreeItem(project.name()).expand().getNode(parent.summary()).expand().getNode(child.summary())!=null;}catch(RuntimeException pending) {return false;}},"Weekly hierarchy did not reach the installed view");
            var projectRow=tree.getTreeItem(project.name());var parentRow=projectRow.getNode(parent.summary());
            setFormat(false);
            RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->projectRow.widget.getText(8).equals("2:15") && parentRow.widget.getText(8).equals("2:15"));}catch(Exception pending) {return false;}},"Weekly totals did not aggregate child time");
            var before=api.metadata();
            view.toolbarButton("Hide activities and keep projects and tasks visible").click();
            assertTrue(RemoteTestSupport.onUi(()->projectRow.widget.getExpanded() && parentRow.widget.getExpanded()));
            assertTrue(RemoteTestSupport.onUi(()->Arrays.stream(parentRow.widget.getItems()).noneMatch(item->item.getData() instanceof Activity)));
            assertNotNull(parentRow.getNode(child.summary()));
            view.toolbarButton("Expand all projects and tasks").click();assertNotNull(parentRow.getNode("Parent interval"));
            setFormat(true);
            RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->projectRow.widget.getText(8).equals("2.26") && parentRow.widget.getText(8).equals("2.25") && Arrays.stream(projectRow.widget.getItems()).anyMatch(item->item.getText().equals(rounding.summary()) && item.getText(8).equals("0.01")));}catch(Exception pending) {return false;}},"Decimal weekly totals rounded individual days instead of the exact aggregate");
            assertEquals(before,api.metadata());for(var value:records) assertEquals(value,api.activity(value.id()).orElseThrow());
            for(var value:records) api.deleteActivity(new DeleteActivity(value.id(),value.version()));
            api.deleteTask(new DeleteTask(child.id(),child.version()));api.deleteTask(new DeleteTask(parent.id(),parent.version()));api.deleteTask(new DeleteTask(rounding.id(),rounding.version()));api.deleteProject(new DeleteProject(project.id(),project.version()));
            RemoteBootstrap.proof.setProperty("phase.workweek.display-options","passed");RemoteBootstrap.writeProof();
        } finally {setFormat(original);view.toolbarButton("Show current week").click();}
    }
    private static void setFormat(boolean decimal) {
        PlatformUI.getWorkbench().getDisplay().asyncExec(()->PreferencesUtil.createPreferenceDialogOn(
            PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),"net.resheim.eclipse.timekeeper.ui.preferences.TimekeeperPreferences",null,null).open());
        var dialog=new SWTWorkbenchBot().shell("Preferences");var checkbox=dialog.bot().checkBox("Show durations as decimal hours");
        if(checkbox.isChecked()!=decimal) checkbox.click();dialog.bot().button("Apply and Close").click();
        RemoteTestSupport.waitFor(()->!dialog.isOpen(),"Duration preferences did not close");
    }
}
