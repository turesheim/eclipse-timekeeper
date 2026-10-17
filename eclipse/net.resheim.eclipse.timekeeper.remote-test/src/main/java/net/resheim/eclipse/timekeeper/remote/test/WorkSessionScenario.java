package net.resheim.eclipse.timekeeper.remote.test;

import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Installed native controls, real server persistence and committed-but-lost replies. */
final class WorkSessionScenario {
    static void prepare(HttpTimekeeperService api) throws Exception {
        var bot=new SWTWorkbenchBot();
        RemoteTestSupport.waitFor(()->bot.viewByTitle("Workweek").bot().button("Start work session").isEnabled(),"Session start unavailable");
        bot.viewByTitle("Workweek").bot().button("Start work session").click();
        RemoteTestSupport.waitFor(()->api.activeWorkSession(OwnerId.LOCAL).isPresent(),"Session start did not commit");
        var running=api.activeWorkSession(OwnerId.LOCAL).orElseThrow();
        var activity=api.startActivity(new StartActivity(null,OwnerId.LOCAL,"Independent installed timer",Set.of(),Optional.of(running.id()),Optional.empty()));
        RemoteTestSupport.waitFor(()->bot.viewByTitle("Workweek").bot().button("Stop work session").isEnabled(),"Session stop unavailable");
        bot.viewByTitle("Workweek").bot().button("Stop work session").click();
        RemoteTestSupport.waitFor(()->api.workSession(running.id()).orElseThrow().end().isPresent(),"Session stop did not commit");
        assertEquals(activity.id(),api.activeActivity(OwnerId.LOCAL).orElseThrow().id());
        var stopped=api.stopActivity(new StopActivity(activity.id(),activity.version()));
        api.deleteActivity(new DeleteActivity(stopped.id(),stopped.version()));
        var completed=api.workSession(running.id()).orElseThrow();api.deleteWorkSession(new DeleteWorkSession(completed.id(),completed.version()));

        if(!bot.toggleButton("Session details").isPressed()) bot.toggleButton("Session details").click();
        var from=Instant.parse("2007-10-25T08:00:00.123456789Z");
        var until=from.plus(Duration.ofHours(10));var tasks=api.tasks();
        bot.button("Record session").click();var editor=bot.shell("Record work session").bot();
        editor.textWithLabel("Summary").setText("Installed work session");editor.textWithLabel("Start (local time)").setText(net.resheim.eclipse.timekeeper.ui.views.LocalTimeInput.format(from,net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin.getCalendarZone()));
        editor.textWithLabel("End (local time, blank while running)").setText(net.resheim.eclipse.timekeeper.ui.views.LocalTimeInput.format(until,net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin.getCalendarZone()));editor.button("Save").click();
        RemoteTestSupport.waitFor(()->api.workSessions(OwnerId.LOCAL).stream().anyMatch(value->value.summary().equals("Installed work session")),"Retrospective session not stored");
        var session=api.workSessions(OwnerId.LOCAL).stream().filter(value->value.summary().equals("Installed work session")).findFirst().orElseThrow();
        RemoteTestSupport.waitFor(()->bot.comboBoxWithId("work-session-history").selection().contains("Installed work session"),"Manual session not selected");
        var interval=api.createActivity(new CreateActivity(null,OwnerId.LOCAL,from,Optional.of(until),"Installed session activity",Set.of()));
        RemoteTestSupport.waitFor(()->api.workSessionReport(session.id()).activities().size()==1,"Independent interval missing from temporal context");
        var contextActivity=api.workSessionReport(session.id()).activities().getFirst();
        assertTrue(contextActivity.workSessionId().isEmpty());assertTrue(contextActivity.task().isEmpty());assertTrue(contextActivity.projectId().isEmpty());
        assertEquals(interval,contextActivity);assertEquals(from,contextActivity.start());assertEquals(Optional.of(until),contextActivity.end());
        assertEquals(tasks,api.tasks());assertEquals(Duration.ofHours(10),api.workSessionReport(session.id()).time().allocatedTime());

        var independent=api.createActivity(new CreateActivity(null,OwnerId.LOCAL,from.minusSeconds(3600),Optional.of(until),"Installed outside interval",Set.of()));
        RemoteTestSupport.waitFor(()->api.workSessionReport(session.id()).activities().size()==2,"Concurrent interval missing from temporal context");
        assertEquals(independent,api.activity(independent.id()).orElseThrow());
        assertTrue(api.workSessionReport(session.id()).time().outsideSession().contains(independent.id()));
        assertEquals(Duration.ofHours(20),api.workSessionReport(session.id()).time().trackedActivityTime());
        assertEquals(Duration.ofHours(10),api.workSessionReport(session.id()).time().allocatedTime());
        RemoteTestSupport.waitFor(()->bot.tableWithId("work-session-activities").rowCount()==2,"Overlapping native rows not loaded");
        api.deleteActivity(new DeleteActivity(independent.id(),independent.version()));

        bot.button("Edit session").click();var shell=bot.shell("Edit work session");editor=shell.bot();
        editor.textWithLabel("Summary").setText("Unreviewed session draft");
        var current=api.workSession(session.id()).orElseThrow();
        var peer=api.updateWorkSession(new UpdateWorkSession(current.id(),current.version(),current.start(),current.end(),"Peer session edit"));
        editor.button("Save").click();
        RemoteTestSupport.waitFor(()->shell.bot().button("Save").isEnabled(),"Stale session draft did not recover");
        assertTrue(shell.isOpen());assertEquals("Unreviewed session draft",editor.textWithLabel("Summary").getText());
        assertEquals(peer,api.workSession(session.id()).orElseThrow());editor.button("Cancel").click();
        RemoteTestSupport.waitFor(()->bot.comboBoxWithId("work-session-history").selection().contains("Peer session edit"),"Peer session edit did not reach native history");
        RemoteBootstrap.proof.setProperty("session.id",session.id().value().toString());
        RemoteBootstrap.proof.setProperty("session.activity.id",contextActivity.id().value().toString());
        RemoteBootstrap.proof.setProperty("session.start",from.toString());RemoteBootstrap.proof.setProperty("session.end",until.toString());
        RemoteBootstrap.proof.setProperty("phase.work-session.native","passed");RemoteBootstrap.writeProof();
        bot.toggleButton("Session details").click();
    }

    /** The existing tracking scenario has already started the isolated reply gate. */
    static void uncertainCommands(HttpTimekeeperService api) throws Exception {
        RemoteTestSupport.showWorkweek();
        var bot=new SWTWorkbenchBot();bot.viewByTitle("Workweek").setFocus();assertTrue(api.activeWorkSession(OwnerId.LOCAL).isEmpty());
        var before=api.workSessions(OwnerId.LOCAL).size();
        RemoteBootstrap.holdNextSessionReply(null);
        WorkSession started;
        try {
            awaitStart(bot);
            bot.viewByTitle("Workweek").bot().button("Start work session").click();RemoteBootstrap.awaitTrackingReplyCommitted(true);
            started=api.activeWorkSession(OwnerId.LOCAL).orElseThrow();
            RemoteTestSupport.waitFor(()->bot.viewByTitle("Workweek").bot().button("Retry session command").isEnabled(),"Lost start did not retain its retry command");
        } finally {RemoteBootstrap.releaseTaskReply();}
        bot.viewByTitle("Workweek").bot().button("Retry session command").click();
        RemoteTestSupport.waitFor(()->bot.viewByTitle("Workweek").bot().button("Stop work session").isEnabled(),"Repeated start was not reconciled");
        assertEquals(started,api.activeWorkSession(OwnerId.LOCAL).orElseThrow());assertEquals(before+1,api.workSessions(OwnerId.LOCAL).size());
        RemoteBootstrap.holdNextSessionReply(started.id().value());
        WorkSession stopped;
        try {
            bot.viewByTitle("Workweek").bot().button("Stop work session").click();RemoteBootstrap.awaitTrackingReplyCommitted(false);
            stopped=api.workSession(started.id()).orElseThrow();assertEquals(started.version()+1,stopped.version());
            RemoteTestSupport.waitFor(()->bot.viewByTitle("Workweek").bot().button("Retry session command").isEnabled(),"Lost stop did not retain its retry command");
        } finally {RemoteBootstrap.releaseTaskReply();}
        bot.viewByTitle("Workweek").bot().button("Retry session command").click();
        RemoteTestSupport.waitFor(()->bot.viewByTitle("Workweek").bot().button("Start work session").isEnabled(),"Repeated stop was not reconciled");
        assertEquals(stopped,api.workSession(started.id()).orElseThrow());assertTrue(api.activeWorkSession(OwnerId.LOCAL).isEmpty());
        assertEquals(before+1,api.workSessions(OwnerId.LOCAL).size());
        RemoteBootstrap.proof.setProperty("phase.work-session.uncertain-start","passed");
        RemoteBootstrap.proof.setProperty("phase.work-session.uncertain-stop","passed");RemoteBootstrap.writeProof();
        RemoteTestSupport.closedLocalStorage();
    }

    private static void awaitStart(SWTWorkbenchBot bot) throws Exception {
        try {RemoteTestSupport.waitFor(()->bot.viewByTitle("Workweek").bot().buttonWithId("work-session-start").isEnabled(),"Unknown start unavailable");}
        catch(RuntimeException failure) {
            var controls=bot.viewByTitle("Workweek").bot();
            var button=controls.buttonWithId("work-session-start").widget;
            var status=controls.labelWithId("work-session-status").widget;
            var history=controls.comboBoxWithId("work-session-history").widget;
            String state=RemoteTestSupport.onUi(()->{
                var ui=net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin.getDefault();
                return "ready="+ui.isBackendReady()+", backend="+ui.backendStatus()+", enabled="+button.getEnabled()
                        +", inherited="+button.isEnabled()+", visible="+button.isVisible()+", caption="+button.getText()
                        +", status="+status.getText()+", history="+history.getText();
            });
            throw new AssertionError("Unknown start unavailable: "+state,failure);
        }
    }

    static void restart(HttpTimekeeperService api) throws Exception {
        assertEquals("passed",RemoteBootstrap.proof.getProperty("phase.work-session.native"));
        var id=new WorkSessionId(UUID.fromString(RemoteBootstrap.proof.getProperty("session.id")));
        var session=api.workSession(id).orElseThrow();assertEquals("Peer session edit",session.summary());
        assertEquals(Instant.parse(RemoteBootstrap.proof.getProperty("session.start")),session.start());
        assertEquals(Optional.of(Instant.parse(RemoteBootstrap.proof.getProperty("session.end"))),session.end());
        var activity=api.activity(new ActivityId(UUID.fromString(RemoteBootstrap.proof.getProperty("session.activity.id")))).orElseThrow();
        assertTrue(activity.workSessionId().isEmpty());assertTrue(activity.task().isEmpty());
        assertEquals(session.start(),activity.start());assertEquals(session.end(),activity.end());
        assertEquals(Duration.ofHours(10),api.workSessionReport(id).time().allocatedTime());
        for(var phase:List.of("uncertain-start","uncertain-stop")) assertEquals("passed",RemoteBootstrap.proof.getProperty("phase.work-session."+phase));
        RemoteBootstrap.proof.setProperty("phase.work-session.restart","passed");RemoteBootstrap.writeProof();
    }
}
