/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.mylyn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskCategory;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
import org.eclipse.mylyn.internal.tasks.core.ITaskListChangeListener;
import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskContainerDelta;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.swt.widgets.Display;

import net.resheim.eclipse.timekeeper.client.RemoteFailure;
import net.resheim.eclipse.timekeeper.service.ServiceException;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateProject;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;

/**
 * Keeps the embedded Eclipse task list and the provider-neutral Timekeeper
 * model aligned for existing projections. New standalone records are projected
 * only when the user enables Task List integration; native editing does not
 * need Mylyn objects.
 */
@SuppressWarnings("restriction")
public final class MylynTaskSynchronizer implements ITaskListChangeListener, IPropertyChangeListener {

	private final TimekeeperService service;
	private final TaskList taskList;
	private final Display display;
	private final java.util.function.Supplier<MylynBackendScope> scopeSupplier;
	private volatile boolean running;
    private volatile Thread worker;
    private volatile Thread projectionThread;
    private volatile boolean busy;
	private boolean restoreAfterDeletionFailure;
	private volatile boolean applying;
	private final Set<TaskId> deletedTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final Set<ProjectId> deletedProjects = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private Set<TaskId> knownBackendTasks = Set.of();
	private Set<ProjectId> knownBackendProjects = Set.of();
	private Set<TaskId> knownProjectedTasks = Set.of();
	private volatile boolean scheduled;
	private volatile boolean backendAvailable;
	private final Runnable backendPoll = this::pollBackend;
	private final java.util.concurrent.atomic.AtomicBoolean mylynDirty = new java.util.concurrent.atomic.AtomicBoolean(true);
	private final java.util.concurrent.atomic.AtomicBoolean backendDirty = new java.util.concurrent.atomic.AtomicBoolean(true);
	private String lastFailure;

	public MylynTaskSynchronizer(TimekeeperService service, Display display) {
        this(service,display,MylynBackendScope::current);
    }

    public MylynTaskSynchronizer(TimekeeperService service,Display display,java.util.function.Supplier<MylynBackendScope> scopeSupplier) {
        this(service,display,scopeSupplier,TasksUiPlugin.getTaskList());
    }
    public MylynTaskSynchronizer(TimekeeperService service,Display display,java.util.function.Supplier<MylynBackendScope> scopeSupplier,TaskList taskList) {
        this.scopeSupplier=scopeSupplier;
		this.service = service;
		this.display = display;
		this.taskList = taskList;
	}

	public void start() {
		if (running)
			return;
		running = true;
		backendAvailable = false;
		mylynDirty.set(true);
		backendDirty.set(true);
		TimekeeperUiPlugin.getDefault().getPreferenceStore().addPropertyChangeListener(this);
		taskList.addChangeListener(this);
		display.timerExec(1000, backendPoll);
		// The embedded database (or a remote client) may still be connecting.
		// Keep the initial dirty flags until a successful client read.
	}

	public void stop() {
		if (!running)
			return;
		running = false;
		if (!display.isDisposed())
			display.timerExec(-1, backendPoll);
		TimekeeperUiPlugin.getDefault().getPreferenceStore().removePropertyChangeListener(this);

		taskList.removeChangeListener(this);
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (event.getProperty().equals(PreferenceConstants.PROJECT_NATIVE_TASKS))
			schedule(false, true);
	}

	@Override
	public void containersChanged(Set<TaskContainerDelta> deltas) {
		if (!running || (applying && (worker==null || Thread.currentThread()==worker || Thread.currentThread()==projectionThread)))
			return;
		for (TaskContainerDelta delta : deltas) {
			if (delta.getKind() != TaskContainerDelta.Kind.DELETED)
				continue;
			if (delta.getElement() instanceof LocalTask task)
				mappedTask(task).ifPresent(deletedTasks::add);
			if (delta.getElement() instanceof AbstractTaskCategory category)
				mappedProject(category).ifPresent(deletedProjects::add);
		}
		if (deltas.stream().anyMatch(delta -> !delta.isTransient()))
			schedule(true, false);
	}

	private void schedule(boolean fromMylyn, boolean fromBackend) {
        // Record external edits immediately, even when their scheduling callback
        // has not yet reached the UI event queue.
        if(fromMylyn) mylynDirty.set(true);
        if(fromBackend) backendDirty.set(true);
        if(Display.getCurrent()!=display) {if(!display.isDisposed()) display.asyncExec(()->schedule(fromMylyn,fromBackend));return;}
		if (scheduled || display.isDisposed())
			return;
		scheduled = true;
		display.asyncExec(this::reconcile);
	}

	private void reconcile() {
        if(TimekeeperUiPlugin.getDefault().isRemote() && Display.getCurrent()!=null) {
            scheduled=false;if(busy || !backendAvailable || !running) return;busy=true;
            TimekeeperUiPlugin.getDefault().runOperation("Synchronize Mylyn tasks",()->{worker=Thread.currentThread();try {reconcile();return null;}finally {worker=null;}},
                value->{busy=false;if(mylynDirty.get() || backendDirty.get()) schedule(false,false);},failure->{busy=false;mylynDirty.set(true);backendDirty.set(true);});return;
        }
		scheduled = false;
		if (!running || display.isDisposed() || !backendAvailable)
			return;
		boolean importMylyn = mylynDirty.getAndSet(false);
		boolean exportBackend = backendDirty.getAndSet(false);
		applying = true;
		boolean succeeded = false;
		try {
			if (importMylyn) {
				applyDeletions();
				synchronizeMylynToBackend();
			}
			if (exportBackend || importMylyn)
				synchronizeBackendToMylyn();
			succeeded = true;
			lastFailure = null;
		} catch (RuntimeException failure) {
			if(importMylyn) mylynDirty.set(true);
			if(exportBackend) backendDirty.set(true);
			String description = failure.getClass().getName() + ": " + failure.getMessage();
			if (!description.equals(lastFailure)) {
				TimekeeperUiPlugin.getDefault().getLog().log(new Status(IStatus.WARNING, MylynIntegration.PLUGIN_ID,
						"Could not synchronize Mylyn through the Timekeeper client; retrying", failure));
				lastFailure = description;
			}
            if(TimekeeperUiPlugin.getDefault().isRemote()) throw failure;
		} finally {
			applying = false;
			if (succeeded && (mylynDirty.get() || backendDirty.get()))
				schedule(false, false);
		}
	}

	private List<Task> lastTasks = List.of();
	private List<Project> lastProjects = List.of();

	private void pollBackend() {
		if (!running || display.isDisposed())
			return;
		try {
            if(!TimekeeperUiPlugin.getDefault().isBackendReady()) {backendAvailable=false;return;}
			var tasks = service.tasks();
			var projects = service.projects();
			backendAvailable = true;
			if (mylynDirty.get() || backendDirty.get() || !tasks.equals(lastTasks) || !projects.equals(lastProjects)) {
				lastTasks = tasks;
				lastProjects = projects;
				schedule(false, true);
			}
		} catch (RuntimeException unavailable) {
			backendAvailable = false;
			// The client may be temporarily disconnected; retry on the next poll.
		} finally {
			if (running && !display.isDisposed())
				display.timerExec(1000, backendPoll);
		}
	}

	private void applyDeletions() {
		for (TaskId id : Set.copyOf(deletedTasks)) {
			service.task(id).filter(task -> task.externalReferences().isEmpty()).ifPresent(task -> {
				try {
					service.deleteTask(
							new net.resheim.eclipse.timekeeper.service.Commands.DeleteTask(id, task.version()));
				} catch (RuntimeException failure) {
					// Recorded history is preserved; the projection is restored below.
					handleDeletionFailure(failure);
				}
			});
            deletedTasks.remove(id);
		}
		for (ProjectId id : Set.copyOf(deletedProjects)) {
			service.project(id).ifPresent(project -> {
				try {
					service.deleteProject(
							new net.resheim.eclipse.timekeeper.service.Commands.DeleteProject(id, project.version()));
					scope().forget(id);
				} catch (RuntimeException failure) {
					handleDeletionFailure(failure);
				}
			});
            deletedProjects.remove(id);
		}
		if (restoreAfterDeletionFailure) {
			restoreAfterDeletionFailure = false;
			// Repair the hierarchy before importing changes caused by Mylyn deletion.
			synchronizeBackendToMylyn();
		}
	}

    private void handleDeletionFailure(RuntimeException failure) {
        if (failure instanceof ServiceException
                || failure instanceof RemoteFailure remote && (remote.kind() == RemoteFailure.Kind.CONFLICT
                    || remote.kind() == RemoteFailure.Kind.FORBIDDEN)) {
            showDeletionFailure(failure);
            return;
        }
        // An unknown transport outcome must retain the pending deletion for reconciliation.
        throw failure;
    }

	private void showDeletionFailure(RuntimeException failure) {
		restoreAfterDeletionFailure = true;
        Runnable error=()->org.eclipse.jface.dialogs.MessageDialog.openError(display.getActiveShell(),"Could not delete Timekeeper record",failure.getMessage());
        if(Display.getCurrent()==display) error.run();else display.asyncExec(error);
	}

	private void synchronizeMylynToBackend() {
		Map<String, Project> projects = new HashMap<>();
        for (AbstractTaskCategory category : taskList.getTaskCategories()) {
            if(scope().maySynchronize(category) && mappedProject(category).map(id -> service.project(id).isPresent()).orElse(true))
                projects.put(category.getHandleIdentifier(),ensureProject(category));
        }

		Map<ITask, Task> synchronizedTasks = new HashMap<>();
		List<AbstractTask> localTasks = taskList.getAllTasks().stream().filter(LocalTask.class::isInstance)
                .filter(task -> scope().maySynchronize(task))
                // A UUID denotes an existing projection, not a request to create
                // another backend record when that UUID has disappeared.
                .filter(task -> mappedTask(task).map(id -> service.task(id).isPresent()).orElse(true))
				.sorted(Comparator.comparingInt(this::depth)).toList();
		for (AbstractTask task : localTasks)
			ensureTask(task, projects, synchronizedTasks);
	}

	private Project ensureProject(AbstractTaskCategory category) {
		Optional<ProjectId> mappedId = mappedProject(category);
        Project project;
        if(mappedId.isPresent()) project=service.project(mappedId.orElseThrow()).orElseThrow(()->ServiceException.notFound("project",mappedId.orElseThrow()));
        else project=service.projects().stream().filter(candidate->candidate.name().equals(category.getSummary())).findFirst()
            .orElseGet(()->service.createProject(new CreateProject(category.getSummary())));
		remember(project.id(), category);
		if (!project.name().equals(category.getSummary()) && !scope().projectProjectionUnchanged(project.id(),category.getSummary())) {
            project = service.updateProject(new UpdateProject(project.id(), scope().projectProjectionVersion(project), category.getSummary()));
		}
        if(project.name().equals(category.getSummary())) scope().rememberProjection(project);
		return project;
	}

	private Task ensureTask(AbstractTask mylynTask, Map<String, Project> projects, Map<ITask, Task> synchronizedTasks) {
		Task known = synchronizedTasks.get(mylynTask);
		if (known != null)
			return known;

		Optional<Task> parent = parentTask(mylynTask).filter(LocalTask.class::isInstance).filter(task -> scope().maySynchronize(task))
				.map(value -> ensureTask(value, projects, synchronizedTasks));
		Optional<ProjectId> requestedProjectId = parent.flatMap(Task::projectId)
				.or(() -> category(mylynTask).filter(value -> scope().maySynchronize(value)).map(
						value -> projects.computeIfAbsent(value.getHandleIdentifier(), ignored -> ensureProject(value)))
						.map(Project::id));
		ExternalTaskReference reference = externalReference(mylynTask);
        Optional<TaskId> mappedId=mappedTask(mylynTask);
        Task current=mappedId.flatMap(service::task).or(()->service.findTask(reference.key())).orElseGet(()->{
            if(mappedId.isPresent()) throw ServiceException.notFound("task",mappedId.orElseThrow());
            return service.createTask(new CreateTask(requestedProjectId,parent.map(Task::id),mylynTask.getSummary(),Optional.ofNullable(mylynTask.getUrl())));
        });
		Optional<ProjectId> projectId = requestedProjectId;
		if (projectId.isEmpty())
			projectId = current.projectId();

		Optional<TaskId> parentId = parent.map(Task::id);
		Optional<String> url = Optional.ofNullable(mylynTask.getUrl()).filter(value -> !value.isBlank());
        String sourceState=scope().taskState(projectId,parentId,mylynTask.getSummary(),url);
		if ((!current.projectId().equals(projectId) || !current.parentTaskId().equals(parentId)
				|| !current.summary().equals(mylynTask.getSummary()) || !current.url().equals(url))
                && !scope().taskProjectionUnchanged(mylynTask,sourceState)) {
			try {
                current = service.updateTask(new UpdateTask(current.id(), scope().taskProjectionVersion(mylynTask,current), projectId, parentId,
						mylynTask.getSummary(), url));
			} catch (RuntimeException failure) {
				throw new IllegalStateException("Could not synchronize Mylyn task '" + mylynTask.getSummary() + "' ("
						+ current.id() + ") from project " + current.projectId() + " to " + projectId + " and parent "
						+ parentId, failure);
			}
		}
		scope().remember(mylynTask,current.id(),current.externalReferences().isEmpty());
        // A successful import is a new baseline even if a later record aborts this pass.
        // Do not mark an unchanged, stale projection as matching newer backend fields.
        if(sourceState.equals(scope().taskState(current.projectId(),current.parentTaskId(),current.summary(),current.url())))
            scope().rememberProjection(mylynTask,current);
		synchronizedTasks.put(mylynTask, current);
		return current;
	}

    private void synchronizeBackendToMylyn() {
        String backendScope=scope().scope();
        // Remote reads stay on the operation worker. Task List objects and their
        // acknowledged baselines are changed atomically with respect to UI edits.
        var tasks=List.copyOf(service.tasks());
        var projects=List.copyOf(service.projects());
        Runnable projection=()->{
            if(!running || display.isDisposed()) return;
            var current=scopeSupplier.get();
            if(current==null || !backendScope.equals(current.scope())) {backendDirty.set(true);return;}
            // A user/provider edit arrived after this pass consumed its input.
            // Import that edit next, rather than replaying an older snapshot over it.
            if(mylynDirty.get()) return;
            projectionThread=Thread.currentThread();
            try {applyBackendToMylyn(tasks,projects);}
            finally {projectionThread=null;}
        };
        if(Display.getCurrent()==display) projection.run();
        else if(!display.isDisposed()) display.syncExec(projection);
    }

	private void applyBackendToMylyn(List<Task> backendTasks,List<Project> backendProjects) {
		Map<TaskId, Task> domainTasks = new HashMap<>();
		backendTasks.forEach(task -> domainTasks.put(task.id(), task));
		for (AbstractTask projected : List.copyOf(taskList.getAllTasks())) {
			if (!(projected instanceof LocalTask))
				continue;
			mappedTask(projected).filter(id -> (scope().ownsProjection(projected) || knownBackendTasks.contains(id)) && !domainTasks.containsKey(id))
					.ifPresent(id -> taskList.deleteTask(projected));
		}
		Set<ProjectId> projectIds = backendProjects.stream().map(Project::id)
				.collect(java.util.stream.Collectors.toSet());
		for (AbstractTaskCategory category : Set.copyOf(taskList.getTaskCategories())) {
			mappedProject(category).filter(id -> (scope().ownsCategory(category) || knownBackendProjects.contains(id)) && !projectIds.contains(id))
					.ifPresent(id -> {
						taskList.deleteCategory(category);
						scope().forget(id);
					});
		}

		Map<TaskId, AbstractTask> mylynTasks = indexedMylynTasks();
		boolean includeNew = TimekeeperUiPlugin.getDefault().getPreferenceStore()
				.getBoolean(PreferenceConstants.PROJECT_NATIVE_TASKS);
		Set<TaskId> projected = domainTasks.values().stream().filter(this::isLocalTask)
				.filter(task -> task.externalReferences().isEmpty()
						? includeNew || mylynTasks.containsKey(task.id()) || knownProjectedTasks.contains(task.id())
						: mylynTasks.containsKey(task.id()))
				.map(Task::id).collect(java.util.stream.Collectors.toSet());
		// A projected child needs its native ancestors to preserve the actual
		// hierarchy.
		for (TaskId id : List.copyOf(projected)) {
			Task current = domainTasks.get(id);
			Set<TaskId> visited = new HashSet<>();
			while (current != null && current.parentTaskId().isPresent() && visited.add(current.id())) {
				TaskId parentId = current.parentTaskId().orElseThrow();
				projected.add(parentId);
				current = domainTasks.get(parentId);
			}
		}
		Set<ProjectId> nativeProjects = new HashSet<>();
		if (includeNew)
			backendProjects.stream().map(Project::id).forEach(nativeProjects::add);
		backendProjects.stream().filter(project -> scope().category(project.id()) != null)
				.map(Project::id).forEach(nativeProjects::add);
		domainTasks.values().stream().filter(task -> projected.contains(task.id())).map(Task::projectId)
				.flatMap(Optional::stream).forEach(nativeProjects::add);
		Map<ProjectId, AbstractTaskCategory> categories = new HashMap<>();
		for (Project project : backendProjects) {
			if (nativeProjects.contains(project.id()))
				categories.put(project.id(), ensureCategory(project));
		}

		List<Task> ordered = new ArrayList<>(domainTasks.values());
		ordered.sort(Comparator.comparingInt(task -> depth(task, domainTasks)));
		for (Task task : ordered) {
			if (!projected.contains(task.id()))
				continue;
			AbstractTask mylynTask = mylynTasks.get(task.id());
			if (mylynTask == null) {
				mylynTask = findByReference(task).orElse(null);
			}
			if (mylynTask == null) {
				mylynTask = new LocalTask(String.valueOf(taskList.getNextLocalTaskId()), task.summary());
			}
			scope().remember(mylynTask,task.id(),task.externalReferences().isEmpty());
			mylynTask.setSummary(task.summary());
			mylynTask.setUrl(task.url().orElse(null));
			AbstractTaskContainer parent = task.parentTaskId().map(mylynTasks::get)
					.<AbstractTaskContainer>map(value -> value)
					.orElseGet(() -> task.projectId().map(categories::get).orElse(null));
            scope().rememberProjection(mylynTask,task);
			// A null parent moves a local task to Mylyn's unmatched container as well.
			if (!taskList.addTask(mylynTask, parent))
				taskList.notifyElementChanged(mylynTask);
			mylynTasks.put(task.id(), mylynTask);
		}
		knownBackendTasks = Set.copyOf(domainTasks.keySet());
		knownBackendProjects = Set.copyOf(projectIds);
		knownProjectedTasks = Set.copyOf(mylynTasks.keySet());
	}

	private boolean isLocalTask(Task task) {
		return task.externalReferences().isEmpty() || task.externalReferences().stream()
				.anyMatch(reference -> MylynMetadata.KIND_LOCAL.equals(reference.providerId()));
	}

	private AbstractTaskCategory ensureCategory(Project project) {
		String handle = scope().category(project.id());
		AbstractTaskCategory category = handle == null ? null : taskList.getContainerForHandle(handle);
		if (category == null) {
			category = taskList.getTaskCategories().stream()
                    .filter(candidate -> scope().maySynchronize(candidate))
                    .filter(candidate -> mappedProject(candidate).map(project.id()::equals).orElse(true))
					.filter(candidate -> candidate.getSummary().equals(project.name())).findFirst().orElse(null);
		}
		if (category == null) {
			category = new TaskCategory(scope().categoryHandle(project.id()), project.name());
			taskList.addCategory((TaskCategory) category);
		} else if (!category.getSummary().equals(project.name())) {
			taskList.renameContainer(category, project.name());
		}
		remember(project.id(), category);
        scope().rememberProjection(project);
		return category;
	}

	private Map<TaskId, AbstractTask> indexedMylynTasks() {
		Map<TaskId, AbstractTask> result = new HashMap<>();
		for (AbstractTask task : taskList.getAllTasks())
			mappedTask(task).ifPresent(id -> result.put(id, task));
		return result;
	}

	private Optional<AbstractTask> findByReference(Task task) {
		for (AbstractTask candidate : taskList.getAllTasks()) {
            if(scope().foreignProjection(candidate)) continue;
			ExternalTaskReferenceKey key = externalReference(candidate).key();
			if (task.externalReferences().stream().anyMatch(reference -> reference.key().equals(key))) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	private ExternalTaskReference externalReference(ITask task) {
		String provider = task.getConnectorKind() == null ? "mylyn" : task.getConnectorKind();
		return new ExternalTaskReference(provider, MylynMetadata.getRepositoryUrl(task), task.getTaskId(),
				task.getUrl());
	}

    private MylynBackendScope scope() {
        var scope=scopeSupplier.get();if(scope==null) throw new IllegalStateException("The Timekeeper backend is still connecting.");return scope;
    }
    private Optional<TaskId> mappedTask(ITask task) {
        var scope=scopeSupplier.get();return scope==null ? Optional.empty() : scope.task(task);
    }
    private Optional<ProjectId> mappedProject(AbstractTaskCategory category) {
        var scope=scopeSupplier.get();return scope==null ? Optional.empty() : scope.project(category);
    }
    private void remember(ProjectId projectId,AbstractTaskCategory category) {scope().remember(projectId,category);}

	private Optional<AbstractTask> parentTask(ITask task) {
		if (!(task instanceof AbstractTask concrete))
			return Optional.empty();
		return concrete.getParentContainers().stream().filter(AbstractTask.class::isInstance)
				.map(AbstractTask.class::cast).findFirst();
	}

	private Optional<AbstractTaskCategory> category(ITask task) {
		Set<ITask> visited = new HashSet<>();
		ITask current = task;
		while (current instanceof AbstractTask concrete && visited.add(current)) {
			Optional<AbstractTaskCategory> category = concrete.getParentContainers().stream()
					.filter(AbstractTaskCategory.class::isInstance).map(AbstractTaskCategory.class::cast).findFirst();
			if (category.isPresent() && category.get() instanceof TaskCategory)
				return category;
			current = concrete.getParentContainers().stream().filter(AbstractTask.class::isInstance)
					.map(AbstractTask.class::cast).findFirst().orElse(null);
		}
		return Optional.empty();
	}

	private int depth(AbstractTask task) {
		int depth = 0;
		Set<ITask> visited = new HashSet<>();
		ITask current = task;
		while (current != null && visited.add(current)) {
			current = parentTask(current).orElse(null);
			if (current != null)
				depth++;
		}
		return depth;
	}

	private int depth(Task task, Map<TaskId, Task> tasks) {
		int depth = 0;
		Set<TaskId> visited = new HashSet<>();
		Task current = task;
		while (current.parentTaskId().isPresent() && visited.add(current.id())) {
			current = tasks.get(current.parentTaskId().orElseThrow());
			if (current == null)
				break;
			depth++;
		}
		return depth;
	}
}
