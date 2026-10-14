package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.db.adapter.JpaServicePorts;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;

class JpaDailyActivitiesTest {
    private EntityManager manager;
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2025-10-28T12:00:00Z"));
    private final List<TimekeeperEvent> events=new ArrayList<>();
    private JpaServicePorts repository;
    private TimekeeperService service() throws Exception {
        manager=DatabaseStartup.open("jdbc:h2:mem:daily_"+UUID.randomUUID());
        repository=new JpaServicePorts(()->manager);
        var original=repository.ports(event->repository.afterCommit(()->events.add(event)));
        var clock=new Ports.TimeSource() {
            public Instant now() {return now.get();}
            public ZoneId zone(OwnerId owner) {return ZoneId.of("Europe/Oslo");}
        };
        return new DefaultTimekeeperService(new Ports.ServicePorts(original.projects(),original.tasks(),original.activities(),
            original.labels(),original.identifiers(),clock,original.transactions(),original.events()));
    }
    @AfterEach void close() {DatabaseStartup.close(manager);}
    private ActivityQuery query(Instant start,Instant end) {
        return new ActivityQuery(start,end,Optional.of(OwnerId.LOCAL),Optional.empty(),Optional.empty(),ZoneId.of("Europe/Oslo"));
    }
    @Test void actualJpaRowsPreserveManualLabelsAndExactDstTotals() throws Exception {
        var api=service();var task=api.createTask(new CreateTask(Optional.empty(),"Daily JPA manual",Optional.empty()));
        var label=api.createLabel(new CreateLabel("Daily JPA label",Optional.of("12,34,56")));
        var start=Instant.parse("2025-10-25T21:59:59.123456789Z");var end=Instant.parse("2025-10-27T22:00:00.987654321Z");
        var retained=api.createActivity(new CreateActivity(task.id(),OwnerId.LOCAL,start,Optional.of(end),"Exact manual",Set.of(label.id())));
        var report=api.activities(query(start.minusSeconds(1),end.plusSeconds(1)));
        assertEquals(3,report.activities().size());assertEquals(retained,report.activities().getLast());
        assertEquals(Duration.between(start,end),report.total());assertEquals(label,api.label(label.id()).orElseThrow());
        for(var part:report.activities()) {assertTrue(part.manual());assertEquals(Set.of(label.id()),part.labelIds());assertEquals("Exact manual",part.summary());}
        assertEquals(3L,manager.createNativeQuery("SELECT COUNT(*) FROM ACTIVITY").getSingleResult());
    }
    @Test void tasklessLocalActivitiesAndDirectProjectsKeepDailyTimeWithoutCreatingTasks() throws Exception {
        var api=service();var project=api.createProject(new CreateProject("Direct local project"));
        var start=Instant.parse("2025-10-25T21:59:59.123456789Z");var end=Instant.parse("2025-10-27T22:00:00.987654321Z");
        api.createActivity(new CreateActivity(null,OwnerId.LOCAL,start,Optional.of(end),"Taskless local",Set.of(),Optional.empty(),Optional.of(project.id())));
        var report=api.activities(query(start.minusSeconds(1),end.plusSeconds(1)));assertEquals(3,report.activities().size());
        assertEquals(Duration.between(start,end),report.total());assertTrue(api.tasks().isEmpty());
        for(var part:report.activities()) {assertTrue(part.task().isEmpty());assertEquals(Optional.of(project.id()),part.projectId());}
        assertEquals(3L,manager.createNativeQuery("SELECT COUNT(*) FROM PROJECT_ACTIVITY").getSingleResult());
        assertThrows(ServiceException.class,()->api.deleteProject(new DeleteProject(project.id(),project.version())));
        for(var part:report.activities()) api.deleteActivity(new DeleteActivity(part.id(),part.version()));
        assertEquals(0L,manager.createNativeQuery("SELECT COUNT(*) FROM PROJECT_ACTIVITY").getSingleResult());
        api.deleteProject(new DeleteProject(project.id(),api.project(project.id()).orElseThrow().version()));
    }
    @Test void jpaMidnightRolloverKeepsActiveIdentityAndFailedArchivesRollbackEvents() throws Exception {
        var api=service();now.set(Instant.parse("2025-10-25T21:59:59.123456789Z"));
        var task=api.createTask(new CreateTask(Optional.empty(),"Daily JPA tracking",Optional.empty()));
        var original=api.startActivity(new StartActivity(task.id(),OwnerId.LOCAL,"Exact tracking",Set.of()));
        var collision=new Activity(ActivityDailySegments.identifier(original.id(),Instant.parse("2025-10-25T22:00:00Z")),
            task.id(),OwnerId.LOCAL,original.start().minusSeconds(60),Optional.of(original.start()),"Independent row",Set.of(),true,0);
        repository.required(()->repository.save(collision));
        var range=query(original.start().minusSeconds(100),original.start().plus(Duration.ofDays(3)));
        var before=api.activities(range).activities();int eventCount=events.size();
        now.set(original.start().plus(Duration.ofHours(26)));
        assertThrows(ServiceException.class,()->api.rolloverActivities(OwnerId.LOCAL));
        assertEquals(before,api.activities(range).activities());assertEquals(eventCount,events.size());
        api.deleteActivity(new DeleteActivity(collision.id(),api.activity(collision.id()).orElseThrow().version()));
        api.rolloverActivities(OwnerId.LOCAL);var active=api.activeActivity(OwnerId.LOCAL).orElseThrow();
        assertEquals(original.id(),active.id());assertFalse(active.manual());
        assertEquals(3,api.activities(range).activities().size());assertEquals(Duration.ofHours(26),api.activities(range).total());
        eventCount=events.size();api.rolloverActivities(OwnerId.LOCAL);
        assertEquals(active,api.activeActivity(OwnerId.LOCAL).orElseThrow());assertEquals(eventCount,events.size());
    }
}
