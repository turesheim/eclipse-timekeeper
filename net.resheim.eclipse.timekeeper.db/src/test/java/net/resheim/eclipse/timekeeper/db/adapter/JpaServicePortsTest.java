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
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.service.Commands.CreateActivity;
import net.resheim.eclipse.timekeeper.service.Commands.CreateLabel;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteProject;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteTask;
import net.resheim.eclipse.timekeeper.service.Commands.LinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.UnlinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
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
		var subtask = service.createTask(new CreateTask(Optional.of(project.id()), Optional.of(task.id()),
				"Adapter subtask", Optional.empty()));
		var label = service.createLabel(new CreateLabel("Billable", Optional.of("0,128,0")));
		Instant start = Instant.parse("2026-09-22T08:00:00Z");
		var activity = service.createActivity(new CreateActivity(task.id(), OwnerId.LOCAL, start,
				Optional.of(start.plus(Duration.ofMinutes(45))), "Implemented adapter", Set.of(label.id())));

		var report = service.activities(new ActivityQuery(start.minusSeconds(1), start.plusSeconds(3600),
				Optional.of(OwnerId.LOCAL), Optional.of(task.id()), Optional.of(project.id()), ZoneOffset.UTC));
		assertEquals(Duration.ofMinutes(45), report.total());
		assertEquals(activity.id(), report.activities().getFirst().id());
		assertEquals(6, events.size());

		var projectId = project.id();
		var taskId = task.id();
		var subtaskId = subtask.id();
		DatabaseStartup.close(manager);
		manager = DatabaseStartup.open(url("service") + ";IFEXISTS=TRUE");
		service = service(new ArrayList<>());
		assertEquals("Renamed", service.project(projectId).orElseThrow().name());
		assertEquals(projectId, service.task(taskId).orElseThrow().projectId().orElseThrow());
		assertEquals(taskId, service.task(subtaskId).orElseThrow().parentTaskId().orElseThrow());
		assertEquals(activity.id(), service.activity(activity.id()).orElseThrow().id());
	}

	@Test
	void projectCanBeDeletedAfterItsTaskHierarchy() throws Exception {
		manager = DatabaseStartup.open(url("delete-hierarchy"));
		TimekeeperService service = service(new ArrayList<>());
		var project = service.createProject(new CreateProject("Disposable"));
		var parent = service.createTask(new CreateTask(Optional.of(project.id()), "Parent", Optional.empty()));
		var child = service.createTask(new CreateTask(Optional.of(project.id()), Optional.of(parent.id()),
				"Child", Optional.empty()));

		service.deleteTask(new DeleteTask(child.id(), child.version()));
		service.deleteTask(new DeleteTask(parent.id(), parent.version()));
		service.deleteProject(new DeleteProject(project.id(), project.version()));
		assertTrue(service.projects().isEmpty());
		assertTrue(service.tasks().isEmpty());
	}

	@Test
	void standaloneTaskSurvivesRestartAndExternalLinkChangesKeepItsIdentityAndActivities() throws Exception {
		manager = DatabaseStartup.open(url("standalone"));
		TimekeeperService service = service(new ArrayList<>());
		var task = service.createTask(new CreateTask(Optional.empty(), "Local task",
				Optional.of("https://example.test/local")));
		Instant start = Instant.parse("2026-09-22T10:00:00Z");
		var activity = service.createActivity(new CreateActivity(task.id(), OwnerId.LOCAL, start,
				Optional.of(start.plus(Duration.ofMinutes(20))), "Standalone work", Set.of()));
		var taskId = task.id();

		DatabaseStartup.close(manager);
		manager = DatabaseStartup.open(url("standalone") + ";IFEXISTS=TRUE");
		service = service(new ArrayList<>());
		task = service.task(taskId).orElseThrow();
		assertTrue(task.projectId().isEmpty());
		assertEquals("https://example.test/local", task.url().orElseThrow());
		task = service.updateTask(new UpdateTask(task.id(), task.version(), Optional.empty(),
				"Renamed local task", Optional.of("https://example.test/renamed")));
		ExternalTaskReference link = new ExternalTaskReference("github", "example/timekeeper", "183",
				"https://github.com/example/timekeeper/issues/183");
		task = service.linkExternalReference(new LinkExternalReference(task.id(), task.version(), link));
		assertEquals(taskId, service.findTask(link.key()).orElseThrow().id());
		task = service.unlinkExternalReference(new UnlinkExternalReference(task.id(), task.version(), link.key()));

		assertEquals(taskId, task.id());
		assertTrue(task.externalReferences().isEmpty());
		assertEquals(activity.id(), service.activity(activity.id()).orElseThrow().id());
		assertEquals(Duration.ofMinutes(20), service.activities(new ActivityQuery(start.minusSeconds(1),
				start.plusSeconds(3600), Optional.of(OwnerId.LOCAL), Optional.of(taskId), Optional.empty(),
				ZoneOffset.UTC)).total());
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
