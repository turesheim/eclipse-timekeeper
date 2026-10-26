package net.resheim.eclipse.timekeeper.service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.List;

import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.WorkSessionId;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskSource;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskProviderData;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.domain.TaskChecklist;
import net.resheim.eclipse.timekeeper.domain.TaskPlanning;
import net.resheim.eclipse.timekeeper.domain.TaskTiming;
import net.resheim.eclipse.timekeeper.domain.TaskImportance;
import net.resheim.eclipse.timekeeper.domain.TaskBody;
import net.resheim.eclipse.timekeeper.domain.TaskRecurrence;
import net.resheim.eclipse.timekeeper.domain.TaskOccurrence;
import net.resheim.eclipse.timekeeper.domain.TaskReminder;
import net.resheim.eclipse.timekeeper.domain.TaskCapabilities;
import net.resheim.eclipse.timekeeper.domain.TaskSyncMode;

/** Immutable inputs accepted by {@link TimekeeperService}. */
public final class Commands {
	private Commands() { }

	public record CreateProject(String name) { }
	public record UpdateProject(ProjectId id, long expectedVersion, String name) { }
	public record DeleteProject(ProjectId id, long expectedVersion) { }
	public record CreateTaskConnection(String providerId, String remoteAccountId, String name,
			boolean readable, boolean writable, boolean enabled) { }
	public record UpdateTaskConnection(java.util.UUID id, long expectedVersion, String name,
			boolean readable, boolean writable, boolean enabled) { }
	public record UpdateTaskConnectionCapabilities(java.util.UUID id,long expectedVersion,TaskCapabilities capabilities) { }
	public record CreateTaskList(java.util.UUID connectionId, String remoteListId, String name,
			net.resheim.eclipse.timekeeper.domain.TaskListKind kind, boolean readable, boolean writable,
			boolean shared, boolean owner, boolean present, int position) { }
	public record UpdateTaskList(java.util.UUID id, long expectedVersion, String name,
			net.resheim.eclipse.timekeeper.domain.TaskListKind kind, boolean readable, boolean writable,
			boolean shared, boolean owner, boolean present, int position) { }
	public record CreateTaskSyncCursor(java.util.UUID connectionId, Optional<java.util.UUID> listId,
			TaskSyncMode mode, Optional<String> cursor, Optional<Instant> lastObservedAt,
			Optional<Instant> lastCompleteAt) { }
	public record UpdateTaskSyncCursor(java.util.UUID id, long expectedVersion, TaskSyncMode mode,
			Optional<String> cursor, Optional<Instant> lastObservedAt, Optional<Instant> lastCompleteAt) { }

	public record CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
			Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body, int siblingOrder, Set<ExternalTaskReferenceKey> externalAliases, TaskRecurrence recurrence, List<TaskReminder> reminders) {
		public CreateTask {
			projectId = optional(projectId);
			parentTaskId = optional(parentTaskId);
			url = optional(url);
			externalReferences = copy(externalReferences);
			externalAliases = copy(externalAliases);
			planning = planning == null ? TaskPlanning.unplanned() : planning;
			checklist = checklist == null ? TaskChecklist.empty() : checklist;
			timing = timing == null ? TaskTiming.empty() : timing;
			importance = importance == null ? TaskImportance.ordinary() : importance;
			body = body == null ? TaskBody.empty() : body;
			recurrence = recurrence == null ? TaskRecurrence.empty() : recurrence;
			reminders = reminders == null ? List.of() : List.copyOf(reminders);
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning,
				TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body, int siblingOrder,
				Set<ExternalTaskReferenceKey> externalAliases, TaskRecurrence recurrence) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, importance, body, siblingOrder, externalAliases, recurrence, List.of());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning,
				TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body, int siblingOrder,
				Set<ExternalTaskReferenceKey> externalAliases) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, importance, body, siblingOrder, externalAliases, TaskRecurrence.empty());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning,
				TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body, int siblingOrder) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, importance, body, siblingOrder, Set.of());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning,
				TaskChecklist checklist, TaskTiming timing, TaskImportance importance, TaskBody body) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, importance, body, 0);
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist, TaskTiming timing, TaskImportance importance) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, importance, TaskBody.empty());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist, TaskTiming timing) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, timing, TaskImportance.ordinary(), TaskBody.empty());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning, TaskChecklist checklist) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, checklist, TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences, TaskPlanning planning) {
			this(projectId, parentTaskId, summary, url, externalReferences, planning, TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url, Set<ExternalTaskReference> externalReferences) {
			this(projectId, parentTaskId, summary, url, externalReferences, TaskPlanning.unplanned(), TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty());
		}

		public CreateTask(Optional<ProjectId> projectId, Optional<TaskId> parentTaskId, String summary,
				Optional<String> url) {
			this(projectId, parentTaskId, summary, url, Set.of(), TaskPlanning.unplanned(), TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty());
		}

		public CreateTask(Optional<ProjectId> projectId, String summary, Optional<String> url) {
			this(projectId, Optional.empty(), summary, url, Set.of(), TaskPlanning.unplanned(), TaskChecklist.empty(), TaskTiming.empty(), TaskImportance.ordinary(), TaskBody.empty());
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
	public record UpdateTaskBody(TaskId id, long expectedVersion, TaskBody body) { }
	public record UpdateTaskRecurrence(TaskId id, long expectedVersion, TaskRecurrence recurrence) { }
	public record UpdateTaskOccurrences(TaskId id, long expectedVersion, java.util.List<TaskOccurrence> occurrences) { }
	public record UpdateTaskReminders(TaskId id, long expectedVersion, List<TaskReminder> reminders) { }
	public record UpdateTaskOrder(TaskId id, long expectedVersion, int siblingOrder) { }
	public record LinkExternalReference(TaskId taskId, long expectedVersion, ExternalTaskReference reference) { }
	public record ReplaceExternalReference(TaskId taskId, long expectedVersion, ExternalTaskReferenceKey previous, ExternalTaskReference current) { }
	public record UpdateExternalTaskSource(TaskId taskId, long expectedVersion, ExternalTaskReferenceKey reference, ExternalTaskSource source) { }
	public record UpdateExternalTaskProviderData(TaskId taskId, long expectedVersion, ExternalTaskReferenceKey reference, Optional<ExternalTaskProviderData> data) { }
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
