package net.resheim.eclipse.timekeeper.mylyn;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.ui.TaskElementLabelProvider;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.mylyn.tasks.ui.TasksUiUtil;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.LinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.StartActivity;
import net.resheim.eclipse.timekeeper.service.Commands.StopActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.service.ServiceException;
import net.resheim.eclipse.timekeeper.domain.FailureCode;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.tasks.TaskIntegration;

/**
 * Optional adapter. Every read and write of Timekeeper data crosses the client
 * boundary.
 */
@SuppressWarnings("restriction")
public final class MylynIntegration extends AbstractUIPlugin implements IStartup, TaskIntegration {
	public static final String PLUGIN_ID = "net.resheim.eclipse.timekeeper.mylyn";
	private static MylynIntegration instance;
	private BundleContext context;
	private ServiceRegistration<TaskIntegration> registration;
	private MylynTaskSynchronizer synchronizer;
	private TaskElementLabelProvider labels;

	public static MylynIntegration getDefault() {
		return instance;
	}

	public MylynTaskSynchronizer synchronizer() {
		ensureStarted();
		return synchronizer;
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		this.context = context;
		instance = this;
	}

	@Override
	public void earlyStartup() {
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			if (instance != null)
				instance.ensureStarted();
		});
	}

	private void ensureStarted() {
		if (registration != null)
			return;
		var ui = TimekeeperUiPlugin.getDefault();
		if (ui == null)
			return;
		synchronizer = new MylynTaskSynchronizer(ui.getTimekeeperService(), PlatformUI.getWorkbench().getDisplay());
		labels = new TaskElementLabelProvider();
		registration = context.registerService(TaskIntegration.class, this, null);
		synchronizer.start();
	}

	private TimekeeperService service() {
		ensureStarted();
		return TimekeeperUiPlugin.getDefault().getTimekeeperService();
	}

	/**
	 * Reconnection is resolved by stable provider key, then by the native
	 * projection UUID.
	 */
	public Task resolve(ITask external) {
		var service = service();
		var reference = reference(external);
		Optional<Task> found = service.findTask(reference.key()).or(() -> mapped(external).flatMap(service::task));
		if (found.isPresent()) {
			Task task = found.orElseThrow();
			// Native Task List projections carry no provider identity of their own.
			if (!task.externalReferences().isEmpty()) {
				if (!task.externalReferences().contains(reference)) {
					task = service
							.linkExternalReference(new LinkExternalReference(task.id(), task.version(), reference));
				}
				var url = Optional.ofNullable(external.getUrl()).filter(value -> !value.isBlank());
				if (!task.summary().equals(external.getSummary()) || !task.url().equals(url)) {
					task = service.updateTask(new UpdateTask(task.id(), task.version(), task.projectId(),
							task.parentTaskId(), external.getSummary(), url));
				}
			}
			external.setAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID, task.id().value().toString());
			return task;
		}
		String name = MylynMetadata.getMylynProjectName(external);
		Project project = service.projects().stream().filter(value -> value.name().equals(name)).findFirst()
				.orElseGet(() -> service.createProject(new CreateProject(name)));
		Task created;
		try {
			created = service.createTask(new CreateTask(Optional.of(project.id()), Optional.empty(),
					external.getSummary(), Optional.ofNullable(external.getUrl()), Set.of(reference)));
		} catch (ServiceException collision) {
			if (collision.code() != FailureCode.CONFLICT)
				throw collision;
			// Another client may have linked the same provider key concurrently.
			created = service.findTask(reference.key()).orElseThrow(() -> collision);
		}
		external.setAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID, created.id().value().toString());
		return created;
	}

	public void activated(ITask external) {
        var ui=TimekeeperUiPlugin.getDefault();
        if(ui.isRemote() && org.eclipse.swt.widgets.Display.getCurrent()!=null) {
            ui.runOperation("Track Mylyn task",()->{activated(external);return null;},value->{},this::reportFailure);return;
        }
		Task task = resolve(external);
		var service = service();
		var active = service.activeActivity(OwnerId.LOCAL);
		if (active.filter(activity -> activity.taskId().equals(task.id())).isPresent())
			return;
        if(ui.isRemote()) ui.activateRemoteInWorker(task.id());
        else {active.ifPresent(activity -> service.stopActivity(new StopActivity(activity.id(),activity.version())));service.startActivity(new StartActivity(task.id(),OwnerId.LOCAL,"",Set.of()));}
	}

	public void deactivated(ITask external) {
        var ui=TimekeeperUiPlugin.getDefault();
        if(ui.isRemote() && org.eclipse.swt.widgets.Display.getCurrent()!=null) {
            ui.runOperation("Stop Mylyn task",()->{deactivated(external);return null;},value->{},this::reportFailure);return;
        }
		// A late/duplicate deactivation must neither create a task nor stop its
		// successor.
		var service = service();
		Optional<TaskId> id = service.findTask(reference(external).key()).map(Task::id).or(() -> mapped(external));
		service.activeActivity(OwnerId.LOCAL).filter(activity -> id.filter(activity.taskId()::equals).isPresent())
				.ifPresent(activity -> service.stopActivity(new StopActivity(activity.id(), activity.version())));
	}

	public Optional<ITask> linked(Task task) {
		return TasksUiPlugin.getTaskList().getAllTasks().stream()
				.filter(candidate -> mapped(candidate).filter(task.id()::equals).isPresent() || task
						.externalReferences().stream().anyMatch(ref -> ref.key().equals(reference(candidate).key())))
				.map(ITask.class::cast).findFirst();
	}

	private Optional<TaskId> mapped(ITask task) {
		try {
			return Optional.ofNullable(task.getAttribute(MylynMetadata.ATTR_TIMEKEEPER_TASK_ID)).map(UUID::fromString)
					.map(TaskId::new);
		} catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
	}

	private ExternalTaskReference reference(ITask task) {
		return new ExternalTaskReference(task.getConnectorKind() == null ? "mylyn" : task.getConnectorKind(),
				MylynMetadata.getRepositoryUrl(task), task.getTaskId(), task.getUrl());
	}

	@Override
	public Optional<String> displayId(Task task) {
		if (task.externalReferences().stream().noneMatch(ref -> MylynMetadata.KIND_JIRA.equals(ref.providerId())))
			return Optional.empty();
		return linked(task).filter(external -> MylynMetadata.KIND_JIRA.equals(external.getConnectorKind()))
				.flatMap(external -> Optional.ofNullable(external.getTaskKey())
						.filter(key -> !key.isBlank() && !key.equals(external.getTaskId()))
						.or(() -> Optional.ofNullable(external.getUrl()).flatMap(this::jiraKeyFromUrl)))
				.or(() -> task.externalReferences().stream()
						.filter(ref -> MylynMetadata.KIND_JIRA.equals(ref.providerId()))
						.flatMap(ref -> ref.externalUrl().stream()).sorted().map(this::jiraKeyFromUrl)
						.flatMap(Optional::stream).findFirst());
	}

	private Optional<String> jiraKeyFromUrl(String url) {
		try {
			String path = java.net.URI.create(url).getPath();
			if (path == null)
				return Optional.empty();
			int index = path.lastIndexOf("/browse/");
			if (index < 0)
				return Optional.empty();
			String key = path.substring(index + "/browse/".length());
			return key.isBlank() || key.contains("/") ? Optional.empty() : Optional.of(key);
		} catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
	}

	@Override
	public boolean isLinked(Task task) {
		return linked(task).isPresent();
	}

	@Override
	public boolean isActive(Task task) {
		return linked(task).map(ITask::isActive).orElse(false);
	}

	@Override
	public Image image(Task task) {
		ensureStarted();
		return linked(task).map(labels::getImage).orElse(null);
	}

	@Override
	public void open(Task task) {
		linked(task).ifPresent(TasksUiUtil::openTask);
	}

	@Override
	public void activate(Task task) {
		linked(task).ifPresent(TasksUi.getTaskActivityManager()::activateTask);
	}

	@Override
	public void deactivate(TaskId taskId) {
		service().task(taskId).flatMap(this::linked).filter(ITask::isActive)
				.ifPresent(TasksUi.getTaskActivityManager()::deactivateTask);
	}

	@Override
	public void deactivateActiveTask() {
		TasksUi.getTaskActivityManager().deactivateActiveTask();
	}

	public void reportFailure(RuntimeException failure) {
        if(TimekeeperUiPlugin.getDefault().isRemote() && org.eclipse.swt.widgets.Display.getCurrent()!=null) org.eclipse.jface.dialogs.MessageDialog.openError(org.eclipse.swt.widgets.Display.getCurrent().getActiveShell(),"Mylyn tracking failed",failure.getMessage());
		getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, "Mylyn tracking failed", failure));
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		Runnable cleanup = () -> {
			if (synchronizer != null)
				synchronizer.stop();
			if (labels != null)
				labels.dispose();
		};
		var display = PlatformUI.getWorkbench().getDisplay();
		if (!display.isDisposed())
			display.syncExec(cleanup);
		else if (synchronizer != null)
			synchronizer.stop();
		if (registration != null)
			registration.unregister();
		instance = null;
		super.stop(context);
	}
}
