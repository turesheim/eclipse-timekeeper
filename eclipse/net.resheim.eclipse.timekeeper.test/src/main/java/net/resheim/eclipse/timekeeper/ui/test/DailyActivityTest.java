package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

/** Real Eclipse editor and Workweek rows for a manual interval across midnight. */
public class DailyActivityTest {
    @Test public void manualDayPartsAreVisibleAndEditedIndependently() throws Exception {
        var bot=new SWTWorkbenchBot();
        bot.waitUntil(new DefaultCondition() {
            public boolean test() {return TimekeeperPlugin.getDefault().isReady();}
            public String getFailureMessage() {return "Embedded database did not become ready";}
        },30000);
        onUi(()->{
            try {var window=PlatformUI.getWorkbench().getActiveWorkbenchWindow();window.getShell().setSize(1100,700);
                window.getShell().forceActive();window.getActivePage().showView(WorkWeekView.VIEW_ID);}
            catch(Exception failure) {throw new AssertionError(failure);}
        });
        var service=TimekeeperUiPlugin.getDefault().getTimekeeperService();
        var project=service.createProject(new CreateProject("Daily editor "+UUID.randomUUID()));
        var task=service.createTask(new CreateTask(Optional.of(project.id()),"Daily editor task",Optional.empty()));
        var zone=TimekeeperUiPlugin.getCalendarZone();
        var monday=LocalDate.now(zone).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        var query=new ActivityQuery(monday.atStartOfDay(zone).toInstant(),monday.plusDays(7).atStartOfDay(zone).toInstant(),
                Optional.of(OwnerId.LOCAL),Optional.of(task.id()),Optional.empty(),zone);
        try {
            bot.viewByTitle("Workweek").setFocus();bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
            bot.viewByTitle("Workweek").toolbarButton("Show previous week").click();
            bot.waitUntil(new DefaultCondition() {
                public boolean test() {try {return bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode(task.summary())!=null;}catch(RuntimeException failure){return false;}}
                public String getFailureMessage() {return "Daily editor task did not appear";}
            });
            bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary()).contextMenu("New activity").click();
            bot.shell("New activity").activate();bot.textWithLabel("Summary").setText("Across midnight");
            bot.textWithLabel("Start (yyyy-MM-dd HH:mm)").setText(monday+" 23:30");
            bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(monday.plusDays(1)+" 01:30");
            var label=service.labels().getFirst();bot.tableWithId("activity-labels").getTableItem(label.name()).check();bot.button("Save").click();
            bot.waitUntil(new DefaultCondition() {
                public boolean test() {return service.activities(query).activities().size()==2;}
                public String getFailureMessage() {return "Manual interval was not stored as two days";}
            });
            var report=service.activities(query);var first=report.activities().getFirst();var last=report.activities().getLast();
            assertEquals(Duration.ofHours(2),report.total());assertEquals(first.end().orElseThrow(),last.start());
            for(var part:report.activities()) {assertTrue(part.manual());assertEquals(Set.of(label.id()),part.labelIds());}
            // Give each independent row a distinct summary before opening the second through Workweek.
            service.updateActivity(new UpdateActivity(first.id(),first.version(),first.start(),first.end(),"Earlier day",first.labelIds()));
            var earlier=service.activity(first.id()).orElseThrow();
            bot.waitUntil(new DefaultCondition() {
                public boolean test() {try {var node=bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary()).expand();return node.getItems().length==2 && node.getNode("Across midnight")!=null;}catch(RuntimeException failure){return false;}}
                public String getFailureMessage() {return "Daily activity rows did not refresh";}
            });
            bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).getNode(task.summary()).getNode("Across midnight").contextMenu("Edit activity...").click();
            bot.shell("Edit activity").activate();assertEquals(monday.plusDays(1)+" 00:00",bot.textWithLabel("Start (yyyy-MM-dd HH:mm)").getText());
            bot.textWithLabel("Summary").setText("Edited later day");bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(monday.plusDays(1)+" 02:00");bot.button("Save").click();
            assertEquals(earlier,service.activity(first.id()).orElseThrow());assertEquals("Edited later day",service.activity(last.id()).orElseThrow().summary());
            assertEquals(Duration.ofMinutes(150),service.activities(query).total());
        } finally {
            for(var activity:service.activities(query).activities()) service.deleteActivity(new DeleteActivity(activity.id(),activity.version()));
            service.deleteTask(new DeleteTask(task.id(),service.task(task.id()).orElseThrow().version()));
            service.deleteProject(new DeleteProject(project.id(),service.project(project.id()).orElseThrow().version()));
        }
    }
    @Test public void tasklessActivitiesAppearAndCanBeEditedWithoutAPlaceholderTask() throws Exception {
        var bot=new SWTWorkbenchBot();
        bot.waitUntil(new DefaultCondition() {
            public boolean test() {return TimekeeperPlugin.getDefault().isReady();}
            public String getFailureMessage() {return "Embedded database did not become ready";}
        },30000);
        onUi(()->{try {var window=PlatformUI.getWorkbench().getActiveWorkbenchWindow();window.getShell().forceActive();window.getActivePage().showView(WorkWeekView.VIEW_ID);}
            catch(Exception failure) {throw new AssertionError(failure);}});
        var service=TimekeeperUiPlugin.getDefault().getTimekeeperService();var zone=TimekeeperUiPlugin.getCalendarZone();
        var monday=LocalDate.now(zone).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        var from=monday.atTime(9,0).atZone(zone).toInstant();var end=from.plusSeconds(3600);
        var label=service.labels().getFirst();var tasks=service.tasks();
        var project=service.createProject(new CreateProject("Taskless project "+UUID.randomUUID()));
        var standalone=service.createActivity(new CreateActivity(null,OwnerId.LOCAL,from,Optional.of(end),"Unassigned activity check",Set.of(label.id())));
        var direct=service.createActivity(new CreateActivity(null,OwnerId.LOCAL,from,Optional.of(end),"Direct project check",Set.of(),Optional.empty(),Optional.of(project.id())));
        try {
            bot.viewByTitle("Workweek").setFocus();bot.viewByTitle("Workweek").toolbarButton("Show current week").click();
            bot.viewByTitle("Workweek").toolbarButton("Show previous week").click();
            bot.waitUntil(new DefaultCondition() {
                public boolean test() {try {return bot.treeWithId("workweek-editor-tree").getTreeItem("Unassigned activity check")!=null
                        && bot.treeWithId("workweek-editor-tree").getTreeItem(project.name()).expand().getNode("Direct project check")!=null;}
                    catch(RuntimeException failure) {return false;}}
                public String getFailureMessage() {return "Taskless activities did not appear at the root and under their direct project";}
            });
            bot.treeWithId("workweek-editor-tree").getTreeItem("Unassigned activity check").contextMenu("Edit activity...").click();
            bot.shell("Edit activity").activate();bot.textWithLabel("Summary").setText("Edited unassigned activity");bot.button("Save").click();
            var edited=service.activity(standalone.id()).orElseThrow();assertTrue(edited.task().isEmpty());assertEquals(standalone.labelIds(),edited.labelIds());
            assertEquals("Edited unassigned activity",edited.summary());assertEquals(standalone.start(),edited.start());assertEquals(standalone.end(),edited.end());
            assertEquals(tasks,service.tasks());
        } finally {
            for(var value:List.of(standalone,direct)) {
                var current=service.activity(value.id()).orElseThrow();service.deleteActivity(new DeleteActivity(current.id(),current.version()));
            }
            service.deleteProject(new DeleteProject(project.id(),service.project(project.id()).orElseThrow().version()));
        }
    }
    private static void onUi(Runnable work) throws Exception {
        var task=new FutureTask<Void>(work,null);PlatformUI.getWorkbench().getDisplay().syncExec(task);task.get(5,TimeUnit.SECONDS);
    }
}
