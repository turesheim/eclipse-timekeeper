package net.resheim.eclipse.timekeeper.service;

import java.util.List;
import java.util.Optional;

import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.Label;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskConnection;
import net.resheim.eclipse.timekeeper.domain.TaskList;
import net.resheim.eclipse.timekeeper.domain.TaskSyncCursor;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateActivity;
import net.resheim.eclipse.timekeeper.service.Commands.CreateLabel;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTaskConnection;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTaskList;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTaskSyncCursor;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteActivity;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteLabel;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteProject;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteTask;
import net.resheim.eclipse.timekeeper.service.Commands.LinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.ReplaceExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateExternalTaskSource;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateExternalTaskProviderData;
import net.resheim.eclipse.timekeeper.service.Commands.StartActivity;
import net.resheim.eclipse.timekeeper.service.Commands.StopActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UnlinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateLabel;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateProject;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskConnection;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskList;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskSyncCursor;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskPlanning;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskChecklist;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskTiming;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskImportance;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskBody;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskRecurrence;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskOccurrences;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskOrder;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityReport;

/** Mylyn-, Eclipse-, OSGi- and persistence-independent Timekeeper operations. */
public interface TimekeeperService {
	Project createProject(CreateProject command);
	Project updateProject(UpdateProject command);
	void deleteProject(DeleteProject command);
	Optional<Project> project(ProjectId id);
	List<Project> projects();
	default TaskConnection createTaskConnection(CreateTaskConnection command) { throw new UnsupportedOperationException("Task connections are not supported"); }
	default TaskConnection updateTaskConnection(UpdateTaskConnection command) { throw new UnsupportedOperationException("Task connections are not supported"); }
	default void deleteTaskConnection(java.util.UUID id,long expectedVersion) { throw new UnsupportedOperationException("Task connections are not supported"); }
	default Optional<TaskConnection> taskConnection(java.util.UUID id) { throw new UnsupportedOperationException("Task connections are not supported"); }
	default List<TaskConnection> taskConnections() { throw new UnsupportedOperationException("Task connections are not supported"); }
	default TaskList createTaskList(CreateTaskList command) { throw new UnsupportedOperationException("Provider task lists are not supported"); }
	default TaskList updateTaskList(UpdateTaskList command) { throw new UnsupportedOperationException("Provider task lists are not supported"); }
	default void deleteTaskList(java.util.UUID id,long expectedVersion) { throw new UnsupportedOperationException("Provider task lists are not supported"); }
	default Optional<TaskList> taskList(java.util.UUID id) { throw new UnsupportedOperationException("Provider task lists are not supported"); }
	default List<TaskList> taskLists(java.util.UUID connectionId) { throw new UnsupportedOperationException("Provider task lists are not supported"); }
	default TaskSyncCursor createTaskSyncCursor(CreateTaskSyncCursor command) { throw new UnsupportedOperationException("Task sync cursors are not supported"); }
	default TaskSyncCursor updateTaskSyncCursor(UpdateTaskSyncCursor command) { throw new UnsupportedOperationException("Task sync cursors are not supported"); }
	default void deleteTaskSyncCursor(java.util.UUID id,long expectedVersion) { throw new UnsupportedOperationException("Task sync cursors are not supported"); }
	default Optional<TaskSyncCursor> taskSyncCursor(java.util.UUID id) { throw new UnsupportedOperationException("Task sync cursors are not supported"); }
	default List<TaskSyncCursor> taskSyncCursors(java.util.UUID connectionId) { throw new UnsupportedOperationException("Task sync cursors are not supported"); }

	Task createTask(CreateTask command);
	Task updateTask(UpdateTask command);
	default Task updateTaskPlanning(UpdateTaskPlanning command) { throw new UnsupportedOperationException("Task planning is not supported"); }
	default Task updateTaskChecklist(UpdateTaskChecklist command) { throw new UnsupportedOperationException("Task checklists are not supported"); }
	default Task updateTaskTiming(UpdateTaskTiming command) { throw new UnsupportedOperationException("Task timing is not supported"); }
	default Task updateTaskImportance(UpdateTaskImportance command) { throw new UnsupportedOperationException("Task importance is not supported"); }
	default Task updateTaskBody(UpdateTaskBody command) { throw new UnsupportedOperationException("Task body is not supported"); }
	default Task updateTaskRecurrence(UpdateTaskRecurrence command) { throw new UnsupportedOperationException("Task recurrence is not supported"); }
	default Task updateTaskOccurrences(UpdateTaskOccurrences command) { throw new UnsupportedOperationException("Task occurrences are not supported"); }
	default Task updateTaskOrder(UpdateTaskOrder command) { throw new UnsupportedOperationException("Task order is not supported"); }
	void deleteTask(DeleteTask command);
	Optional<Task> task(TaskId id);
	List<Task> tasks();
	Task linkExternalReference(LinkExternalReference command);
	default Task replaceExternalReference(ReplaceExternalReference command) { throw new UnsupportedOperationException("External task identity replacement is not supported"); }
	default Task updateExternalTaskSource(UpdateExternalTaskSource command) { throw new UnsupportedOperationException("External task source metadata is not supported"); }
	default Task updateExternalTaskProviderData(UpdateExternalTaskProviderData command) { throw new UnsupportedOperationException("External task provider data is not supported"); }
	Task unlinkExternalReference(UnlinkExternalReference command);
	Optional<Task> findTask(ExternalTaskReferenceKey reference);

	Label createLabel(CreateLabel command);
	Label updateLabel(UpdateLabel command);
	void deleteLabel(DeleteLabel command);
	Optional<Label> label(LabelId id);
	List<Label> labels();

	Activity startActivity(StartActivity command);
	void rolloverActivities(OwnerId ownerId);
	Activity stopActivity(StopActivity command);
	Optional<Activity> activeActivity(OwnerId ownerId);
	Activity createActivity(CreateActivity command);
	Activity updateActivity(UpdateActivity command);
	void deleteActivity(DeleteActivity command);
	Optional<Activity> activity(ActivityId id);
	ActivityReport activities(ActivityQuery query);
}
