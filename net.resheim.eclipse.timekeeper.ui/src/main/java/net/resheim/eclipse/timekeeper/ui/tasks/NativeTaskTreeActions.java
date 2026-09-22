package net.resheim.eclipse.timekeeper.ui.tasks;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteProject;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteTask;
import net.resheim.eclipse.timekeeper.service.Commands.LinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.StartActivity;
import net.resheim.eclipse.timekeeper.service.Commands.StopActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UnlinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateProject;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

/** Native project/task commands presented directly in the Workweek tree. */
public final class NativeTaskTreeActions {
	private final Shell shell;
	private final TimekeeperService service;
	private final Runnable refresh;
	private final Action newProjectAction;

	public NativeTaskTreeActions(Shell shell, TimekeeperService service, Runnable refresh) {
		this.shell = shell;
		this.service = service;
		this.refresh = refresh;
		newProjectAction = new Action("New Timekeeper project...") {
			@Override public void run() { createProject(); }
		};
		newProjectAction.setToolTipText("Create a native Timekeeper project");
		newProjectAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_OBJ_FOLDER));
	}

	public Action newProjectAction() {
		return newProjectAction;
	}

	public boolean isNative(Project project) {
		return project != null && project.getServiceId() != null;
	}

	public boolean isNative(Task task) {
		return task != null && isNative(task.getProject());
	}

	public void fillContextMenu(IMenuManager menu, Object selected) {
		if (selected instanceof Project project && isNative(project)) {
			menu.add(new Separator("native-project"));
			menu.add(action("New task...", () -> createTask(project, null)));
			menu.add(action("Rename project...", () -> renameProject(project)));
			menu.add(action("Delete project", () -> deleteProject(project)));
		} else if (selected instanceof Task task && isNative(task)) {
			menu.add(new Separator("native-task"));
			var active = service.activeActivity(OwnerId.LOCAL);
			if (active.isEmpty()) {
				menu.add(action("Start activity", () -> startActivity(task)));
			} else if (active.orElseThrow().taskId().equals(taskId(task))) {
				menu.add(action("Stop activity", () -> stopActivity(active.orElseThrow())));
			}
			menu.add(action("New subtask...", () -> createTask(task.getProject(), task)));
			menu.add(action("Edit task...", () -> editTask(task)));
			menu.add(action("Open task URL", () -> openUrl(task)));
			menu.add(action("Link external task...", () -> link(task)));
			if (!domainTask(task).externalReferences().isEmpty()) {
				menu.add(action("Unlink external task...", () -> unlink(task)));
			}
			menu.add(action("Delete task", () -> deleteTask(task)));
		}
	}

	private void startActivity(Task task) {
		var current = domainTask(task);
		run("start activity", () -> service.startActivity(
				new StartActivity(current.id(), OwnerId.LOCAL, current.summary(), Set.of())));
	}

	private void stopActivity(net.resheim.eclipse.timekeeper.domain.Activity activity) {
		run("stop activity", () -> service.stopActivity(new StopActivity(activity.id(), activity.version())));
	}

	public boolean open(Task task) {
		if (!isNative(task)) return false;
		editTask(task);
		return true;
	}

	private Action action(String text, Runnable runnable) {
		return new Action(text) {
			@Override public void run() { runnable.run(); }
		};
	}

	private void createProject() {
		ProjectEditorDialog dialog = new ProjectEditorDialog(shell, null);
		if (dialog.open() == Window.OK) run("create project", () -> service.createProject(new CreateProject(dialog.name())));
	}

	private void renameProject(Project project) {
		var current = service.project(projectId(project)).orElseThrow();
		ProjectEditorDialog dialog = new ProjectEditorDialog(shell, current.name());
		if (dialog.open() == Window.OK) run("rename project", () -> service.updateProject(
				new UpdateProject(current.id(), current.version(), dialog.name())));
	}

	private void deleteProject(Project project) {
		var current = service.project(projectId(project)).orElseThrow();
		if (!MessageDialog.openQuestion(shell, "Delete Timekeeper project",
				"Delete \"" + current.name() + "\"? A project containing tasks cannot be deleted.")) return;
		run("delete project", () -> service.deleteProject(new DeleteProject(current.id(), current.version())));
	}

	private void createTask(Project project, Task parent) {
		TaskEditorDialog dialog = new TaskEditorDialog(shell, null, parent != null);
		if (dialog.open() != Window.OK) return;
		run(parent == null ? "create task" : "create subtask", () -> service.createTask(new CreateTask(
				Optional.of(projectId(project)), parent == null ? Optional.empty() : Optional.of(taskId(parent)),
				dialog.summary(), dialog.url())));
	}

	private void editTask(Task task) {
		var current = domainTask(task);
		TaskEditorDialog dialog = new TaskEditorDialog(shell, current, current.parentTaskId().isPresent());
		if (dialog.open() != Window.OK) return;
		run("update task", () -> service.updateTask(new UpdateTask(current.id(), current.version(),
				current.projectId(), current.parentTaskId(), dialog.summary(), dialog.url())));
	}

	private void deleteTask(Task task) {
		var current = domainTask(task);
		if (!MessageDialog.openQuestion(shell, "Delete Timekeeper task",
				"Delete \"" + current.summary() + "\"? Tasks with subtasks or recorded activities cannot be deleted.")) return;
		run("delete task", () -> service.deleteTask(new DeleteTask(current.id(), current.version())));
	}

	private void link(Task task) {
		var current = domainTask(task);
		ExternalReferenceDialog dialog = new ExternalReferenceDialog(shell);
		if (dialog.open() != Window.OK) return;
		run("link external task", () -> service.linkExternalReference(
				new LinkExternalReference(current.id(), current.version(), dialog.reference())));
	}

	private void unlink(Task task) {
		var current = domainTask(task);
		ExternalTaskReference reference = chooseReference(current);
		if (reference == null) return;
		run("unlink external task", () -> service.unlinkExternalReference(
				new UnlinkExternalReference(current.id(), current.version(), reference.key())));
	}

	private ExternalTaskReference chooseReference(net.resheim.eclipse.timekeeper.domain.Task task) {
		if (task.externalReferences().size() == 1) return task.externalReferences().iterator().next();
		ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
			@Override public String getText(Object element) {
				ExternalTaskReference reference = (ExternalTaskReference) element;
				return reference.providerId() + ": " + reference.externalId();
			}
		});
		dialog.setTitle("Unlink External Task");
		dialog.setMessage("Select the external reference to remove:");
		dialog.setElements(task.externalReferences().toArray());
		return dialog.open() == Window.OK ? (ExternalTaskReference) dialog.getFirstResult() : null;
	}

	private void openUrl(Task task) {
		var current = domainTask(task);
		Optional<String> url = current.url().or(() -> current.externalReferences().stream()
				.map(ExternalTaskReference::externalUrl).flatMap(Optional::stream).findFirst());
		if (url.isEmpty()) {
			MessageDialog.openInformation(shell, "Timekeeper task", "This task has no URL.");
			return;
		}
		run("open URL", () -> {
			int style = IWorkbenchBrowserSupport.AS_EDITOR | IWorkbenchBrowserSupport.LOCATION_BAR
					| IWorkbenchBrowserSupport.NAVIGATION_BAR;
			PlatformUI.getWorkbench().getBrowserSupport()
					.createBrowser(style, "timekeeper.task", "Timekeeper Task", "Timekeeper task link")
					.openURL(URI.create(url.orElseThrow()).toURL());
		});
	}

	private ProjectId projectId(Project project) {
		return new ProjectId(UUID.fromString(project.getServiceId()));
	}

	private TaskId taskId(Task task) {
		return new TaskId(UUID.fromString(task.getId()));
	}

	private net.resheim.eclipse.timekeeper.domain.Task domainTask(Task task) {
		return service.task(taskId(task)).orElseThrow();
	}

	private void run(String operation, ThrowingAction action) {
		try {
			action.run();
			refresh.run();
		} catch (Exception e) {
			MessageDialog.openError(shell, "Could not " + operation,
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	@FunctionalInterface
	private interface ThrowingAction {
		void run() throws Exception;
	}
}
