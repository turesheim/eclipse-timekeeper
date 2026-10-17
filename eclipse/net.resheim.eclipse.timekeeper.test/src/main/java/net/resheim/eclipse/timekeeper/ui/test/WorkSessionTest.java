package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

/** Exercises actual SWT controls against the embedded database, without mock dialogs. */
public class WorkSessionTest {
    private final SWTWorkbenchBot bot=new SWTWorkbenchBot();

    @Test public void sessionTimersAreIndependentAndPermitMultipleSessionsPerDay() throws Exception {
        open();
        var activities=TimekeeperUiPlugin.getDefault().getTimekeeperService();
        var sessions=TimekeeperUiPlugin.getDefault().getWorkSessionService();
        var tasks=activities.tasks().stream().map(Task::id).collect(java.util.stream.Collectors.toSet());
        waitFor(()->bot.button("Start work session").isEnabled(),"Start button unavailable");
        bot.button("Start work session").click();
        waitFor(()->sessions.activeWorkSession(OwnerId.LOCAL).isPresent(),"Work session was not started");
        var first=sessions.activeWorkSession(OwnerId.LOCAL).orElseThrow();
        var ownedSessions=new ArrayList<WorkSessionId>();ownedSessions.add(first.id());
        ActivityId createdActivity=null;
        try {
            // Other integration scenarios may leave their timer running. Preserve it;
            // session controls must also work independently of an existing activity.
            var existingActivity=activities.activeActivity(OwnerId.LOCAL);
            var activity=existingActivity.orElseGet(()->activities.startActivity(
                    new StartActivity(null,OwnerId.LOCAL,"Independent timer",Set.of(),Optional.empty(),Optional.empty())));
            if(existingActivity.isEmpty()) createdActivity=activity.id();
            waitFor(()->bot.labelWithId("work-session-status").getText().contains("Running") && bot.button("Stop work session").isEnabled(),"Running status missing");
            bot.button("Stop work session").click();
            waitFor(()->sessions.workSession(first.id()).orElseThrow().end().isPresent(),"Session did not stop");
            assertEquals(activity.id(),activities.activeActivity(OwnerId.LOCAL).orElseThrow().id());
            assertTrue(activities.activity(activity.id()).orElseThrow().end().isEmpty());
            waitFor(()->bot.button("Start work session").isEnabled(),"Second session unavailable");
            bot.button("Start work session").click();
            waitFor(()->sessions.activeWorkSession(OwnerId.LOCAL).isPresent(),"Second session did not start");
            var second=sessions.activeWorkSession(OwnerId.LOCAL).orElseThrow();ownedSessions.add(second.id());
            assertNotEquals(first.id(),second.id());
            waitFor(()->bot.button("Stop work session").isEnabled(),"Second stop unavailable");
            bot.button("Stop work session").click();
            waitFor(()->sessions.activeWorkSession(OwnerId.LOCAL).isEmpty(),"Second session did not stop");
            assertEquals(tasks,activities.tasks().stream().map(Task::id).collect(java.util.stream.Collectors.toSet()));
        } finally {
            for(var id:ownedSessions) {
                var current=sessions.workSession(id).orElseThrow();sessions.deleteWorkSession(new DeleteWorkSession(current.id(),current.version()));
            }
            if(createdActivity!=null) {
                var current=activities.activity(createdActivity).orElseThrow();
                if(current.end().isEmpty()) current=activities.stopActivity(new StopActivity(current.id(),current.version()));
                activities.deleteActivity(new DeleteActivity(current.id(),current.version()));
            }
        }
    }

    @Test public void retrospectiveSessionDerivesTasklessOverlapAndPreservesStaleEdits() throws Exception {
        open();
        var activities=TimekeeperUiPlugin.getDefault().getTimekeeperService();
        var sessions=TimekeeperUiPlugin.getDefault().getWorkSessionService();
        var tasks=activities.tasks().stream().map(Task::id).collect(java.util.stream.Collectors.toSet());var zone=TimekeeperUiPlugin.getCalendarZone();
        var from=LocalDate.of(2007,10,25).atTime(8,0).atZone(zone).toInstant().plusNanos(123);
        var until=from.plus(Duration.ofHours(10));
        if(!bot.toggleButton("Session details").isPressed()) bot.toggleButton("Session details").click();
        waitFor(()->bot.button("Record session").isEnabled(),"Record button unavailable");
        bot.button("Record session").click();bot.shell("Record work session").activate();
        bot.textWithLabel("Summary").setText("Retrospective session");bot.textWithLabel("Start (local time)").setText(net.resheim.eclipse.timekeeper.ui.views.LocalTimeInput.format(from,TimekeeperUiPlugin.getCalendarZone()));
        bot.textWithLabel("End (local time, blank while running)").setText(net.resheim.eclipse.timekeeper.ui.views.LocalTimeInput.format(until,TimekeeperUiPlugin.getCalendarZone()));bot.button("Save").click();
        waitFor(()->sessions.workSessions(OwnerId.LOCAL).stream().anyMatch(value->value.summary().equals("Retrospective session")),"Manual session was not saved");
        var session=sessions.workSessions(OwnerId.LOCAL).stream().filter(value->value.summary().equals("Retrospective session")).findFirst().orElseThrow();
        var independent=activities.createActivity(new CreateActivity(null,OwnerId.LOCAL,from.minusSeconds(3600),Optional.of(until.plusSeconds(3600)),"Outside association",Set.of()));
        try {
            waitFor(()->bot.comboBoxWithId("work-session-history").selection().contains("Retrospective session"),"Recorded session was not selected");
            activities.createActivity(new CreateActivity(null,OwnerId.LOCAL,from.plusSeconds(3600),Optional.of(from.plusSeconds(14400)),"Overlapping interval",Set.of()));
            waitFor(()->sessions.workSessionReport(session.id()).activities().size()==2,"Temporal context missing");
            var metric=sessions.workSessionReport(session.id()).time();
            assertEquals(Duration.ofHours(13),metric.trackedActivityTime());assertEquals(Duration.ofHours(10),metric.allocatedTime());assertEquals(Duration.ZERO,metric.unallocatedTime());
            assertEquals(tasks,activities.tasks().stream().map(Task::id).collect(java.util.stream.Collectors.toSet()));
            waitFor(()->bot.tableWithId("work-session-activities").rowCount()==2,"Overlapping rows not displayed");
            var screenshots=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"timekeeper-work-session-ui");
            java.nio.file.Files.createDirectories(screenshots);
            var capture=new FutureTask<Void>(()->TestUtility.takeScreenshot(screenshots.toFile(),bot.tableWithId("work-session-activities").widget.getParent().getParent(),"work-session-eclipse.png"),null);
            PlatformUI.getWorkbench().getDisplay().syncExec(capture);capture.get(5,TimeUnit.SECONDS);
            assertEquals(independent,activities.activity(independent.id()).orElseThrow());
            assertTrue(sessions.workSessionReport(session.id()).time().outsideSession().contains(independent.id()));
            // Another writer must not overwrite a dialog's immutable captured version.
            bot.button("Edit session").click();bot.shell("Edit work session").activate();
            bot.textWithLabel("Summary").setText("Unsaved draft");
            var original=sessions.workSession(session.id()).orElseThrow();
            var peer=sessions.updateWorkSession(new UpdateWorkSession(original.id(),original.version(),original.start(),original.end(),"Peer edit"));
            bot.button("Save").click();
            assertTrue(bot.shell("Edit work session").isOpen());assertEquals("Unsaved draft",bot.textWithLabel("Summary").getText());
            assertEquals(peer,sessions.workSession(session.id()).orElseThrow());bot.button("Cancel").click();
            waitFor(()->bot.button("Edit session").isEnabled() && bot.comboBoxWithId("work-session-history").selection().contains("Peer edit"),"Reviewed session snapshot did not refresh");
            bot.button("Edit session").click();bot.shell("Edit work session").activate();
            assertEquals(net.resheim.eclipse.timekeeper.ui.views.LocalTimeInput.format(from,TimekeeperUiPlugin.getCalendarZone()),bot.textWithLabel("Start (local time)").getText());
            assertEquals(net.resheim.eclipse.timekeeper.ui.views.LocalTimeInput.format(until,TimekeeperUiPlugin.getCalendarZone()),bot.textWithLabel("End (local time, blank while running)").getText());
            bot.textWithLabel("Summary").setText("Reviewed edit");bot.button("Save").click();
            waitFor(()->sessions.workSession(session.id()).orElseThrow().summary().equals("Reviewed edit"),"Reviewed edit did not save");
            assertEquals(from,sessions.workSession(session.id()).orElseThrow().start());
        } finally {
            for(var value:sessions.workSessionReport(session.id()).activities()) {
                var current=activities.activity(value.id()).orElseThrow();activities.deleteActivity(new DeleteActivity(current.id(),current.version()));
            }
            var currentSession=sessions.workSession(session.id()).orElseThrow();sessions.deleteWorkSession(new DeleteWorkSession(currentSession.id(),currentSession.version()));
            if(bot.toggleButton("Session details").isPressed()) bot.toggleButton("Session details").click();
        }
    }
    private void open() throws Exception {
        waitFor(()->TimekeeperPlugin.getDefault().isReady(),"Embedded database unavailable");
        var task=new FutureTask<Void>(()->{try {var window=PlatformUI.getWorkbench().getActiveWorkbenchWindow();window.getShell().setSize(1300,850);
            window.getShell().forceActive();window.getActivePage().showView(WorkWeekView.VIEW_ID);}catch(Exception error) {throw new AssertionError(error);}},null);
        PlatformUI.getWorkbench().getDisplay().syncExec(task);task.get(5,TimeUnit.SECONDS);bot.viewByTitle("Workweek").setFocus();
    }
    private void waitFor(BooleanSupplier predicate,String message) {
        bot.waitUntil(new DefaultCondition() {public boolean test() {try {return predicate.getAsBoolean();}catch(RuntimeException error) {return false;}}
            public String getFailureMessage() {return message;}},30000);
    }
}
