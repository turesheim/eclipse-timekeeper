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
import net.resheim.eclipse.timekeeper.domain.ActivityDailySegments;
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
        this(ports,null);
    }
    public DefaultTimekeeperService(ServicePorts ports,WorkSessionService workSessions) {
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
            if(ports.activities().findAllActivities().stream().anyMatch(value -> value.projectId().equals(Optional.of(current.id()))))
                throw conflict("project", "project still contains activities");
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
			validateParent(command.projectId(), command.parentTaskId(), null);
			for (ExternalTaskReference reference : command.externalReferences()) {
				if (ports.tasks().findByExternalReference(reference.key()).isPresent()) {
					throw conflict("externalReference", "external reference is linked to another task");
				}
			}
			Task created = ports.tasks().save(new Task(ports.identifiers().newTaskId(), command.projectId(),
					command.parentTaskId(),
					command.summary(), command.url(), command.externalReferences(), 0));
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
			if (!current.projectId().equals(command.projectId()) && ports.tasks().existsByParent(current.id())) {
				throw conflict("projectId", "a task with subtasks cannot move to another project");
			}
			validateParent(command.projectId(), command.parentTaskId(), current.id());
			Task updated = ports.tasks().save(current.update(command.projectId(), command.parentTaskId(),
					command.summary(), command.url()));
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
			if (ports.tasks().existsByParent(current.id())) {
				throw conflict("task", "task still contains subtasks");
			}
			ports.tasks().delete(current.id());
			publish(TASK, DELETED, current.id().value().toString());
			return null;
		});
	}

	private void validateParent(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, TaskId taskId) {
		if (parentTaskId.isEmpty()) return;
		Task parent = requiredTask(parentTaskId.orElseThrow());
		if (taskId != null && parent.id().equals(taskId)) {
			throw conflict("parentTaskId", "a task cannot be its own parent");
		}
		if (projectId.isEmpty() || !projectId.equals(parent.projectId())) {
			throw conflict("parentTaskId", "a subtask must belong to the same project as its parent");
		}
		for (Optional<TaskId> ancestor = parent.parentTaskId(); ancestor.isPresent();) {
			if (taskId != null && ancestor.get().equals(taskId)) {
				throw conflict("parentTaskId", "a task hierarchy cannot contain a cycle");
			}
			ancestor = requiredTask(ancestor.get()).parentTaskId();
		}
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
			Task task = command.taskId() == null ? null : requiredTask(command.taskId());
			OwnerId owner = required(command.ownerId(), "ownerId");
			ensureNoOpenActivity(owner, null);
            validateLabels(command.labelIds());validateActivityContext(task,command.projectId());
            Activity created = ports.activities().save(new Activity(ports.identifiers().newActivityId(), task == null ? null : task.id(),
					owner, ports.timeSource().now(), Optional.empty(), command.summary(), command.labelIds(), false, 0, Optional.empty(), command.projectId()));
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
			Instant now = ports.timeSource().now();
			return saveDailyActivities(current.stop(now), true, now);
		});
	}

	@Override
	public Optional<Activity> activeActivity(OwnerId ownerId) {
		return tx(() -> ports.activities().findOpenByOwner(required(ownerId, "ownerId")));
	}

	@Override
	public void rolloverActivities(OwnerId ownerId) {
		tx(() -> {
			required(ownerId, "ownerId");
			Instant now = ports.timeSource().now();
			for (var current : ports.activities().findAllActivities()) {
				if (!current.ownerId().equals(ownerId)) continue;
				if (ActivityDailySegments.split(current, ports.timeSource().zone(ownerId), now).size() < 2) continue;
				var changed = new Activity(current.id(), current.taskId(), current.ownerId(), current.start(), current.end(),
						current.summary(), current.labelIds(), current.manual(), current.version() + 1, current.workSessionId(), current.projectId());
				saveDailyActivities(changed, true, now);
			}
			return null;
		});
	}

	@Override
	public Activity createActivity(CreateActivity command) {
		return tx(() -> {
			required(command, "command");
			Activity.validateStart(command.start(), ports.timeSource().now());
			Task task = command.taskId() == null ? null : requiredTask(command.taskId());
			OwnerId owner = required(command.ownerId(), "ownerId");
            validateLabels(command.labelIds());validateActivityContext(task,command.projectId());
            if (command.end().isEmpty()) ensureNoOpenActivity(owner, null);
            return saveDailyActivities(new Activity(ports.identifiers().newActivityId(), task == null ? null : task.id(),
					owner, command.start(), command.end(), command.summary(), command.labelIds(), true, 0, Optional.empty(), command.projectId()), false,
					ports.timeSource().now());
		});
	}

	@Override
	public Activity updateActivity(UpdateActivity command) {
		return tx(() -> {
			required(command, "command");
			Activity current = requiredActivity(command.id());
			version(current.version(), command.expectedVersion(), "activity");
			validateLabels(command.labelIds());
			Activity.validateStart(command.start(), ports.timeSource().now());
			if (command.end().isEmpty()) ensureNoOpenActivity(current.ownerId(), current.id());
			return saveDailyActivities(current.edit(command.start(), command.end().orElse(null),
					command.summary(), command.labelIds()), true,
					ports.timeSource().now());
		});
	}

	private Activity saveDailyActivities(Activity requested, boolean existing, Instant now) {
		Activity prior = existing ? requiredActivity(requested.id()) : null;
		boolean unchangedWork = prior != null && sameRecordedWork(prior,requested);
		var intervals = ActivityDailySegments.split(requested, ports.timeSource().zone(requested.ownerId()), now);
		int retainedIndex = intervals.size() - 1;
		Activity retained = null;
		for (int index = 0; index < intervals.size(); index++) {
			var interval = intervals.get(index);
			boolean original = index == retainedIndex;
			var part = new Activity(original ? requested.id() : ActivityDailySegments.identifier(requested.id(), interval.start()), requested.taskId(),
					requested.ownerId(), interval.start(), interval.end(), requested.summary(), requested.labelIds(),
					requested.manual(), original ? requested.version() : 0, requested.workSessionId(), requested.projectId());
			if (!original && ports.activities().find(part.id()).isPresent())
				throw conflict("activity", "A daily activity already uses this interval. Edit that day's activity separately.");
			part = ports.activities().saveDailyPart(part,prior,unchangedWork);
			publish(ACTIVITY, original && existing ? UPDATED : CREATED, part.id().value().toString());
			if (original) retained = part;
		}
		return retained;
	}

    private static boolean sameRecordedWork(Activity before,Activity after) {
        return Objects.equals(before.taskId(),after.taskId()) && before.ownerId().equals(after.ownerId())
                && before.start().equals(after.start()) && before.end().equals(after.end())
                && before.summary().equals(after.summary()) && before.labelIds().equals(after.labelIds())
                && before.projectId().equals(after.projectId()) && before.manual()==after.manual();
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
		return projectId.isEmpty() || (activity.taskId() == null ? activity.projectId().equals(projectId) : requiredTask(activity.taskId()).projectId().equals(projectId));
	}

    private void validateActivityContext(Task task,Optional<ProjectId> projectId) {
        projectId.ifPresent(this::requiredProject);
        if(task!=null && projectId.isPresent() && !task.projectId().equals(projectId)) throw conflict("projectId","The activity project must match its task's project.");
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
