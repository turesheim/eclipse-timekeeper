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
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityReport;

/** Mylyn-, Eclipse-, OSGi- and persistence-independent Timekeeper operations. */
public interface TimekeeperService {
	Project createProject(CreateProject command);
	Project updateProject(UpdateProject command);
	void deleteProject(DeleteProject command);
	Optional<Project> project(ProjectId id);
	List<Project> projects();

	Task createTask(CreateTask command);
	Task updateTask(UpdateTask command);
	void deleteTask(DeleteTask command);
	Optional<Task> task(TaskId id);
	List<Task> tasks();
	Task linkExternalReference(LinkExternalReference command);
	Task unlinkExternalReference(UnlinkExternalReference command);
	Optional<Task> findTask(ExternalTaskReferenceKey reference);

	Label createLabel(CreateLabel command);
	Label updateLabel(UpdateLabel command);
	void deleteLabel(DeleteLabel command);
	Optional<Label> label(LabelId id);
	List<Label> labels();

	Activity startActivity(StartActivity command);
	Activity stopActivity(StopActivity command);
	Optional<Activity> activeActivity(OwnerId ownerId);
	Activity createActivity(CreateActivity command);
	Activity updateActivity(UpdateActivity command);
	void deleteActivity(DeleteActivity command);
	Optional<Activity> activity(ActivityId id);
	ActivityReport activities(ActivityQuery query);
}
