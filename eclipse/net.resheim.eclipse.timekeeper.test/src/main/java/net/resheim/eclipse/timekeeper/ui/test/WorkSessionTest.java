package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter MINUTES=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final SWTWorkbenchBot bot=new SWTWorkbenchBot();

    @Test public void sessionTimersAreIndependentAndPermitMultipleSessionsPerDay() throws Exception {
        open();
        var activities=TimekeeperUiPlugin.getDefault().getTimekeeperService();
        var sessions=TimekeeperUiPlugin.getDefault().getWorkSessionService();
        var tasks=activities.tasks();
        waitFor(()->bot.button("Start work session").isEnabled(),"Start button unavailable");
        bot.button("Start work session").click();
        waitFor(()->sessions.activeWorkSession(OwnerId.LOCAL).isPresent(),"Work session was not started");
        var first=sessions.activeWorkSession(OwnerId.LOCAL).orElseThrow();
        var activity=activities.startActivity(new StartActivity(null,OwnerId.LOCAL,"Independent timer",Set.of(),Optional.of(first.id()),Optional.empty()));
        try {
            waitFor(()->bot.labelWithId("work-session-status").getText().startsWith("Running") && bot.button("Stop work session").isEnabled(),"Running status missing");
            bot.button("Stop work session").click();
            waitFor(()->sessions.workSession(first.id()).orElseThrow().end().isPresent(),"Session did not stop");
            assertEquals(activity.id(),activities.activeActivity(OwnerId.LOCAL).orElseThrow().id());
            assertTrue(activities.activity(activity.id()).orElseThrow().end().isEmpty());
            waitFor(()->bot.button("Start work session").isEnabled(),"Second session unavailable");
            bot.button("Start work session").click();
            waitFor(()->sessions.activeWorkSession(OwnerId.LOCAL).isPresent(),"Second session did not start");
            var second=sessions.activeWorkSession(OwnerId.LOCAL).orElseThrow();
            assertNotEquals(first.id(),second.id());
            waitFor(()->bot.button("Stop work session").isEnabled(),"Second stop unavailable");
            bot.button("Stop work session").click();
            waitFor(()->sessions.activeWorkSession(OwnerId.LOCAL).isEmpty(),"Second session did not stop");
            assertEquals(tasks,activities.tasks());
        } finally {
            for(var session:sessions.workSessions(OwnerId.LOCAL)) {
                for(var value:sessions.workSessionReport(session.id()).activities()) {
                    var current=activities.activity(value.id()).orElseThrow();
                    if(current.end().isEmpty()) current=activities.stopActivity(new StopActivity(current.id(),current.version()));
                    activities.deleteActivity(new DeleteActivity(current.id(),current.version()));
                }
                var current=sessions.workSession(session.id()).orElseThrow();sessions.deleteWorkSession(new DeleteWorkSession(current.id(),current.version()));
            }
        }
    }

    @Test public void retrospectiveSessionSupportsTasklessOverlapAssociationAndStaleEdits() throws Exception {
        open();
        var activities=TimekeeperUiPlugin.getDefault().getTimekeeperService();
        var sessions=TimekeeperUiPlugin.getDefault().getWorkSessionService();
        var tasks=activities.tasks();var zone=TimekeeperUiPlugin.getCalendarZone();
        var from=LocalDate.now(zone).minusDays(2).atTime(8,0).atZone(zone).toInstant().plusNanos(123);
        var until=from.plus(Duration.ofHours(10));
        if(!bot.toggleButton("Session details").isPressed()) bot.toggleButton("Session details").click();
        waitFor(()->bot.button("Record session").isEnabled(),"Record button unavailable");
        bot.button("Record session").click();bot.shell("Record work session").activate();
        bot.textWithLabel("Summary").setText("Retrospective session");bot.textWithLabel("Start (UTC)").setText(from.toString());
        bot.textWithLabel("End (UTC, blank while running)").setText(until.toString());bot.button("Save").click();
        waitFor(()->sessions.workSessions(OwnerId.LOCAL).stream().anyMatch(value->value.summary().equals("Retrospective session")),"Manual session was not saved");
        var session=sessions.workSessions(OwnerId.LOCAL).stream().filter(value->value.summary().equals("Retrospective session")).findFirst().orElseThrow();
        var independent=activities.createActivity(new CreateActivity(null,OwnerId.LOCAL,from.minusSeconds(3600),Optional.of(until.plusSeconds(3600)),"Outside association",Set.of()));
        try {
            waitFor(()->bot.button("Add activity").isEnabled() && bot.comboBoxWithId("work-session-history").selection().contains("Retrospective session"),"Recorded session was not selected");
            bot.button("Add activity").click();bot.shell("New activity").activate();
            bot.textWithLabel("Summary").setText("Exact session interval");bot.button("Save").click();
            assertTrue("Taskless editor should close after acknowledged save",Arrays.stream(bot.shells()).noneMatch(shell->shell.getText().equals("New activity")));
            waitFor(()->sessions.workSessionReport(session.id()).activities().size()==1,"Taskless activity missing");
            var first=sessions.workSessionReport(session.id()).activities().getFirst();
            assertTrue(first.task().isEmpty());assertTrue(first.projectId().isEmpty());assertEquals(from,first.start());assertEquals(Optional.of(until),first.end());
            bot.button("Add activity").click();bot.shell("New activity").activate();
            bot.textWithLabel("Summary").setText("Overlapping interval");
            bot.textWithLabel("Start (yyyy-MM-dd HH:mm)").setText(MINUTES.format(from.plusSeconds(3600).atZone(zone)));
            bot.textWithLabel("End (yyyy-MM-dd HH:mm)").setText(MINUTES.format(from.plusSeconds(14400).atZone(zone)));bot.button("Save").click();
            waitFor(()->sessions.workSessionReport(session.id()).activities().size()==2,"Second activity missing");
            var metric=sessions.workSessionReport(session.id()).time();
            assertEquals(Duration.ofHours(13),metric.trackedActivityTime());assertEquals(Duration.ofHours(10),metric.allocatedTime());assertEquals(Duration.ZERO,metric.unallocatedTime());
            assertEquals(tasks,activities.tasks());
            waitFor(()->bot.tableWithId("work-session-activities").rowCount()==2,"Associated rows not displayed");
            var screenshots=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"timekeeper-work-session-ui");
            java.nio.file.Files.createDirectories(screenshots);
            var capture=new FutureTask<Void>(()->TestUtility.takeScreenshot(screenshots.toFile(),bot.tableWithId("work-session-activities").widget.getParent().getParent(),"work-session-eclipse.png"),null);
            PlatformUI.getWorkbench().getDisplay().syncExec(capture);capture.get(5,TimeUnit.SECONDS);
            // The association editor changes only the link, and warns without rejecting the interval.
            bot.button("Associate activity").click();bot.shell("Associate activity").activate();
            var choices=bot.comboBoxWithId("session-association-choice");
            choices.setSelection(Arrays.stream(choices.items()).filter(value->value.startsWith("Outside association")).findFirst().orElseThrow());
            bot.button("Associate").click();
            waitFor(()->activities.activity(independent.id()).orElseThrow().workSessionId().equals(Optional.of(session.id())),"Association did not save");
            var associated=activities.activity(independent.id()).orElseThrow();
            assertEquals(independent.start(),associated.start());assertEquals(independent.end(),associated.end());
            assertTrue(sessions.workSessionReport(session.id()).time().outsideSession().contains(independent.id()));
            waitFor(()->bot.tableWithId("work-session-activities").rowCount()==3,"Associated list missing");
            bot.tableWithId("work-session-activities").select("Outside association");bot.button("Remove association").click();
            waitFor(()->activities.activity(independent.id()).orElseThrow().workSessionId().isEmpty(),"Association not removed");
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
            assertEquals(from.toString(),bot.textWithLabel("Start (UTC)").getText());
            assertEquals(until.toString(),bot.textWithLabel("End (UTC, blank while running)").getText());
            bot.textWithLabel("Summary").setText("Reviewed edit");bot.button("Save").click();
            waitFor(()->sessions.workSession(session.id()).orElseThrow().summary().equals("Reviewed edit"),"Reviewed edit did not save");
            assertEquals(from,sessions.workSession(session.id()).orElseThrow().start());
        } finally {
            for(var value:sessions.workSessionReport(session.id()).activities()) {
                var current=activities.activity(value.id()).orElseThrow();activities.deleteActivity(new DeleteActivity(current.id(),current.version()));
            }
            var current=activities.activity(independent.id()).orElseThrow();activities.deleteActivity(new DeleteActivity(current.id(),current.version()));
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
