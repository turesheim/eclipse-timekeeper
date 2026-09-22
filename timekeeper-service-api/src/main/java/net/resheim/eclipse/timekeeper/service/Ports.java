package net.resheim.eclipse.timekeeper.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.Label;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;

/** Persistence, transaction, time, identity and event ports required by the service. */
public final class Ports {
	private Ports() { }

	public interface ProjectRepository {
		Optional<Project> find(ProjectId id);
		List<Project> findAllProjects();
		Project save(Project project);
		void delete(ProjectId id);
	}

	public interface TaskRepository {
		Optional<Task> find(TaskId id);
		Optional<Task> findByExternalReference(ExternalTaskReferenceKey reference);
		List<Task> findAllTasks();
		boolean existsByProject(ProjectId projectId);
		Task save(Task task);
		void delete(TaskId id);
	}

	public interface ActivityRepository {
		Optional<Activity> find(ActivityId id);
		Optional<Activity> findOpenByOwner(OwnerId ownerId);
		List<Activity> findOverlapping(Instant fromInclusive, Instant toExclusive,
				Optional<OwnerId> ownerId, Optional<TaskId> taskId);
		boolean existsByTask(TaskId taskId);
		boolean existsByLabel(LabelId labelId);
		Activity save(Activity activity);
		void delete(ActivityId id);
	}

	public interface LabelRepository {
		Optional<Label> find(LabelId id);
		List<Label> findAllLabels();
		Label save(Label label);
		void delete(LabelId id);
	}

	public interface IdentifierSource {
		ProjectId newProjectId();
		TaskId newTaskId();
		ActivityId newActivityId();
		LabelId newLabelId();
	}

	@FunctionalInterface
	public interface TimeSource {
		Instant now();
	}

	@FunctionalInterface
	public interface TransactionRunner {
		<T> T required(Supplier<T> work);
	}

	@FunctionalInterface
	public interface EventSink {
		void publish(TimekeeperEvent event);
	}

	public record ServicePorts(ProjectRepository projects, TaskRepository tasks,
			ActivityRepository activities, LabelRepository labels, IdentifierSource identifiers,
			TimeSource timeSource, TransactionRunner transactions, EventSink events) {
		public ServicePorts {
			if (projects == null || tasks == null || activities == null || labels == null
					|| identifiers == null || timeSource == null || transactions == null || events == null) {
				throw new IllegalArgumentException("service ports must not be null");
			}
		}
	}
}
