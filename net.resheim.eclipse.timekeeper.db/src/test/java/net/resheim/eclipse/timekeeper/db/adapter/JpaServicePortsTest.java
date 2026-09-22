package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

import javax.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.resheim.eclipse.timekeeper.db.adapter.JpaServicePorts;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateActivity;
import net.resheim.eclipse.timekeeper.service.Commands.CreateLabel;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateProject;
import net.resheim.eclipse.timekeeper.service.DefaultTimekeeperService;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.service.ServiceException;
import net.resheim.eclipse.timekeeper.service.TimekeeperEvent;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

class JpaServicePortsTest {
	@TempDir
	Path directory;
	private EntityManager manager;

	@AfterEach
	void close() {
		DatabaseStartup.close(manager);
		manager = null;
	}

	@Test
	void persistsTheServiceModelAtomicallyAndKeepsIdentityAcrossRestart() throws Exception {
		manager = DatabaseStartup.open(url("service"));
		var events = new ArrayList<TimekeeperEvent>();
		TimekeeperService service = service(events);

		var project = service.createProject(new CreateProject("Embedded"));
		project = service.updateProject(new UpdateProject(project.id(), project.version(), "Renamed"));
		var task = service.createTask(new CreateTask(Optional.of(project.id()), "Adapter task", Optional.empty()));
		var label = service.createLabel(new CreateLabel("Billable", Optional.of("0,128,0")));
		Instant start = Instant.parse("2026-09-22T08:00:00Z");
		var activity = service.createActivity(new CreateActivity(task.id(), OwnerId.LOCAL, start,
				Optional.of(start.plus(Duration.ofMinutes(45))), "Implemented adapter", Set.of(label.id())));

		var report = service.activities(new ActivityQuery(start.minusSeconds(1), start.plusSeconds(3600),
				Optional.of(OwnerId.LOCAL), Optional.of(task.id()), Optional.of(project.id()), ZoneOffset.UTC));
		assertEquals(Duration.ofMinutes(45), report.total());
		assertEquals(activity.id(), report.activities().getFirst().id());
		assertEquals(5, events.size());

		var projectId = project.id();
		var taskId = task.id();
		DatabaseStartup.close(manager);
		manager = DatabaseStartup.open(url("service") + ";IFEXISTS=TRUE");
		service = service(new ArrayList<>());
		assertEquals("Renamed", service.project(projectId).orElseThrow().name());
		assertEquals(projectId, service.task(taskId).orElseThrow().projectId().orElseThrow());
		assertEquals(activity.id(), service.activity(activity.id()).orElseThrow().id());
	}

	@Test
	void rollsBackRepositoryChangesWhenEventPublicationFails() throws Exception {
		manager = DatabaseStartup.open(url("rollback"));
		JpaServicePorts ports = new JpaServicePorts(() -> manager);
		TimekeeperService service = new DefaultTimekeeperService(ports.ports(event -> {
			throw new IllegalStateException("listener failed");
		}));

		assertThrows(IllegalStateException.class, () -> service.createProject(new CreateProject("Rolled back")));
		manager.clear();
		assertEquals(0L, manager.createQuery("SELECT COUNT(p) FROM Project p", Long.class).getSingleResult());
		assertFalse(manager.getTransaction().isActive());
	}

	@Test
	void reportsAStableConflictUntilTheDatabaseIsReady() {
		JpaServicePorts ports = new JpaServicePorts(() -> null);
		TimekeeperService service = new DefaultTimekeeperService(ports.ports(event -> { }));

		ServiceException failure = assertThrows(ServiceException.class, service::projects);
		assertTrue(failure.getMessage().contains("database is not ready"));
	}

	private TimekeeperService service(ArrayList<TimekeeperEvent> events) {
		JpaServicePorts ports = new JpaServicePorts(() -> manager);
		return new DefaultTimekeeperService(ports.ports(events::add));
	}

	private String url(String name) {
		return "jdbc:h2:" + directory.resolve(name).toAbsolutePath();
	}
}
