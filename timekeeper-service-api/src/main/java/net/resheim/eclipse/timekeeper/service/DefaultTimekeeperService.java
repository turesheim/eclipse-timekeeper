package net.resheim.eclipse.timekeeper.service;

import static net.resheim.eclipse.timekeeper.service.TimekeeperEvent.ChangeType.CREATED;
import static net.resheim.eclipse.timekeeper.service.TimekeeperEvent.ChangeType.DELETED;
import static net.resheim.eclipse.timekeeper.service.TimekeeperEvent.ChangeType.UPDATED;
import static net.resheim.eclipse.timekeeper.service.TimekeeperEvent.EntityType.ACTIVITY;
import static net.resheim.eclipse.timekeeper.service.TimekeeperEvent.EntityType.LABEL;
import static net.resheim.eclipse.timekeeper.service.TimekeeperEvent.EntityType.PROJECT;
import static net.resheim.eclipse.timekeeper.service.TimekeeperEvent.EntityType.TASK;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
import net.resheim.eclipse.timekeeper.service.Ports.ServicePorts;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityReport;
import net.resheim.eclipse.timekeeper.service.TimekeeperEvent.ChangeType;
import net.resheim.eclipse.timekeeper.service.TimekeeperEvent.EntityType;

/** Default application service. Every public operation owns one transaction boundary. */
public class DefaultTimekeeperService implements TimekeeperService {
	private final ServicePorts ports;

	public DefaultTimekeeperService(ServicePorts ports) {
		this.ports = Objects.requireNonNull(ports, "ports");
	}

	@Override
	public Project createProject(CreateProject command) {
		return tx(() -> {
			required(command, "command");
			Project created = ports.projects().save(new Project(ports.identifiers().newProjectId(),
					command.name(), 0));
			publish(PROJECT, CREATED, created.id().value().toString());
			return created;
		});
	}

	@Override
	public Project updateProject(UpdateProject command) {
		return tx(() -> {
			required(command, "command");
			Project current = requiredProject(command.id());
			version(current.version(), command.expectedVersion(), "project");
			Project updated = ports.projects().save(current.rename(command.name()));
			publish(PROJECT, UPDATED, updated.id().value().toString());
			return updated;
		});
	}

	@Override
	public void deleteProject(DeleteProject command) {
		tx(() -> {
			required(command, "command");
			Project current = requiredProject(command.id());
			version(current.version(), command.expectedVersion(), "project");
			if (ports.tasks().existsByProject(current.id())) {
				throw conflict("project", "project still contains tasks");
			}
			ports.projects().delete(current.id());
			publish(PROJECT, DELETED, current.id().value().toString());
			return null;
		});
	}

	@Override
	public Optional<Project> project(ProjectId id) {
		return tx(() -> ports.projects().find(required(id, "projectId")));
	}

	@Override
	public List<Project> projects() {
		return tx(() -> List.copyOf(ports.projects().findAllProjects()));
	}

	@Override
	public Task createTask(CreateTask command) {
		return tx(() -> {
			required(command, "command");
			command.projectId().ifPresent(this::requiredProject);
			Task created = ports.tasks().save(new Task(ports.identifiers().newTaskId(), command.projectId(),
					command.summary(), command.url(), Set.of(), 0));
			publish(TASK, CREATED, created.id().value().toString());
			return created;
		});
	}

	@Override
	public Task updateTask(UpdateTask command) {
		return tx(() -> {
			required(command, "command");
			Task current = requiredTask(command.id());
			version(current.version(), command.expectedVersion(), "task");
			command.projectId().ifPresent(this::requiredProject);
			Task updated = ports.tasks().save(current.update(command.projectId(), command.summary(), command.url()));
			publish(TASK, UPDATED, updated.id().value().toString());
			return updated;
		});
	}

	@Override
	public void deleteTask(DeleteTask command) {
		tx(() -> {
			required(command, "command");
			Task current = requiredTask(command.id());
			version(current.version(), command.expectedVersion(), "task");
			if (ports.activities().existsByTask(current.id())) {
				throw conflict("task", "task still contains activities");
			}
			ports.tasks().delete(current.id());
			publish(TASK, DELETED, current.id().value().toString());
			return null;
		});
	}

	@Override
	public Optional<Task> task(TaskId id) {
		return tx(() -> ports.tasks().find(required(id, "taskId")));
	}

	@Override
	public List<Task> tasks() {
		return tx(() -> List.copyOf(ports.tasks().findAllTasks()));
	}

	@Override
	public Task linkExternalReference(LinkExternalReference command) {
		return tx(() -> {
			required(command, "command");
			Task current = requiredTask(command.taskId());
			version(current.version(), command.expectedVersion(), "task");
			ExternalTaskReference reference = required(command.reference(), "externalReference");
			ports.tasks().findByExternalReference(reference.key()).ifPresent(linked -> {
				if (!linked.id().equals(current.id())) {
					throw conflict("externalReference", "external reference is linked to another task");
				}
			});
			Task updated = ports.tasks().save(current.link(reference));
			publish(TASK, UPDATED, updated.id().value().toString());
			return updated;
		});
	}

	@Override
	public Task unlinkExternalReference(UnlinkExternalReference command) {
		return tx(() -> {
			required(command, "command");
			Task current = requiredTask(command.taskId());
			version(current.version(), command.expectedVersion(), "task");
			ExternalTaskReferenceKey key = required(command.reference(), "externalReference");
			ExternalTaskReference reference = current.externalReferences().stream()
					.filter(candidate -> candidate.key().equals(key)).findFirst()
					.orElseThrow(() -> ServiceException.notFound("externalReference", key));
			Task updated = ports.tasks().save(current.unlink(reference));
			publish(TASK, UPDATED, updated.id().value().toString());
			return updated;
		});
	}

	@Override
	public Optional<Task> findTask(ExternalTaskReferenceKey reference) {
		return tx(() -> ports.tasks().findByExternalReference(required(reference, "externalReference")));
	}

	@Override
	public Label createLabel(CreateLabel command) {
		return tx(() -> {
			required(command, "command");
			Label created = ports.labels().save(new Label(ports.identifiers().newLabelId(), command.name(),
					command.color(), 0));
			publish(LABEL, CREATED, created.id().value().toString());
			return created;
		});
	}

	@Override
	public Label updateLabel(UpdateLabel command) {
		return tx(() -> {
			required(command, "command");
			Label current = requiredLabel(command.id());
			version(current.version(), command.expectedVersion(), "label");
			Label updated = ports.labels().save(current.update(command.name(), command.color()));
			publish(LABEL, UPDATED, updated.id().value().toString());
			return updated;
		});
	}

	@Override
	public void deleteLabel(DeleteLabel command) {
		tx(() -> {
			required(command, "command");
			Label current = requiredLabel(command.id());
			version(current.version(), command.expectedVersion(), "label");
			if (ports.activities().existsByLabel(current.id())) {
				throw conflict("label", "label is still used by activities");
			}
			ports.labels().delete(current.id());
			publish(LABEL, DELETED, current.id().value().toString());
			return null;
		});
	}

	@Override
	public Optional<Label> label(LabelId id) {
		return tx(() -> ports.labels().find(required(id, "labelId")));
	}

	@Override
	public List<Label> labels() {
		return tx(() -> List.copyOf(ports.labels().findAllLabels()));
	}

	@Override
	public Activity startActivity(StartActivity command) {
		return tx(() -> {
			required(command, "command");
			Task task = requiredTask(command.taskId());
			OwnerId owner = required(command.ownerId(), "ownerId");
			ensureNoOpenActivity(owner, null);
			validateLabels(command.labelIds());
			Activity created = ports.activities().save(new Activity(ports.identifiers().newActivityId(), task.id(),
					owner, ports.timeSource().now(), Optional.empty(), command.summary(), command.labelIds(), false, 0));
			publish(ACTIVITY, CREATED, created.id().value().toString());
			return created;
		});
	}

	@Override
	public Activity stopActivity(StopActivity command) {
		return tx(() -> {
			required(command, "command");
			Activity current = requiredActivity(command.id());
			version(current.version(), command.expectedVersion(), "activity");
			Activity updated = ports.activities().save(current.stop(ports.timeSource().now()));
			publish(ACTIVITY, UPDATED, updated.id().value().toString());
			return updated;
		});
	}

	@Override
	public Activity createActivity(CreateActivity command) {
		return tx(() -> {
			required(command, "command");
			Task task = requiredTask(command.taskId());
			OwnerId owner = required(command.ownerId(), "ownerId");
			validateLabels(command.labelIds());
			if (command.end().isEmpty()) ensureNoOpenActivity(owner, null);
			Activity created = ports.activities().save(new Activity(ports.identifiers().newActivityId(), task.id(),
					owner, command.start(), command.end(), command.summary(), command.labelIds(), true, 0));
			publish(ACTIVITY, CREATED, created.id().value().toString());
			return created;
		});
	}

	@Override
	public Activity updateActivity(UpdateActivity command) {
		return tx(() -> {
			required(command, "command");
			Activity current = requiredActivity(command.id());
			version(current.version(), command.expectedVersion(), "activity");
			validateLabels(command.labelIds());
			if (command.end().isEmpty()) ensureNoOpenActivity(current.ownerId(), current.id());
			Activity updated = ports.activities().save(current.edit(command.start(), command.end().orElse(null),
					command.summary(), command.labelIds()));
			publish(ACTIVITY, UPDATED, updated.id().value().toString());
			return updated;
		});
	}

	@Override
	public void deleteActivity(DeleteActivity command) {
		tx(() -> {
			required(command, "command");
			Activity current = requiredActivity(command.id());
			version(current.version(), command.expectedVersion(), "activity");
			ports.activities().delete(current.id());
			publish(ACTIVITY, DELETED, current.id().value().toString());
			return null;
		});
	}

	@Override
	public Optional<Activity> activity(ActivityId id) {
		return tx(() -> ports.activities().find(required(id, "activityId")));
	}

	@Override
	public ActivityReport activities(ActivityQuery query) {
		return tx(() -> {
			required(query, "query");
			Instant generatedAt = ports.timeSource().now();
			List<Activity> activities = ports.activities().findOverlapping(query.fromInclusive(), query.toExclusive(),
					query.ownerId(), query.taskId()).stream()
					.filter(activity -> matchesProject(activity, query.projectId()))
					.sorted(Comparator.comparing(Activity::start).thenComparing(activity -> activity.id().value()))
					.toList();
			Duration total = activities.stream()
					.map(activity -> activity.durationBetween(query.fromInclusive(), query.toExclusive(), generatedAt))
					.reduce(Duration.ZERO, Duration::plus);
			return new ActivityReport(query, generatedAt, activities, total);
		});
	}

	private boolean matchesProject(Activity activity, Optional<ProjectId> projectId) {
		return projectId.isEmpty() || requiredTask(activity.taskId()).projectId().equals(projectId);
	}

	private void validateLabels(Set<LabelId> labels) {
		required(labels, "labelIds").forEach(this::requiredLabel);
	}

	private void ensureNoOpenActivity(OwnerId ownerId, ActivityId allowed) {
		ports.activities().findOpenByOwner(ownerId).ifPresent(open -> {
			if (!open.id().equals(allowed)) {
				throw conflict("ownerId", "owner already has an open activity");
			}
		});
	}

	private Project requiredProject(ProjectId id) {
		return ports.projects().find(required(id, "projectId"))
				.orElseThrow(() -> ServiceException.notFound("projectId", id));
	}

	private Task requiredTask(TaskId id) {
		return ports.tasks().find(required(id, "taskId"))
				.orElseThrow(() -> ServiceException.notFound("taskId", id));
	}

	private Activity requiredActivity(ActivityId id) {
		return ports.activities().find(required(id, "activityId"))
				.orElseThrow(() -> ServiceException.notFound("activityId", id));
	}

	private Label requiredLabel(LabelId id) {
		return ports.labels().find(required(id, "labelId"))
				.orElseThrow(() -> ServiceException.notFound("labelId", id));
	}

	private void version(long actual, long expected, String field) {
		if (expected < 0) throw invalid("expectedVersion", "expectedVersion must not be negative");
		if (actual != expected) throw conflict(field, "stale " + field + " version");
	}

	private void publish(EntityType type, ChangeType change, String id) {
		ports.events().publish(new TimekeeperEvent(type, change, id, ports.timeSource().now()));
	}

	private <T> T tx(Supplier<T> work) {
		return ports.transactions().required(work);
	}

	private static <T> T required(T value, String field) {
		if (value == null) throw invalid(field, field + " must not be null");
		return value;
	}

	private static DomainValidationException invalid(String field, String message) {
		return new DomainValidationException(field, message);
	}

	private static ServiceException conflict(String field, String message) {
		return new ServiceException(FailureCode.CONFLICT, field, message);
	}
}
