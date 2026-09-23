/*******************************************************************************
 * Copyright © 2016-2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.PersistenceException;
import javax.persistence.TypedQuery;

import org.eclipse.core.resources.ISaveContext;
import org.eclipse.core.resources.ISaveParticipant;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskCategory;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.IRepositoryManager;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
import net.resheim.eclipse.timekeeper.db.model.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.ProjectType;
import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.db.model.TaskLinkStatus;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;

/**
 * Core features for the time keeping application. Handles database and basic
 * time tracking.
 * 
 * @author Torkild U. Resheim
 */
@SuppressWarnings("restriction")
public class TimekeeperPlugin extends Plugin {
	
	private static final Logger log = LoggerFactory.getLogger(TimekeeperPlugin.class);
	
	private static volatile IStatus databaseStatus = new Status(IStatus.INFO, TimekeeperPlugin.BUNDLE_ID,
			"Connecting to the Timekeeper database.");
	private volatile boolean stopping;

	public static final String BUNDLE_ID = "net.resheim.eclipse.timekeeper.db"; //$NON-NLS-1$

	/* Preferences */
	public static final String PREF_DATABASE_URL = "database-url";
	public static final String PREF_DATABASE_LOCATION = "database-location";
	public static final String PREF_DATABASE_LOCATION_SHARED = "shared";
	public static final String PREF_DATABASE_LOCATION_WORKSPACE = "workspace";
	public static final String PREF_DATABASE_LOCATION_URL = "url";
	public static final String PREF_REPORT_TEMPLATES = "report-templates";
	public static final String PREF_DEFAULT_TEMPLATE = "default-template";

	/** Identifier used when storing Timekeeper data in the Mylyn Task */
	public static final String KEY_VALUELIST_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$

	private static TimekeeperPlugin instance;

	private static volatile EntityManager entityManager = null;

	private static Job saveDatabaseJob;

	private static final ListenerList<DatabaseChangeListener> listeners = new ListenerList<>();

	/** Task repository kind identifier for Bugzilla. */
	public static final String KIND_BUGZILLA = "bugzilla"; //$NON-NLS-1$
	/** Task repository kind identifier for GitHub. */
	public static final String KIND_GITHUB = "github"; //$NON-NLS-1$
	/** Task repository kind identifier for JIRA. */
	public static final String KIND_JIRA = "jira"; //$NON-NLS-1$
	/** Task repository kind identifier for local tasks. */
	public static final String KIND_LOCAL = "local"; //$NON-NLS-1$
	/** Repository attribute ID for custom grouping field. */
	public static final String ATTR_GROUPING = KEY_VALUELIST_ID + ".grouping"; //$NON-NLS-1$
	/** Stable Timekeeper task UUID stored on a projected Mylyn task. */
	public static final String ATTR_TIMEKEEPER_TASK_ID = KEY_VALUELIST_ID + ".task-id"; //$NON-NLS-1$

	private static final String LOCAL_REPO_ID = "local";
	// Persisted Timekeeper repository identity; preserve this prefix across Mylyn upgrades.
	private static final String LOCAL_REPO_PREFIX = LOCAL_REPO_ID + "-";

	private static final String LOCAL_REPO_KEY_ID = "net.resheim.eclipse.timekeeper.repo-id"; //$NON-NLS-1$
	
	/**
	 * Some features connected to Mylyn has no knowledge of Timekeeper tasks and in
	 * order to avoid excessive lookups in the database, we utilise a simple cache.
	 */
	private static Map<ITask, Task> linkCache = new HashMap<>();

	public void addListener(DatabaseChangeListener listener) {
		listeners.add(listener);
		log.info("Added new DatabaseChangeListener {}", listener);
	}

	public void removeListener(DatabaseChangeListener listener) {
		listeners.remove(listener);
	}

	private void notifyListeners() {
		log.info("Database state changed");
		for (DatabaseChangeListener databaseChangeListener : listeners) {
			SafeRunner.run(new ISafeRunnable() {
				@Override
				public void run() throws Exception {
					databaseChangeListener.databaseStateChanged();
				}

				@Override
				public void handleException(Throwable exception) {
					// ignore
				}
			});
		}
	}

	/** Notifies Eclipse consumers about a service-level persistence change. */
	public void notifyServiceListeners() {
		notifyListeners();
	}

	private void connectToDatabase() {
		Thread thread = new Thread(() -> {
			EntityManager candidate = null;
			try {
				String jdbcUrl = System.getProperty("net.resheim.eclipse.timekeeper.db.url");
				if (jdbcUrl == null) {
					String location = Platform.getPreferencesService().getString(BUNDLE_ID, PREF_DATABASE_LOCATION,
							PREF_DATABASE_LOCATION_SHARED, new IScopeContext[] { InstanceScope.INSTANCE });
					switch (location) {
					case PREF_DATABASE_LOCATION_WORKSPACE:
						jdbcUrl = getWorkspaceLocation();
						break;
					case PREF_DATABASE_LOCATION_URL:
						jdbcUrl = getSpecifiedLocation();
						break;
					default:
						System.setProperty("h2.bindAddress", "localhost");
						jdbcUrl = getSharedLocation();
						break;
					}
				}
				candidate = DatabaseStartup.open(jdbcUrl);
				cleanTaskActivities(candidate);
				initializeDefaultLabels(candidate);
				synchronized (this) {
					if (stopping) {
						DatabaseStartup.close(candidate);
						return;
					}
					entityManager = candidate;
					databaseStatus = Status.OK_STATUS;
				}
			} catch (Exception failure) {
				try {
					DatabaseStartup.close(candidate);
				} catch (RuntimeException cleanup) {
					failure.addSuppressed(cleanup);
				}
				if (stopping) {
					databaseStatus = Status.CANCEL_STATUS;
				} else {
					databaseStatus = new Status(IStatus.ERROR, BUNDLE_ID,
							"Timekeeper database unavailable: " + failure.getMessage(), failure);
					getLog().log(databaseStatus);
				}
			}
			notifyListeners();
		}, "Timekeeper database startup");
		thread.setDaemon(true);
		thread.start();
	}

	public boolean isReady() {
		return databaseStatus.isOK();
	}

	/** Published startup status for the workweek view and preferences. */
	public IStatus getDatabaseStatus() {
		return databaseStatus;
	}

	static void initializeDefaultLabels(EntityManager manager) {
		if (manager.createQuery("SELECT COUNT(l) FROM ActivityLabel l", Long.class).getSingleResult() != 0) {
			return;
		}
		String[][] defaults = {
				{ "Production issue", "244,103,88" }, { "Testing", "245,166,81" },
				{ "Prototyping", "246,208,90" }, { "Programming", "87,206,105" },
				{ "Debugging", "177,111,209" }, { "Communication", "66,136,243" },
				{ "Meeting", "156,156,160" } };
		manager.getTransaction().begin();
		for (String[] label : defaults) {
			manager.persist(new ActivityLabel(label[0], label[1]));
		}
		manager.getTransaction().commit();
	}

	public class WorkspaceSaveParticipant implements ISaveParticipant {

		@Override
		public void doneSaving(ISaveContext context) {
			// nothing to do here
		}

		@Override
		public void prepareToSave(ISaveContext context) throws CoreException {
			// nothing to do here
		}

		@Override
		public void rollback(ISaveContext context) {
			// nothing to do here
		}

		@Override
		public void saving(ISaveContext context) throws CoreException {
			saveDatabaseJob.setSystem(true);
			saveDatabaseJob.schedule();
		}
	}

	/**
	 * Returns the shared plug-in instance.
	 *
	 * @return the shared instance
	 */
	public static TimekeeperPlugin getDefault() {
		if (instance == null) {
			instance = new TimekeeperPlugin();
		}
		return instance;
	}
	
	public EntityManager getEntityManager() {
		return entityManager;
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		log.info("Starting TimekeeperPlugin");
		instance = this;
		stopping = false;
		databaseStatus = new Status(IStatus.INFO, BUNDLE_ID, "Connecting to the Timekeeper database.");
		createSaveJob();
		connectToDatabase();
		ISaveParticipant saveParticipant = new WorkspaceSaveParticipant();
		ResourcesPlugin.getWorkspace().addSaveParticipant(BUNDLE_ID, saveParticipant);
	}

	/**
	 * In some cases the Mylyn task can be deactivated without the tracked task
	 * being properly updated. This can happen for instance when the workbench is
	 * closed before the database has been updated. In this case some guesswork is
	 * applied using data from Mylyn.
	 */
	private void cleanTaskActivities(EntityManager manager) {
		EntityTransaction transaction = manager.getTransaction();
		boolean activeTransaction = transaction.isActive();
		if (!activeTransaction) transaction.begin();
		try {
			TypedQuery<Task> createQuery = manager.createQuery("SELECT t FROM Task t", Task.class);
			List<Task> resultList = createQuery.getResultList();
			for (Task trackedTask : resultList) {
				Optional<Activity> current = trackedTask.getCurrentActivity();
				if (current.isEmpty()) continue;
				ITask task = trackedTask.getMylynTask() == null ? getMylynTask(trackedTask)
						: trackedTask.getMylynTask();
				// The ITask may not exist in this workspace. An active task must keep
				// its activity open so tracking can continue after a restart.
				if (task == null || TasksUi.getTaskActivityManager().isActive(task)) continue;

				Activity activity = current.get();
				Instant now = Instant.now();
				long elapsedTime = 0;
				Instant tick = trackedTask.getTick();
				if (tick == null || tick.isBefore(activity.getStart())) {
					Calendar start = Calendar.getInstance();
					start.setTimeInMillis(activity.getStart().toEpochMilli());
					Calendar end = Calendar.getInstance();
					end.setTimeInMillis(now.toEpochMilli());
					elapsedTime = TasksUi.getTaskActivityManager().getElapsedTime(task, start, end);
				}
				Instant end = recoveredActivityEnd(activity.getStart(), tick, now, elapsedTime);
				trackedTask.endActivity(end);
				manager.persist(activity);
				manager.persist(trackedTask);
			}
			if (!activeTransaction) transaction.commit();
		} catch (RuntimeException e) {
			if (!activeTransaction && transaction.isActive()) transaction.rollback();
			throw e;
		}
	}

	static Instant recoveredActivityEnd(Instant start, Instant tick,
			Instant now, long elapsedTimeMillis) {
		Instant latestValidEnd = now.isBefore(start) ? start : now;
		Instant recovered = tick != null && !tick.isBefore(start)
				? tick
				: start.plusMillis(elapsedTimeMillis);
		if (recovered.isBefore(start)) return start;
		return recovered.isAfter(latestValidEnd) ? latestValidEnd : recovered;
	}

	@Override
	public synchronized void stop(BundleContext context) throws Exception {
		stopping = true;
		databaseStatus = Status.CANCEL_STATUS;
		if (saveDatabaseJob != null) saveDatabaseJob.cancel();
		try {
			DatabaseStartup.close(entityManager);
		} finally {
			entityManager = null;
			linkCache.clear();
			super.stop(context);
		}
	}

	/**
	 * Returns the Timekeeper {@link Task} associated with the given Mylyn
	 * task. If no such task exists it will be created.
	 * 
	 * @param task the Mylyn task
	 * @return a {@link Task} associated with the Mylyn task
	 * @throws InterruptedException
	 */
	public Task getTask(ITask task) {
		// the UI will typically attempt to get some task details before the database is ready 
		if (entityManager == null || task == null) {
			return null;
		}
		if (linkCache.containsKey(task)) {
			return linkCache.get(task);
		}
		Task found = null;
		String timekeeperId = task.getAttribute(ATTR_TIMEKEEPER_TASK_ID);
		if (timekeeperId != null) {
			try {
				found = entityManager.find(Task.class, UUID.fromString(timekeeperId).toString());
			} catch (IllegalArgumentException malformed) {
				// Fall back to the provider/repository/task identity below.
			}
		}
		String providerId = task.getConnectorKind() == null ? "mylyn" : task.getConnectorKind();
		String repositoryId = TimekeeperPlugin.getRepositoryUrl(task);
		if (found == null) {
			List<Task> matches = entityManager.createNamedQuery("ExternalTaskReference.findTask", Task.class)
					.setParameter("providerId", providerId)
					.setParameter("repositoryId", repositoryId)
					.setParameter("externalId", task.getTaskId())
					.setMaxResults(1)
					.getResultList();
			found = matches.isEmpty() ? null : matches.get(0);
		}
		if (found == null) {
			// no such tracked task exists, create one
			Task tt = new Task(task);
			entityManager.persist(tt);
			task.setAttribute(ATTR_TIMEKEEPER_TASK_ID, tt.getId());
			linkCache.put(task, tt);
			return tt;
		} else {
			log.info("Task '{}' was not linked with Mylyn task", found);
			// make sure there is a link between the two tasks, this would be the case if the tracked task was just
			// loaded from the database
			if (found.getTaskLinkStatus().equals(TaskLinkStatus.UNDETERMINED)) { 
				found.linkWithMylynTask(task);
				entityManager.persist(found);
			}
			task.setAttribute(ATTR_TIMEKEEPER_TASK_ID, found.getId());
			linkCache.put(task, found);
			return found;
		}
	}

	/**
	 * Returns the Mylyn {@link ITask} associated with the given {@link Task}
	 * task. If no such task exists <code>null</code> will be returned.
	 * 
	 * @param task the time tracked task
	 * @return a Mylyn task or <code>null</code>
	 */
	public static ITask getMylynTask(Task task) {
		ITask projected = TasksUiPlugin.getTaskList().getAllTasks().stream()
				.filter(candidate -> task.getId().equals(candidate.getAttribute(ATTR_TIMEKEEPER_TASK_ID)))
				.findFirst().orElse(null);
		if (projected != null) return projected;
		// get the repository then find the task. Seems like the Mylyn API is
		// a bit limited in this area as I could not find something more usable
		List<TaskRepository> repositories = TasksUi.getRepositoryManager().getAllRepositories();
		for (ExternalTaskReference reference : task.getExternalReferences()) {
			String repositoryUrl = reference.getRepositoryId();
			if (repositoryUrl.startsWith(LOCAL_REPO_PREFIX)) repositoryUrl = LOCAL_REPO_ID;
			String selectedRepositoryUrl = repositoryUrl;
			Optional<TaskRepository> repository = repositories.stream()
					.filter(candidate -> candidate.getRepositoryUrl().equals(selectedRepositoryUrl)).findFirst();
			if (repository.isPresent()) {
				ITask linked = TasksUi.getRepositoryModel().getTask(repository.get(), reference.getExternalId());
				if (linked != null) return linked;
			}
		}
		return null;
	}

	/**
	 * Exports Timekeeper projects, tasks, activities, their relations, and optional
	 * external task identities to separate CSV files.
	 * 
	 * TODO: Compress into zip
	 * 
	 * @param path the path to the directory
	 * @throws IOException
	 */
	public int exportTo(Path path) throws IOException {
		if (!path.toFile().exists()) {
			Files.createDirectory(path);
		}
		Path projectTypes = path.resolve("project_type.csv");
		Path projects = path.resolve("project.csv");
		Path tasks = path.resolve("trackedtask.csv");
		Path activities = path.resolve("activity.csv");
		Path relations = path.resolve("trackedtask_activity.csv");
		Path projectTasks = path.resolve("project_task.csv");
		Path projectActivities = path.resolve("project_activity.csv");
		Path externalReferences = path.resolve("external_task_reference.csv");
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		int projectTypesExported = entityManager
				.createNativeQuery("CALL CSVWRITE('" + projectTypes + "', 'SELECT * FROM PROJECT_TYPE');")
				.executeUpdate();
		int projectsExported = entityManager
				.createNativeQuery("CALL CSVWRITE('" + projects + "', 'SELECT * FROM PROJECT');").executeUpdate();
		int tasksExported = entityManager
				.createNativeQuery("CALL CSVWRITE('" + tasks + "', 'SELECT * FROM TASK');").executeUpdate();
		int activitiesExported = entityManager
				.createNativeQuery("CALL CSVWRITE('" + activities + "', 'SELECT * FROM ACTIVITY');").executeUpdate();
		// relations are not automatically created, so we do this the easy way
		entityManager.createNativeQuery("CALL CSVWRITE('" + relations + "', 'SELECT * FROM TASK_ACTIVITY');")
				.executeUpdate();
		entityManager.createNativeQuery("CALL CSVWRITE('" + projectTasks + "', 'SELECT * FROM PROJECT_TASK');")
				.executeUpdate();
		entityManager.createNativeQuery("CALL CSVWRITE('" + projectActivities + "', 'SELECT * FROM PROJECT_ACTIVITY');")
				.executeUpdate();
		int referencesExported = entityManager.createNativeQuery("CALL CSVWRITE('" + externalReferences
				+ "', 'SELECT * FROM EXTERNAL_TASK_REFERENCE');").executeUpdate();
		transaction.commit();
		return projectTypesExported + projectsExported + tasksExported + activitiesExported + referencesExported;
	}

	/**
	 * Import and merge records from the specified location.
	 * 
	 * @param path root location of the
	 * @return
	 * @throws IOException
	 */
	public int importFrom(Path path) throws IOException {
		Path projectTypes = path.resolve("project_type.csv");
		Path projects = path.resolve("project.csv");
		Path tasks = path.resolve("trackedtask.csv");
		Path activities = path.resolve("activity.csv");
		Path relations = path.resolve("trackedtask_activity.csv");
		Path projectTasks = path.resolve("project_task.csv");
		Path projectActivities = path.resolve("project_activity.csv");
		Path externalReferences = path.resolve("external_task_reference.csv");
		if (!projectTypes.toFile().exists()) {
			throw new IOException("'project_type.csv' does not exist in the specified location.");
		}
		if (!projects.toFile().exists()) {
			throw new IOException("'project.csv' does not exist in the specified location.");
		}
		if (!tasks.toFile().exists()) {
			throw new IOException("'trackedtask.csv' does not exist in the specified location.");
		}
		if (!activities.toFile().exists()) {
			throw new IOException("'activity.csv' does not exist in the specified location.");
		}
		if (!relations.toFile().exists()) {
			throw new IOException("'trackedtask_activity.csv' does not exist in the specified location.");
		}
		if (!projectTasks.toFile().exists()) {
			throw new IOException("'project_task.csv' does not exist in the specified location.");
		}
		if (!projectActivities.toFile().exists()) {
			throw new IOException("'project_activity.csv' does not exist in the specified location.");
		}
		if (!externalReferences.toFile().exists()) {
			throw new IOException("'external_task_reference.csv' does not exist in the specified location.");
		}
		EntityTransaction transaction = entityManager.getTransaction();
		try {
			transaction.begin();
			entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE;").executeUpdate();
			int projectTypesImported = entityManager
					.createNativeQuery("MERGE INTO PROJECT_TYPE (SELECT * FROM CSVREAD('" + projectTypes + "'));")
					.executeUpdate();
			int projectsImported = entityManager
					.createNativeQuery("MERGE INTO PROJECT (SELECT * FROM CSVREAD('" + projects + "'));")
					.executeUpdate();
			int tasksImported = entityManager
					.createNativeQuery("MERGE INTO TASK (SELECT * FROM CSVREAD('" + tasks + "'));")
					.executeUpdate();
			int activitiesImported = entityManager
					.createNativeQuery("MERGE INTO ACTIVITY (SELECT * FROM CSVREAD('" + activities + "'));")
					.executeUpdate();
			entityManager
					.createNativeQuery("MERGE INTO TASK_ACTIVITY (SELECT * FROM CSVREAD('" + relations + "'));")
					.executeUpdate();
			entityManager
					.createNativeQuery("MERGE INTO PROJECT_TASK (SELECT * FROM CSVREAD('" + projectTasks + "'));")
					.executeUpdate();
			entityManager.createNativeQuery(
					"MERGE INTO PROJECT_ACTIVITY (SELECT * FROM CSVREAD('" + projectActivities + "'));")
					.executeUpdate();
			int referencesImported = entityManager.createNativeQuery("MERGE INTO EXTERNAL_TASK_REFERENCE "
					+ "(SELECT * FROM CSVREAD('" + externalReferences + "'));").executeUpdate();
			entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE;").executeUpdate();
			transaction.commit();
			// Refresh task state without detaching objects still used by Mylyn and the
			// Workweek view.
			List<Task> restoredTasks = entityManager.createQuery("SELECT t FROM Task t", Task.class).getResultList();
			for (Task restoredTask : restoredTasks) entityManager.refresh(restoredTask);
			return projectTypesImported + projectsImported + tasksImported + activitiesImported + referencesImported;
		} catch (PersistenceException e) {
			transaction.rollback();
			throw new IOException(e.getMessage());
		}
	}

	/**
	 * <p>
	 * If the lock file does not exist, it is created. Then a server socket is
	 * opened on a defined port, and kept open. The port and IP address of the
	 * process that opened the database is written into the lock file.
	 * </p>
	 * <p>
	 * If the lock file exists, and the lock method is 'file', then the software
	 * switches to the 'file' method.
	 * </p>
	 * <p>
	 * If the lock file exists, and the lock method is 'socket', then the process
	 * checks if the port is in use. If the original process is still running, the
	 * port is in use and this process throws an exception (database is in use). If
	 * the original process died (for example due to a power failure, or abnormal
	 * termination of the virtual machine), then the port was released. The new
	 * process deletes the lock file and starts again.
	 * </p>
	 * 
	 * @return a connection URL string for the shared location
	 */
	public String getSharedLocation() {
		return "jdbc:h2:~/.timekeeper/h2db;AUTO_SERVER=TRUE;AUTO_SERVER_PORT=9090";
	}

	public String getWorkspaceLocation() throws IOException {
		String jdbc_url;
		Location instanceLocation = Platform.getInstanceLocation();
		Path path = Paths.get(instanceLocation.getURL().getPath()).resolve(".timekeeper");
		if (!path.toFile().exists()) {
			Files.createDirectory(path);
		}
		jdbc_url = "jdbc:h2:" + path + "/h2db";
		return jdbc_url;
	}

	public String getSpecifiedLocation() {
		String jdbc_url;
		jdbc_url = Platform.getPreferencesService().getString(BUNDLE_ID, PREF_DATABASE_URL,
				"jdbc:h2:tcp://localhost/~/.timekeeper/h2db", // note use server location per default
				new IScopeContext[] { InstanceScope.INSTANCE });
		return jdbc_url;
	}

	private static void createSaveJob() {
		saveDatabaseJob = new Job("Saving Timekeeper database") {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				if (entityManager != null && entityManager.isOpen()) {
					List<Project> resultList = entityManager.createNamedQuery("Project.findAll", Project.class).getResultList();
					EntityTransaction transaction = entityManager.getTransaction();
					transaction.begin();
					for (Object object : resultList) {
						entityManager.persist(object);
						log.debug("Storing project {}",object);
					}
					transaction.commit();
					return Status.OK_STATUS;
				} else {
					return new Status(IStatus.ERROR, BUNDLE_ID, "Cannot persist data – no database connection.");
				}
			}

		};
	}

	/**
	 * Returns the name of the container holding the supplied task.
	 *
	 * @param task task to find the name for
	 * @return the name of the task
	 */
	public static String getParentContainerSummary(ITask task) {
		Set<ITask> visited = new java.util.HashSet<>();
		ITask current = task;
		while (current instanceof AbstractTask concrete && visited.add(current)) {
			Optional<AbstractTaskContainer> category = concrete.getParentContainers().stream()
					.filter(AbstractTaskCategory.class::isInstance).findFirst();
			if (category.isPresent()) return category.orElseThrow().getSummary();
			current = concrete.getParentContainers().stream().filter(AbstractTask.class::isInstance)
					.map(AbstractTask.class::cast).findFirst().orElse(null);
		}
		// FIXME: Should return null
		return "Uncategorized";
	}

	/**
	 * Returns the project name for the task if it can be determined.
	 *
	 * @param task the task to get the project name for
	 * @return the project name or "&lt;undetermined&gt;"
	 */
	public static String getMylynProjectName(ITask task) {
		String c = task.getConnectorKind();
		try {
			switch (c) {
			case KIND_GITHUB:
			case KIND_LOCAL:
				return getParentContainerSummary(task);
			// Bugzilla and JIRA users may want to group on different
			// values.
			case KIND_BUGZILLA:
			case KIND_JIRA:
				TaskData taskData = TasksUi.getTaskDataManager().getTaskData(task);
				if (taskData != null) {
					// This appears to be a pretty slow mechanism
					TaskRepository taskRepository = taskData.getAttributeMapper().getTaskRepository();
					String groupingAttribute = taskRepository.getProperty(ATTR_GROUPING);
					// Use custom grouping if specified
					if (groupingAttribute != null) {
						TaskAttribute attribute = taskData.getRoot().getAttribute(groupingAttribute);
						return attribute.getValue();
					} else {
						if (c.equals(KIND_BUGZILLA)) {
							return task.getAttribute("product"); //$NON-NLS-1$
						}
						return getParentContainerSummary(task);
					}
				}
				break;
			default:
				break;
			}
		} catch (CoreException e) {
			log.error("Could not obtain project name", e);
		}
		return "<undetermined>";
	}
	
	public static Project getProject(String title) {
		if (title == null) return null;
		return entityManager.createNamedQuery("Project.findAll", Project.class).getResultStream()
				.filter(project -> title.equals(project.getName())).findFirst().orElse(null);
	}

	/** Returns all persisted projects, including empty native projects. */
	public static Stream<Project> getProjects() {
		if (entityManager == null) return Stream.empty();
		return entityManager.createNamedQuery("Project.findAll", Project.class).getResultStream();
	}
	
	/**
	 * Creates a new {@link Project} based on information obtained from the Mylyn task. A {@link ProjectType} will also
	 * be created if it does not already exist.
	 *  
	 * @param task
	 * @return
	 */
	public static Project createAndSaveProject(ITask task) {
		EntityTransaction transaction = entityManager.getTransaction();
		boolean activeTransaction = transaction.isActive();
		String name = getMylynProjectName(task);
		String typeId = task.getConnectorKind();
		ProjectType type = entityManager.find(ProjectType.class, typeId);
		if (!activeTransaction) {
			transaction.begin();
		}
		if (type == null) {
			type = new ProjectType(typeId);
			entityManager.persist(type);
		}
		Project project = new Project(type, name);
		entityManager.persist(project);
		if (!activeTransaction) {
			transaction.commit();
		}
		return project;
		
	}

	/**
	 * Return all tracked tasks, those that are associated with a Mylyn task will have the proper assignment.
	 * 
	 * @return a stream of tasks
	 */
	public static Stream<Task> getTasks(LocalDate startDate, ZoneId zoneId) {
		if (entityManager == null) {
			return Stream.empty();
		}
		return entityManager.createNamedQuery("Task.findAll", Task.class)
				.getResultStream()
				// TODO: Move filtering to database
				.filter(tt -> hasData(tt, startDate, zoneId))
				.map(TimekeeperPlugin::linkWithMylynTask);
	}
	
	private static boolean hasData/* this week */(Task task, LocalDate startDate, ZoneId zoneId) {
		// this should only be NULL if the database has not started yet. See databaseStateChanged()
		if (task == null) {
			return false;
		}
		LocalDate endDate = startDate.plusDays(7);
		Stream<Activity> filter = task
				.getActivities()
				.stream()
				.filter(a -> !a.getDuration(startDate, endDate, zoneId).isZero());
		return filter.count() > 0;
	}
	
	/**
	 * Finds and returns all activity label instances in the database.
	 * 
	 * @return a stream of labels
	 */
	public static Stream<ActivityLabel> getLabels(){
		if (entityManager == null || !entityManager.isOpen()) return Stream.empty();
		return entityManager.createNamedQuery("ActivityLabel.findAll", ActivityLabel.class)
				.getResultStream();
	}
	
	public static void setLabel(ActivityLabel label) {
		EntityTransaction transaction = entityManager.getTransaction();
		boolean activeTransaction = transaction.isActive();
		if (!activeTransaction) {
			transaction.begin();
		}
		entityManager.persist(label);
		if (!activeTransaction) {
			transaction.commit();
		}
	}

	public static void removeLabel(ActivityLabel label) {
		EntityTransaction transaction = entityManager.getTransaction();
		boolean activeTransaction = transaction.isActive();
		if (!activeTransaction) {
			transaction.begin();
		}
		entityManager.remove(label);
		if (!activeTransaction) {
			transaction.commit();
		}
	}
	/**
	 * Links the given task with a Mylyn task if found in any of the workspace task
	 * repositories. If a local task could not be found the tracked task will be
	 * flagged as unlinked for the current workspace.
	 * 
	 * @param tt the tracked task
	 * @return the modified tracked task
	 */
	private static Task linkWithMylynTask(Task tt) {
		ITask linked = getMylynTask(tt);
		if (linked != null) {
			tt.linkWithMylynTask(linked);
			tt.setTaskLinkStatus(TaskLinkStatus.LINKED);
		} else {
			// Keep historical records usable when their Mylyn task was deleted.
			tt.linkWithMylynTask(null);
			tt.setTaskLinkStatus(TaskLinkStatus.UNLINKED);
		}
		return tt;
	}

	/**
	 * Provides means of setting the {@link EntityManager} of the plug-in. This
	 * method should only be used for testing.
	 * 
	 * @param entityManager
	 * @see #start(BundleContext)
	 * @see #connectToDatabase()
	 */
	static void setEntityManager(EntityManager entityManager) {
		TimekeeperPlugin.entityManager = entityManager;
	}

	/**
	 * Returns a list of all report templates stored in the preferences.
	 *
	 * @return a list of templates
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, ReportTemplate> getTemplates() {
		Map<String, ReportTemplate> templates = new HashMap<>();
		// and load the contents from the current preferences
		IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, TimekeeperPlugin.BUNDLE_ID);
		byte[] decoded = Base64.getDecoder().decode(store.getString(TimekeeperPlugin.PREF_REPORT_TEMPLATES));
		ByteArrayInputStream bis = new ByteArrayInputStream(decoded);
		try {
			ObjectInputStream ois = new ObjectInputStream(bis);
			java.util.List<ReportTemplate> rt = (java.util.List<ReportTemplate>) ois.readObject();
			for (ReportTemplate t : rt) {
				templates.put(t.getName(), t);
			}
		} catch (IOException | ClassNotFoundException e) {
			log.error("Could not load report templates",e);
		}
		return templates;
	}

	/**
	 * Creates a new tracked task associated with the Mylyn task if the prior is not
	 * present, and starts a new activity.
	 * 
	 * @param task the Mylyn task to start
	 */
	public void startMylynTask(ITask task) {
		if (entityManager == null) {
			return;
		}
		EntityTransaction transaction = entityManager.getTransaction();
		boolean activeTransaction = transaction.isActive();
		if (!activeTransaction) {
			transaction.begin();
		}
		Task ttask = getTask(task);
		if (ttask != null) {
			Activity activity = ttask.startActivity();
			entityManager.persist(activity);
			log.debug("Activating task '{}'", task);
			notifyListeners();
		}
		if (!activeTransaction) {
			transaction.commit();
		}
	}

	/**
	 * Ends the activity currently active on the given Mylyn task.
	 * 
	 * @param task the Mylyn task to start
	 */
	public void endMylynTask(ITask task) {
		Task ttask = getTask(task);
		if (ttask != null) {
			Activity activity = ttask.endActivity();
			if (activity == null) {
				return;
			}
			EntityTransaction transaction = entityManager.getTransaction();
			boolean activeTransaction = transaction.isActive();
			if (!activeTransaction) {
				transaction.begin();
			}
			entityManager.persist(activity);
			if (!activeTransaction) {
				transaction.commit();
			}
			log.debug("Deactivating task '{}'", task);
			notifyListeners();
		}
	}

	/**
	 * This method will return the repository URL for tasks in repositories that are
	 * not local. If the task is in a local repository, the Timekeeper repository
	 * identifier is returned if it exists. If it does not exist, it will be
	 * created, associated with the repository and returned.
	 * 
	 * @param task the task to get the repository URL for
	 * @return the repository URL or {@link UUID}
	 */
	public static String getRepositoryUrl(ITask task) {
		String url = task.getRepositoryUrl();
		if (TimekeeperPlugin.LOCAL_REPO_ID.equals(task.getRepositoryUrl())) {
			IRepositoryManager repositoryManager = TasksUi.getRepositoryManager();
			if (repositoryManager == null) { // may happen during testing
				return TimekeeperPlugin.LOCAL_REPO_ID;
			}
			TaskRepository repository = repositoryManager.getRepository(task.getConnectorKind(),
					task.getRepositoryUrl());
			String id = repository.getProperty(TimekeeperPlugin.LOCAL_REPO_KEY_ID);
			if (id == null) {
				id = LOCAL_REPO_PREFIX + UUID.randomUUID().toString();
				repository.setProperty(TimekeeperPlugin.LOCAL_REPO_KEY_ID, id);
			}
			url = id;
		}
		return url;
	}

}
