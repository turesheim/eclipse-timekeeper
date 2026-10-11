/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.tasks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.InstanceScope;
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
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import net.resheim.eclipse.timekeeper.db.DatabaseChangeListener;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
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

/**
 * Keeps the embedded Eclipse task list and the provider-neutral Timekeeper model
 * aligned. Mylyn remains the editing UI: a Timekeeper project is represented by
 * a task category and an unconnected Timekeeper task by a Mylyn local task.
 */
@SuppressWarnings("restriction")
public final class MylynTaskSynchronizer implements DatabaseChangeListener, ITaskListChangeListener {
	private static final String PROJECT_HANDLE_PREFIX = "timekeeper-project-";
	private static final String PROJECT_MAPPING_PREFIX = "mylyn-project-";

	private final TimekeeperService service;
	private final TaskList taskList;
	private final Display display;
	private final Preferences mappings;
	private boolean applying;
	private boolean scheduled;
	private boolean mylynDirty = true;
	private boolean backendDirty = true;

	public MylynTaskSynchronizer(TimekeeperService service, Display display) {
		this.service = service;
		this.display = display;
		this.taskList = TasksUiPlugin.getTaskList();
		this.mappings = InstanceScope.INSTANCE.getNode(TimekeeperUiPlugin.PLUGIN_ID).node("mylyn-projects");
	}

	public void start() {
		taskList.addChangeListener(this);
		TimekeeperPlugin.getDefault().addListener(this);
		schedule(true, true);
	}

	public void stop() {
		TimekeeperPlugin.getDefault().removeListener(this);
		taskList.removeChangeListener(this);
	}

	@Override
	public void databaseStateChanged() {
		schedule(false, true);
	}

	@Override
	public void containersChanged(Set<TaskContainerDelta> deltas) {
		if (!applying && deltas.stream().anyMatch(delta -> !delta.isTransient())) schedule(true, false);
	}

	private void schedule(boolean fromMylyn, boolean fromBackend) {
		mylynDirty |= fromMylyn;
		backendDirty |= fromBackend;
		if (scheduled || display.isDisposed()) return;
		scheduled = true;
		display.asyncExec(this::reconcile);
	}

	private void reconcile() {
		scheduled = false;
		if (display.isDisposed() || !TimekeeperPlugin.getDefault().isReady()) return;
		boolean importMylyn = mylynDirty;
		boolean exportBackend = backendDirty;
		mylynDirty = false;
		backendDirty = false;
		applying = true;
		try {
			if (importMylyn) synchronizeMylynToBackend();
			if (exportBackend || importMylyn) synchronizeBackendToMylyn();
		} catch (RuntimeException failure) {
			TimekeeperUiPlugin.getDefault().getLog().log(new Status(IStatus.ERROR,
					TimekeeperUiPlugin.PLUGIN_ID, "Could not synchronize Timekeeper and Mylyn tasks", failure));
		} finally {
			applying = false;
			if (mylynDirty || backendDirty) schedule(false, false);
		}
	}

	private void synchronizeMylynToBackend() {
		Map<String, Project> projects = new HashMap<>();
		for (AbstractTaskCategory category : taskList.getTaskCategories()) {
			projects.put(category.getHandleIdentifier(), ensureProject(category));
		}

		Map<ITask, Task> synchronizedTasks = new HashMap<>();
		List<AbstractTask> localTasks = taskList.getAllTasks().stream()
				.filter(LocalTask.class::isInstance)
				.sorted(Comparator.comparingInt(this::depth))
				.toList();
		for (AbstractTask task : localTasks) ensureTask(task, projects, synchronizedTasks);
	}

	private Project ensureProject(AbstractTaskCategory category) {
		Optional<ProjectId> mappedId = mappedProject(category);
		Project project = mappedId.flatMap(service::project).orElseGet(() -> service.projects().stream()
				.filter(candidate -> candidate.name().equals(category.getSummary())).findFirst()
				.orElseGet(() -> service.createProject(new CreateProject(category.getSummary()))));
		remember(project.id(), category);
		if (!project.name().equals(category.getSummary())) {
			project = service.updateProject(new UpdateProject(project.id(), project.version(), category.getSummary()));
		}
		return project;
	}

	private Task ensureTask(AbstractTask mylynTask, Map<String, Project> projects,
			Map<ITask, Task> synchronizedTasks) {
		Task known = synchronizedTasks.get(mylynTask);
		if (known != null) return known;

		Optional<Task> parent = parentTask(mylynTask)
				.filter(LocalTask.class::isInstance)
				.map(value -> ensureTask(value, projects, synchronizedTasks));
		Optional<ProjectId> requestedProjectId = parent.flatMap(Task::projectId).or(() -> category(mylynTask)
				.map(value -> projects.computeIfAbsent(value.getHandleIdentifier(), ignored -> ensureProject(value)))
				.map(Project::id));
		ExternalTaskReference reference = externalReference(mylynTask);
		Task current = mappedTask(mylynTask).flatMap(service::task)
				.or(() -> service.findTask(reference.key()))
				.orElseGet(() -> service.createTask(new CreateTask(requestedProjectId, parent.map(Task::id),
						mylynTask.getSummary(), Optional.ofNullable(mylynTask.getUrl()))));
		Optional<ProjectId> projectId = requestedProjectId;
		if (projectId.isEmpty()) projectId = current.projectId();

		Optional<TaskId> parentId = parent.map(Task::id);
		Optional<String> url = Optional.ofNullable(mylynTask.getUrl()).filter(value -> !value.isBlank());
		if (!current.projectId().equals(projectId) || !current.parentTaskId().equals(parentId)
				|| !current.summary().equals(mylynTask.getSummary()) || !current.url().equals(url)) {
			try {
				current = service.updateTask(new UpdateTask(current.id(), current.version(), projectId, parentId,
						mylynTask.getSummary(), url));
			} catch (RuntimeException failure) {
				throw new IllegalStateException("Could not synchronize Mylyn task '" + mylynTask.getSummary()
						+ "' (" + current.id() + ") from project " + current.projectId()
						+ " to " + projectId + " and parent " + parentId, failure);
			}
		}
		mylynTask.setAttribute(TimekeeperPlugin.ATTR_TIMEKEEPER_TASK_ID, current.id().value().toString());
		synchronizedTasks.put(mylynTask, current);
		return current;
	}

	private void synchronizeBackendToMylyn() {
		Map<TaskId, Task> domainTasks = new HashMap<>();
		service.tasks().forEach(task -> domainTasks.put(task.id(), task));
		Set<ProjectId> nativeProjects = new HashSet<>();
		TimekeeperPlugin.getProjects().map(net.resheim.eclipse.timekeeper.db.model.Project::getServiceId)
				.filter(java.util.Objects::nonNull).map(UUID::fromString).map(ProjectId::new)
				.forEach(nativeProjects::add);
		domainTasks.values().stream().filter(this::isLocalTask).map(Task::projectId)
				.flatMap(Optional::stream).forEach(nativeProjects::add);
		Map<ProjectId, AbstractTaskCategory> categories = new HashMap<>();
		for (Project project : service.projects()) {
			if (nativeProjects.contains(project.id())) categories.put(project.id(), ensureCategory(project));
		}

		Map<TaskId, AbstractTask> mylynTasks = indexedMylynTasks();
		List<Task> ordered = new ArrayList<>(domainTasks.values());
		ordered.sort(Comparator.comparingInt(task -> depth(task, domainTasks)));
		for (Task task : ordered) {
			if (!isLocalTask(task)) continue;
			AbstractTask mylynTask = mylynTasks.get(task.id());
			if (mylynTask == null) {
				mylynTask = findByReference(task).orElse(null);
			}
			if (mylynTask == null) {
				mylynTask = new LocalTask(String.valueOf(taskList.getNextLocalTaskId()), task.summary());
			}
			mylynTask.setAttribute(TimekeeperPlugin.ATTR_TIMEKEEPER_TASK_ID, task.id().value().toString());
			mylynTask.setSummary(task.summary());
			mylynTask.setUrl(task.url().orElse(null));
			AbstractTaskContainer parent = task.parentTaskId().map(mylynTasks::get)
					.<AbstractTaskContainer>map(value -> value)
					.orElseGet(() -> task.projectId().map(categories::get).orElse(null));
			if (taskList.getTask(mylynTask.getRepositoryUrl(), mylynTask.getTaskId()) == null) {
				taskList.addTask(mylynTask, parent);
			} else if (parent != null && !mylynTask.getParentContainers().contains(parent)) {
				taskList.addTask(mylynTask, parent);
			} else {
				taskList.notifyElementChanged(mylynTask);
			}
			mylynTasks.put(task.id(), mylynTask);
		}
	}

	private boolean isLocalTask(Task task) {
		return task.externalReferences().isEmpty() || task.externalReferences().stream()
				.anyMatch(reference -> TimekeeperPlugin.KIND_LOCAL.equals(reference.providerId()));
	}

	private AbstractTaskCategory ensureCategory(Project project) {
		String handle = mappings.get(mappingKey(project.id()), null);
		AbstractTaskCategory category = handle == null ? null : taskList.getContainerForHandle(handle);
		if (category == null) {
			category = taskList.getTaskCategories().stream()
					.filter(candidate -> candidate.getSummary().equals(project.name()))
					.findFirst().orElse(null);
		}
		if (category == null) {
			category = new TaskCategory(PROJECT_HANDLE_PREFIX + project.id().value(), project.name());
			taskList.addCategory((TaskCategory) category);
		} else if (!category.getSummary().equals(project.name())) {
			taskList.renameContainer(category, project.name());
		}
		remember(project.id(), category);
		return category;
	}

	private Map<TaskId, AbstractTask> indexedMylynTasks() {
		Map<TaskId, AbstractTask> result = new HashMap<>();
		for (AbstractTask task : taskList.getAllTasks()) mappedTask(task).ifPresent(id -> result.put(id, task));
		return result;
	}

	private Optional<AbstractTask> findByReference(Task task) {
		for (AbstractTask candidate : taskList.getAllTasks()) {
			ExternalTaskReferenceKey key = externalReference(candidate).key();
			if (task.externalReferences().stream().anyMatch(reference -> reference.key().equals(key))) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	private ExternalTaskReference externalReference(ITask task) {
		String provider = task.getConnectorKind() == null ? "mylyn" : task.getConnectorKind();
		return new ExternalTaskReference(provider, TimekeeperPlugin.getRepositoryUrl(task), task.getTaskId(), task.getUrl());
	}

	private Optional<TaskId> mappedTask(ITask task) {
		String value = task.getAttribute(TimekeeperPlugin.ATTR_TIMEKEEPER_TASK_ID);
		if (value == null) return Optional.empty();
		try {
			return Optional.of(new TaskId(UUID.fromString(value)));
		} catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
	}

	private Optional<ProjectId> mappedProject(AbstractTaskCategory category) {
		try {
			for (String key : mappings.keys()) {
				if (category.getHandleIdentifier().equals(mappings.get(key, null))
						&& key.startsWith(PROJECT_MAPPING_PREFIX)) {
					return Optional.of(new ProjectId(UUID.fromString(key.substring(PROJECT_MAPPING_PREFIX.length()))));
				}
			}
		} catch (BackingStoreException ignored) {
			// A missing workspace mapping is recoverable through the project name.
		}
		return Optional.empty();
	}

	private void remember(ProjectId projectId, AbstractTaskCategory category) {
		mappings.put(mappingKey(projectId), category.getHandleIdentifier());
		try {
			mappings.flush();
		} catch (BackingStoreException failure) {
			TimekeeperUiPlugin.getDefault().getLog().log(new Status(IStatus.WARNING,
					TimekeeperUiPlugin.PLUGIN_ID, "Could not store the Mylyn category mapping", failure));
		}
	}

	private String mappingKey(ProjectId projectId) {
		return PROJECT_MAPPING_PREFIX + projectId.value();
	}

	private Optional<AbstractTask> parentTask(ITask task) {
		if (!(task instanceof AbstractTask concrete)) return Optional.empty();
		return concrete.getParentContainers().stream().filter(AbstractTask.class::isInstance)
				.map(AbstractTask.class::cast).findFirst();
	}

	private Optional<AbstractTaskCategory> category(ITask task) {
		Set<ITask> visited = new HashSet<>();
		ITask current = task;
		while (current instanceof AbstractTask concrete && visited.add(current)) {
			Optional<AbstractTaskCategory> category = concrete.getParentContainers().stream()
					.filter(AbstractTaskCategory.class::isInstance).map(AbstractTaskCategory.class::cast).findFirst();
			if (category.isPresent() && category.get() instanceof TaskCategory) return category;
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
			if (current != null) depth++;
		}
		return depth;
	}

	private int depth(Task task, Map<TaskId, Task> tasks) {
		int depth = 0;
		Set<TaskId> visited = new HashSet<>();
		Task current = task;
		while (current.parentTaskId().isPresent() && visited.add(current.id())) {
			current = tasks.get(current.parentTaskId().orElseThrow());
			if (current == null) break;
			depth++;
		}
		return depth;
	}
}
