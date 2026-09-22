package net.resheim.eclipse.timekeeper.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.DomainValidationException;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.FailureCode;
import net.resheim.eclipse.timekeeper.domain.Label;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateActivity;
import net.resheim.eclipse.timekeeper.service.Commands.CreateLabel;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteActivity;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteLabel;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteProject;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteTask;
import net.resheim.eclipse.timekeeper.service.Commands.LinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.StartActivity;
import net.resheim.eclipse.timekeeper.service.Commands.StopActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UnlinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateLabel;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateProject;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.service.Ports.ActivityRepository;
import net.resheim.eclipse.timekeeper.service.Ports.IdentifierSource;
import net.resheim.eclipse.timekeeper.service.Ports.LabelRepository;
import net.resheim.eclipse.timekeeper.service.Ports.ProjectRepository;
import net.resheim.eclipse.timekeeper.service.Ports.ServicePorts;
import net.resheim.eclipse.timekeeper.service.Ports.TaskRepository;
import net.resheim.eclipse.timekeeper.service.Ports.TransactionRunner;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.service.TimekeeperEvent.ChangeType;
import net.resheim.eclipse.timekeeper.service.TimekeeperEvent.EntityType;

class DefaultTimekeeperServiceTest {
	private InMemoryStore store;
	private MutableTime time;
	private CountingTransactions transactions;
	private List<TimekeeperEvent> events;
	private TimekeeperService service;

	@BeforeEach
	void setUp() {
		store = new InMemoryStore();
		time = new MutableTime(Instant.parse("2026-09-22T08:00:00Z"));
		transactions = new CountingTransactions();
		events = new ArrayList<>();
		service = new DefaultTimekeeperService(new ServicePorts(store, store, store, store,
				new SequentialIdentifiers(), time::now, transactions, events::add));
	}

	@Test
	void projectTaskAndLabelCrudUsesOneTransactionPerOperation() {
		Project project = service.createProject(new CreateProject("Backend"));
		project = service.updateProject(new UpdateProject(project.id(), 0, "Backend API"));
		Task task = service.createTask(new CreateTask(Optional.of(project.id()), "Define API", Optional.empty()));
		task = service.updateTask(new UpdateTask(task.id(), 0, Optional.of(project.id()), "Implement API",
				Optional.of("https://example.test/task")));
		Label label = service.createLabel(new CreateLabel("coding", Optional.of("#336699")));
		label = service.updateLabel(new UpdateLabel(label.id(), 0, "implementation", Optional.empty()));

		assertEquals(project, service.project(project.id()).orElseThrow());
		assertEquals(task, service.task(task.id()).orElseThrow());
		assertEquals(label, service.label(label.id()).orElseThrow());
		assertEquals(List.of(project), service.projects());
		assertEquals(List.of(task), service.tasks());
		assertEquals(List.of(label), service.labels());

		service.deleteTask(new DeleteTask(task.id(), task.version()));
		service.deleteProject(new DeleteProject(project.id(), project.version()));
		service.deleteLabel(new DeleteLabel(label.id(), label.version()));

		assertEquals(15, transactions.count);
		assertEquals(9, events.size());
		assertEquals(new EventShape(EntityType.PROJECT, ChangeType.CREATED), shape(events.get(0)));
		assertEquals(new EventShape(EntityType.LABEL, ChangeType.DELETED), shape(events.get(events.size() - 1)));
	}

	@Test
	void standaloneAndExternallyLinkedTasksHaveIdenticalActivityRules() {
		Task standalone = service.createTask(new CreateTask(Optional.empty(), "Local task", Optional.empty()));
		Task linked = service.createTask(new CreateTask(Optional.empty(), "Jira task", Optional.empty()));
		ExternalTaskReference reference = new ExternalTaskReference("jira", "https://jira.example", "TK-42",
				Optional.empty());
		linked = service.linkExternalReference(new LinkExternalReference(linked.id(), 0, reference));
		TaskId linkedId = linked.id();

		Activity first = service.startActivity(new StartActivity(standalone.id(), OwnerId.LOCAL, "local work", Set.of()));
		ServiceException alreadyOpen = assertThrows(ServiceException.class,
				() -> service.startActivity(new StartActivity(linkedId, OwnerId.LOCAL, "linked work", Set.of())));
		assertEquals(FailureCode.CONFLICT, alreadyOpen.code());

		time.advance(Duration.ofMinutes(30));
		first = service.stopActivity(new StopActivity(first.id(), 0));
		Activity second = service.startActivity(new StartActivity(linkedId, OwnerId.LOCAL, "linked work", Set.of()));
		time.advance(Duration.ofMinutes(45));
		second = service.stopActivity(new StopActivity(second.id(), 0));

		var report = service.activities(query(Instant.parse("2026-09-22T00:00:00Z"),
				Instant.parse("2026-09-23T00:00:00Z")));
		assertEquals(Duration.ofMinutes(75), report.total());
		assertEquals(List.of(first, second), report.activities());
		assertEquals(linked.id(), service.findTask(reference.key()).orElseThrow().id());

		linked = service.unlinkExternalReference(new UnlinkExternalReference(linked.id(), linked.version(), reference.key()));
		assertTrue(linked.externalReferences().isEmpty());
		assertTrue(service.findTask(reference.key()).isEmpty());
	}

	@Test
	void manualActivityCanBeReportedEditedAndDeleted() {
		Project project = service.createProject(new CreateProject("Server"));
		Task task = service.createTask(new CreateTask(Optional.of(project.id()), "REST API", Optional.empty()));
		Label label = service.createLabel(new CreateLabel("review", Optional.empty()));
		Instant start = Instant.parse("2026-09-21T23:30:00Z");
		Activity activity = service.createActivity(new CreateActivity(task.id(), new OwnerId("alice"), start,
				Optional.of(start.plus(Duration.ofHours(2))), "review", Set.of(label.id())));

		activity = service.updateActivity(new UpdateActivity(activity.id(), 0, start.plus(Duration.ofMinutes(15)),
				Optional.of(start.plus(Duration.ofHours(2))), "API review", Set.of(label.id())));
		assertEquals(activity, service.activity(activity.id()).orElseThrow());
		ActivityQuery filtered = new ActivityQuery(Instant.parse("2026-09-21T00:00:00Z"),
				Instant.parse("2026-09-23T00:00:00Z"), Optional.of(new OwnerId("alice")), Optional.empty(),
				Optional.of(project.id()), ZoneId.of("Europe/Oslo"));
		assertEquals(Duration.ofMinutes(105), service.activities(filtered).total());
		assertTrue(activity.manual());

		ServiceException usedLabel = assertThrows(ServiceException.class,
				() -> service.deleteLabel(new DeleteLabel(label.id(), label.version())));
		assertEquals(FailureCode.CONFLICT, usedLabel.code());

		service.deleteActivity(new DeleteActivity(activity.id(), activity.version()));
		service.deleteLabel(new DeleteLabel(label.id(), label.version()));
		service.deleteTask(new DeleteTask(task.id(), task.version()));
		service.deleteProject(new DeleteProject(project.id(), project.version()));
		assertFalse(store.activities.containsKey(activity.id()));
	}

	@Test
	void validationMissingResourcesAndStaleUpdatesHaveStableCodes() {
		DomainValidationException validation = assertThrows(DomainValidationException.class,
				() -> service.createTask(new CreateTask(Optional.empty(), " ", Optional.empty())));
		assertEquals(FailureCode.VALIDATION, validation.code());
		assertEquals("summary", validation.field());

		Project project = service.createProject(new CreateProject("Core"));
		ServiceException stale = assertThrows(ServiceException.class,
				() -> service.updateProject(new UpdateProject(project.id(), 9, "Wrong")));
		assertEquals(FailureCode.CONFLICT, stale.code());

		ServiceException missing = assertThrows(ServiceException.class,
				() -> service.updateTask(new UpdateTask(new TaskId(UUID.randomUUID()), 0, Optional.empty(),
						"missing", Optional.empty())));
		assertEquals(FailureCode.NOT_FOUND, missing.code());
		assertEquals("taskId", missing.field());
	}

	private ActivityQuery query(Instant from, Instant to) {
		return new ActivityQuery(from, to, Optional.empty(), Optional.empty(), Optional.empty(), ZoneId.of("UTC"));
	}

	private EventShape shape(TimekeeperEvent event) {
		return new EventShape(event.entityType(), event.changeType());
	}

	private record EventShape(EntityType entity, ChangeType change) { }

	private static final class MutableTime {
		private Instant now;

		MutableTime(Instant now) {
			this.now = now;
		}

		Instant now() {
			return now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}
	}

	private static final class CountingTransactions implements TransactionRunner {
		int count;

		@Override
		public <T> T required(Supplier<T> work) {
			count++;
			return work.get();
		}
	}

	private static final class SequentialIdentifiers implements IdentifierSource {
		private long next;
		private UUID next() { return new UUID(0, ++next); }
		@Override public ProjectId newProjectId() { return new ProjectId(next()); }
		@Override public TaskId newTaskId() { return new TaskId(next()); }
		@Override public ActivityId newActivityId() { return new ActivityId(next()); }
		@Override public LabelId newLabelId() { return new LabelId(next()); }
	}

	private static final class InMemoryStore implements ProjectRepository, TaskRepository,
			ActivityRepository, LabelRepository {
		final Map<ProjectId, Project> projects = new HashMap<>();
		final Map<TaskId, Task> tasks = new HashMap<>();
		final Map<ActivityId, Activity> activities = new HashMap<>();
		final Map<LabelId, Label> labels = new HashMap<>();

		@Override public Optional<Project> find(ProjectId id) { return Optional.ofNullable(projects.get(id)); }
		@Override public List<Project> findAllProjects() { return List.copyOf(projects.values()); }
		@Override public Project save(Project value) { projects.put(value.id(), value); return value; }
		@Override public void delete(ProjectId id) { projects.remove(id); }

		@Override public Optional<Task> find(TaskId id) { return Optional.ofNullable(tasks.get(id)); }
		@Override public Optional<Task> findByExternalReference(ExternalTaskReferenceKey reference) {
			return tasks.values().stream().filter(task -> task.externalReferences().stream()
					.anyMatch(candidate -> candidate.key().equals(reference))).findFirst();
		}
		@Override public List<Task> findAllTasks() { return List.copyOf(tasks.values()); }
		@Override public boolean existsByProject(ProjectId id) {
			return tasks.values().stream().anyMatch(task -> task.projectId().filter(id::equals).isPresent());
		}
		@Override public Task save(Task value) { tasks.put(value.id(), value); return value; }
		@Override public void delete(TaskId id) { tasks.remove(id); }

		@Override public Optional<Activity> find(ActivityId id) { return Optional.ofNullable(activities.get(id)); }
		@Override public Optional<Activity> findOpenByOwner(OwnerId owner) {
			return activities.values().stream().filter(activity -> activity.ownerId().equals(owner) && activity.end().isEmpty())
					.findFirst();
		}
		@Override public List<Activity> findOverlapping(Instant from, Instant to, Optional<OwnerId> owner,
				Optional<TaskId> task) {
			return activities.values().stream()
					.filter(activity -> owner.isEmpty() || owner.get().equals(activity.ownerId()))
					.filter(activity -> task.isEmpty() || task.get().equals(activity.taskId()))
					.filter(activity -> activity.start().isBefore(to))
					.filter(activity -> activity.end().isEmpty() || activity.end().get().isAfter(from)).toList();
		}
		@Override public boolean existsByTask(TaskId id) {
			return activities.values().stream().anyMatch(activity -> activity.taskId().equals(id));
		}
		@Override public boolean existsByLabel(LabelId id) {
			return activities.values().stream().anyMatch(activity -> activity.labelIds().contains(id));
		}
		@Override public Activity save(Activity value) { activities.put(value.id(), value); return value; }
		@Override public void delete(ActivityId id) { activities.remove(id); }

		@Override public Optional<Label> find(LabelId id) { return Optional.ofNullable(labels.get(id)); }
		@Override public List<Label> findAllLabels() { return List.copyOf(labels.values()); }
		@Override public Label save(Label value) { labels.put(value.id(), value); return value; }
		@Override public void delete(LabelId id) { labels.remove(id); }
	}
}
