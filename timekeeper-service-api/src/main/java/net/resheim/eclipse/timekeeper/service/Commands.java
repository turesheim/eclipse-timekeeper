package net.resheim.eclipse.timekeeper.service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.TaskId;

/** Immutable inputs accepted by {@link TimekeeperService}. */
public final class Commands {
	private Commands() { }

	public record CreateProject(String name) { }
	public record UpdateProject(ProjectId id, long expectedVersion, String name) { }
	public record DeleteProject(ProjectId id, long expectedVersion) { }

	public record CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId,
			String summary, Optional<String> url) {
		public CreateTask {
			projectId = optional(projectId);
			parentTaskId = optional(parentTaskId);
			url = optional(url);
		}

		public CreateTask(Optional<ProjectId> projectId, String summary, Optional<String> url) {
			this(projectId, Optional.empty(), summary, url);
		}
	}

	public record UpdateTask(TaskId id, long expectedVersion, Optional<ProjectId> projectId,
			Optional<TaskId> parentTaskId,
			String summary, Optional<String> url) {
		public UpdateTask {
			projectId = optional(projectId);
			parentTaskId = optional(parentTaskId);
			url = optional(url);
		}

		public UpdateTask(TaskId id, long expectedVersion, Optional<ProjectId> projectId,
				String summary, Optional<String> url) {
			this(id, expectedVersion, projectId, Optional.empty(), summary, url);
		}
	}

	public record DeleteTask(TaskId id, long expectedVersion) { }
	public record LinkExternalReference(TaskId taskId, long expectedVersion, ExternalTaskReference reference) { }
	public record UnlinkExternalReference(TaskId taskId, long expectedVersion, ExternalTaskReferenceKey reference) { }

	public record CreateLabel(String name, Optional<String> color) {
		public CreateLabel {
			color = optional(color);
		}
	}

	public record UpdateLabel(LabelId id, long expectedVersion, String name, Optional<String> color) {
		public UpdateLabel {
			color = optional(color);
		}
	}

	public record DeleteLabel(LabelId id, long expectedVersion) { }

	public record StartActivity(TaskId taskId, OwnerId ownerId, String summary, Set<LabelId> labelIds) {
		public StartActivity {
			labelIds = copy(labelIds);
		}
	}

	public record StopActivity(ActivityId id, long expectedVersion) { }

	public record CreateActivity(TaskId taskId, OwnerId ownerId, Instant start, Optional<Instant> end,
			String summary, Set<LabelId> labelIds) {
		public CreateActivity {
			end = optional(end);
			labelIds = copy(labelIds);
		}
	}

	public record UpdateActivity(ActivityId id, long expectedVersion, Instant start, Optional<Instant> end,
			String summary, Set<LabelId> labelIds) {
		public UpdateActivity {
			end = optional(end);
			labelIds = copy(labelIds);
		}
	}

	public record DeleteActivity(ActivityId id, long expectedVersion) { }

	private static <T> Optional<T> optional(Optional<T> value) {
		return value == null ? Optional.empty() : value;
	}

	private static <T> Set<T> copy(Set<T> values) {
		return values == null ? Set.of() : Set.copyOf(values);
	}
}
