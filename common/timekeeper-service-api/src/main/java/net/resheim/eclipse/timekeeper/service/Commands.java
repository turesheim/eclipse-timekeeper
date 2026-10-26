package net.resheim.eclipse.timekeeper.service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.WorkSessionId;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.domain.TaskChecklist;
import net.resheim.eclipse.timekeeper.domain.TaskPlanning;
import net.resheim.eclipse.timekeeper.domain.TaskTiming;
import net.resheim.eclipse.timekeeper.domain.TaskImportance;

/** Immutable inputs accepted by {@link TimekeeperService}. */
public final class Commands {
	private Commands() { }

	public record CreateProject(String name) { }
	public record UpdateProject(ProjectId id, long expectedVersion, String name) { }
	public record DeleteProject(ProjectId id, long expectedVersion) { }

	public record CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist, TaskTiming timing, TaskImportance importance) {
		public CreateTask {
			projectId = optional(projectId);
			parentTaskId = optional(parentTaskId);
			url = optional(url);
			externalReferences = copy(externalReferences);
			planning = planning == null ? TaskPlanning.unplanned() : planning;
			checklist = checklist == null ? TaskChecklist.empty() : checklist;
			timing = timing == null ? TaskTiming.empty() : timing;
			importance = importance == null ? TaskImportance.ordinary() : importance;
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist, TaskTiming timing) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, TaskImportance.ordinary());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, TaskTiming.empty(), TaskImportance.ordinary());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences) {
			this(projectId, parentTaskId, summary, url, externalReferences, TaskPlanning.unplanned(), TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url) {
			this(projectId, parentTaskId, summary, url, Set.of(), TaskPlanning.unplanned(), TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary());
		}

		public CreateTask(Optional<ProjectId> projectId, String summary, Optional<String> url) {
			this(projectId, Optional.empty(), summary, url, Set.of(), TaskPlanning.unplanned(), TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary());
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
	public record UpdateTaskPlanning(TaskId id, long expectedVersion, TaskPlanning planning) { }
	public record UpdateTaskChecklist(TaskId id, long expectedVersion, TaskChecklist checklist) { }
	public record UpdateTaskTiming(TaskId id, long expectedVersion, TaskTiming timing) { }
	public record UpdateTaskImportance(TaskId id, long expectedVersion, TaskImportance importance) { }
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

	/** workSessionId is retained for source/wire compatibility and ignored. */
	public record StartActivity(TaskId taskId, OwnerId ownerId, String summary, Set<LabelId> labelIds,
            Optional<WorkSessionId> workSessionId, Optional<ProjectId> projectId) {
        public StartActivity {
            labelIds = copy(labelIds); workSessionId = optional(workSessionId); projectId = optional(projectId);
        }
        public StartActivity(TaskId taskId, OwnerId ownerId, String summary, Set<LabelId> labelIds) {
            this(taskId,ownerId,summary,labelIds,Optional.empty(),Optional.empty());
        }
	}

	public record StopActivity(ActivityId id, long expectedVersion) { }

	/** workSessionId is retained for source/wire compatibility and ignored. */
	public record CreateActivity(TaskId taskId, OwnerId ownerId, Instant start, Optional<Instant> end,
			String summary, Set<LabelId> labelIds, Optional<WorkSessionId> workSessionId, Optional<ProjectId> projectId) {
        public CreateActivity {
            end = optional(end); labelIds = copy(labelIds); workSessionId = optional(workSessionId); projectId = optional(projectId);
        }
        public CreateActivity(TaskId taskId, OwnerId ownerId, Instant start, Optional<Instant> end, String summary, Set<LabelId> labelIds) {
            this(taskId,ownerId,start,end,summary,labelIds,Optional.empty(),Optional.empty());
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

    public record StartWorkSession(OwnerId ownerId, String summary) { }
    public record StopWorkSession(WorkSessionId id, long expectedVersion) { }
    public record CreateWorkSession(OwnerId ownerId, Instant start, Optional<Instant> end, String summary) {
        public CreateWorkSession { end = optional(end); }
    }
    public record UpdateWorkSession(WorkSessionId id, long expectedVersion, Instant start, Optional<Instant> end, String summary) {
        public UpdateWorkSession { end = optional(end); }
    }
    public record DeleteWorkSession(WorkSessionId id, long expectedVersion) { }
    /** Retired attachment command; only null/detach remains supported. */
    @Deprecated
    public record AssociateWorkSession(ActivityId activityId, long expectedActivityVersion, Optional<WorkSessionId> workSessionId) {
        public AssociateWorkSession { workSessionId = optional(workSessionId); }
    }

	private static <T> Optional<T> optional(Optional<T> value) {
		return value == null ? Optional.empty() : value;
	}

	private static <T> Set<T> copy(Set<T> values) {
		return values == null ? Set.of() : Set.copyOf(values);
	}
}
