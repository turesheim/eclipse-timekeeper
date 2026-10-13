package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.resheim.eclipse.timekeeper.db.adapter.JpaServicePorts;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

class NativeWorkSessionTransferTest {
    @TempDir Path directory;
    @Test void nativeCsvRestoresDailySessionRelationsAndDoesNotReuseOldVersions() throws Exception {
        var previous=TimekeeperPlugin.getDefault().getEntityManager();
        var source=DatabaseStartup.open("jdbc:h2:mem:native_session_source_"+UUID.randomUUID());
        var target=DatabaseStartup.open("jdbc:h2:mem:native_session_target_"+UUID.randomUUID());
        try {
            var sourcePorts=new JpaServicePorts(()->source);var sessions=new DefaultWorkSessionService(sourcePorts.workSessionPorts(event->{}));
            var activities=new DefaultTimekeeperService(sourcePorts.ports(event->{}),sessions);
            Instant start=Instant.parse("2025-10-25T21:59:59.123456789Z"),end=Instant.parse("2025-10-27T22:00:00.987654321Z");
            var session=sessions.createWorkSession(new CreateWorkSession(OwnerId.LOCAL,start,Optional.of(end),"Native session, æøå"));
            var label=activities.createLabel(new CreateLabel("Native label",Optional.of("12,34,56")));
            activities.createActivity(new CreateActivity(null,OwnerId.LOCAL,start,Optional.of(end),"Daily native activity",Set.of(label.id()),Optional.of(session.id()),Optional.empty()));
            var task=activities.createTask(new CreateTask(Optional.empty(),"Active CSV task",Optional.empty()));
            var active=activities.startActivity(new StartActivity(task.id(),OwnerId.LOCAL,"Ongoing CSV activity",Set.of(),Optional.of(session.id()),Optional.empty()));
            var expected=sessions.workSessionReport(session.id());TimekeeperPlugin.setEntityManager(source);
            var folder=directory.resolve("export");TimekeeperPlugin.getDefault().exportTo(folder);assertTrue(Files.isRegularFile(folder.resolve("work_session.csv")));
            TimekeeperPlugin.setEntityManager(target);TimekeeperPlugin.getDefault().importFrom(folder);
            var targetPorts=new JpaServicePorts(()->target);var copied=new DefaultWorkSessionService(targetPorts.workSessionPorts(event->{}));
            assertEquals(expected,copied.workSessionReport(session.id()));
            var targetActivities=new DefaultTimekeeperService(targetPorts.ports(event->{}),copied);assertEquals(active,targetActivities.activeActivity(OwnerId.LOCAL).orElseThrow());
            TimekeeperPlugin.getDefault().importFrom(folder);assertEquals(expected,copied.workSessionReport(session.id()));
            var changed=copied.updateWorkSession(new UpdateWorkSession(session.id(),session.version(),start,Optional.of(end),"Changed after export"));
            TimekeeperPlugin.getDefault().importFrom(folder);var restored=copied.workSession(session.id()).orElseThrow();
            assertEquals(session.summary(),restored.summary());assertTrue(restored.version()>changed.version());
            assertThrows(ServiceException.class,()->copied.updateWorkSession(new UpdateWorkSession(session.id(),session.version(),start,Optional.of(end),"Stale editor")));
            TimekeeperPlugin.getDefault().importFrom(folder);assertEquals(restored,copied.workSession(session.id()).orElseThrow());
            var before=copied.workSessionReport(session.id());Files.delete(folder.resolve("work_session.csv"));
            assertThrows(java.io.IOException.class,()->TimekeeperPlugin.getDefault().importFrom(folder));assertEquals(before,copied.workSessionReport(session.id()));
        } finally {TimekeeperPlugin.setEntityManager(previous);DatabaseStartup.close(source);DatabaseStartup.close(target);}
    }
}
