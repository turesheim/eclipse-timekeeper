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
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
import org.eclipse.mylyn.internal.tasks.core.TaskRepositoryManager;
import org.eclipse.mylyn.tasks.core.IRepositoryManager;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.persistence.config.PersistenceUnitProperties;
import org.eclipse.persistence.jpa.PersistenceProvider;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
import net.resheim.eclipse.timekeeper.db.model.GlobalTaskId;
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
	
	private static final CountDownLatch latch = new CountDownLatch(1);

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

	private static EntityManager entityManager = null;

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

	private static final String LOCAL_REPO_ID = "local";

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

	private void connectToDatabase() {
//		Job connectDatabaseJob = new Job("Connecting to Timekeeper database") {
//
//			@Override
//			protected IStatus run(IProgressMonitor monitor) {
		Runnable runnable = () -> {
				log.info("Connecting to Timekeeper database");
				Map<String, Object> props = new HashMap<String, Object>();
				// default, default location
				String jdbc_url = "jdbc:h2:~/.timekeeper/h2db";
				try {
					String location = Platform.getPreferencesService().getString(BUNDLE_ID, PREF_DATABASE_LOCATION,
							PREF_DATABASE_LOCATION_SHARED, new IScopeContext[] { InstanceScope.INSTANCE });
					switch (location) {
					default:
					case PREF_DATABASE_LOCATION_SHARED:
						jdbc_url = getSharedLocation();
						// Fix https://github.com/turesheim/eclipse-timekeeper/issues/107
						System.setProperty("h2.bindAddress", "localhost");
						break;
					case PREF_DATABASE_LOCATION_WORKSPACE:
						jdbc_url = getWorkspaceLocation();
						break;
					case PREF_DATABASE_LOCATION_URL:
						jdbc_url = getSpecifiedLocation();
						break;
					}
					// if this property has been specified it will override all other settings
					if (System.getProperty("net.resheim.eclipse.timekeeper.db.url") != null) {
						log.info("Database URL was specified using property 'net.resheim.eclipse.timekeeper.db.url'");
						jdbc_url = System.getProperty("net.resheim.eclipse.timekeeper.db.url");
					}
					log.info("Using database at '{}'", jdbc_url);

					// baseline the database
//					Flyway flyway = Flyway.configure()
//							.dataSource(jdbc_url, "sa", "")
//							.baselineOnMigrate(false)
//							.locations("classpath:/db/").load();
//					flyway.migrate();
					// https://www.eclipse.org/forums/index.php?t=msg&goto=541155&
					props.put(PersistenceUnitProperties.CLASSLOADER, TimekeeperPlugin.class.getClassLoader());
					props.put(PersistenceUnitProperties.JDBC_URL, jdbc_url);
					props.put(PersistenceUnitProperties.JDBC_DRIVER, "org.h2.Driver");
					props.put(PersistenceUnitProperties.JDBC_USER, "sa");
					props.put(PersistenceUnitProperties.JDBC_PASSWORD, "");
					props.put(PersistenceUnitProperties.LOGGING_LEVEL, "fine"); // fine / fine
					// we want Flyway to create the database, it gives us better control over migrating?
//					props.put(PersistenceUnitProperties.DDL_GENERATION, "create-tables");
//					props.put(PersistenceUnitProperties.JAVASE_DB_INTERACTION, "true");
					createEntityManager(props);
				} catch (Exception e) {
					throw new RuntimeException("Could not connect to Timekeeper database at " + jdbc_url, e);
				}
				cleanTaskActivities();
				notifyListeners();
				latch.countDown();
		};
		Thread thread = new Thread(runnable);
        thread.start();
//				return Status.OK_STATUS;
//			}
//		};
//		log.info("Starting connection job");
//		connectDatabaseJob.setPriority(Job.LONG);
//		connectDatabaseJob.schedule();
	}

	private static void createEntityManager(Map<String, Object> props) {
		entityManager = new PersistenceProvider()
				.createEntityManagerFactory("net.resheim.eclipse.timekeeper.db", props)
				.createEntityManager(props);
	}
	
	public boolean isReady() {
		return latch.getCount() == 0;
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
		connectToDatabase();
		createSaveJob();
		ISaveParticipant saveParticipant = new WorkspaceSaveParticipant();
		ResourcesPlugin.getWorkspace().addSaveParticipant(BUNDLE_ID, saveParticipant);
	}

	/**
	 * In some cases the Mylyn task can be deactivated without the tracked task
	 * being properly updated. This can happen for instance when the workbench is
	 * closed before the database has been updated. In this case some guesswork is
	 * applied using data from Mylyn.
	 */
	private void cleanTaskActivities() {
		TypedQuery<Task> createQuery = entityManager.createQuery("SELECT t FROM Task t",
				Task.class);
		List<Task> resultList = createQuery.getResultList();
		for (Task trackedTask : resultList) {
			trackedTask.getCurrentActivity().ifPresent(activity -> {
				ITask task = trackedTask.getMylynTask() == null ? getMylynTask(trackedTask)
						: trackedTask.getMylynTask();
				// note that the ITask may not exist in this workspace
				if (task != null && !task.isActive()) {
					// try to figure out when it was last active
					ZonedDateTime start = activity.getStart().atZone(ZoneId.systemDefault());
					ZonedDateTime end = start.plusMinutes(30);
					Calendar s = Calendar.getInstance();
					Calendar e = Calendar.getInstance();
					while (true) {
						s.setTime(Date.from(start.toInstant()));
						e.setTime(Date.from(end.toInstant()));
						long elapsedTime = TasksUi.getTaskActivityManager().getElapsedTime(task, s, e);
						// update the end time on the activity
						if (elapsedTime == 0 || e.after(Calendar.getInstance())) {
							activity.setEnd(LocalDateTime.ofInstant(e.toInstant(), ZoneId.systemDefault()));
							trackedTask.endActivity();
							break;
						}
						start = start.plusMinutes(30);
						end = end.plusMinutes(30);
					}
				}
			});
		}
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		if (entityManager != null && entityManager.isOpen()) {
			entityManager.close();
		}
		super.stop(context);
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
		if (entityManager == null) {
			return null;
		}
		if (linkCache.containsKey(task)) {
			return linkCache.get(task);
		}
		GlobalTaskId id = new GlobalTaskId(TimekeeperPlugin.getRepositoryUrl(task), task.getTaskId());
		Task found = entityManager.find(Task.class, id);
		if (found == null) {
			// no such tracked task exists, create one
			Task tt = new Task(task);
			entityManager.persist(tt);
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
		// get the repository then find the task. Seems like the Mylyn API is
		// a bit limited in this area as I could not find something more usable
		Optional<TaskRepository> tr = TasksUi
				.getRepositoryManager()
				.getAllRepositories()
				.stream()
				.filter(r -> r.getRepositoryUrl().equals(task.getRepositoryUrl())).findFirst();
		if (tr.isPresent()) {
			return TasksUi.getRepositoryModel().getTask(tr.get(), task.getTaskId());
		}
		return null;
	}

	/**
	 * Exports Timekeeper related data to two separate CSV files. One for
	 * {@link Task}, another for {@link Activity} instances and yet another
	 * for the relations between these two.
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
		Path tasks = path.resolve("trackedtask.csv");
		Path activities = path.resolve("activity.csv");
		Path relations = path.resolve("trackedtask_activity.csv");
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		int tasksExported = entityManager
				.createNativeQuery("CALL CSVWRITE('" + tasks + "', 'SELECT * FROM TRACKEDTASK');").executeUpdate();
		int activitiesExported = entityManager
				.createNativeQuery("CALL CSVWRITE('" + activities + "', 'SELECT * FROM ACTIVITY');").executeUpdate();
		// relations are not automatically created, so we do this the easy way
		entityManager.createNativeQuery("CALL CSVWRITE('" + relations + "', 'SELECT * FROM TRACKEDTASK_ACTIVITY');")
				.executeUpdate();
		transaction.commit();
		return tasksExported + activitiesExported;
	}

	/**
	 * Import and merge records from the specified location.
	 * 
	 * @param path root location of the
	 * @return
	 * @throws IOException
	 */
	public int importFrom(Path path) throws IOException {
		Path tasks = path.resolve("trackedtask.csv");
		Path activities = path.resolve("activity.csv");
		Path relations = path.resolve("trackedtask_activity.csv");
		if (!tasks.toFile().exists()) {
			throw new IOException("'trackedtask.csv' does not exist in the specified location.");
		}
		if (!activities.toFile().exists()) {
			throw new IOException("'activity.csv' does not exist in the specified location.");
		}
		if (!relations.toFile().exists()) {
			throw new IOException("'trackedtask_activity.csv' does not exist in the specified location.");
		}
		EntityTransaction transaction = entityManager.getTransaction();
		try {
			transaction.begin();
			entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE;").executeUpdate();
			int tasksImported = entityManager
					.createNativeQuery("MERGE INTO TRACKEDTASK (SELECT * FROM CSVREAD('" + tasks + "'));")
					.executeUpdate();
			int activitiesImported = entityManager
					.createNativeQuery("MERGE INTO ACTIVITY (SELECT * FROM CSVREAD('" + activities + "'));")
					.executeUpdate();
			entityManager
					.createNativeQuery("MERGE INTO TRACKEDTASK_ACTIVITY (SELECT * FROM CSVREAD('" + relations + "'));")
					.executeUpdate();
			entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE;").executeUpdate();
			transaction.commit();
			// update all instances with potentially new content
			TypedQuery<Task> createQuery = entityManager.createQuery("SELECT t FROM Task t",
					Task.class);
			List<Task> resultList = createQuery.getResultList();
			for (Task trackedTask : resultList) {
				entityManager.refresh(trackedTask);
			}
			return tasksImported + activitiesImported;
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
		return "jdbc:h2:~/.timekeeper/h2db;AUTO_SERVER=TRUE;FILE_LOCK=SOCKET;AUTO_RECONNECT=TRUE;AUTO_SERVER_PORT=9090";
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
	public static String getParentContainerSummary(AbstractTask task) {
		if (!task.getParentContainers().isEmpty()) {
			AbstractTaskContainer next = task.getParentContainers().iterator().next();
			return next.getSummary();
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
				return getParentContainerSummary((AbstractTask) task);
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
						return getParentContainerSummary((AbstractTask) task);
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
		return entityManager.find(Project.class, title);
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
	public static Stream<Task> getTasks(LocalDate startDate) {
		if (entityManager == null) {
			return Stream.empty();
		}
		return entityManager.createNamedQuery("Task.findAll", Task.class)
				.getResultStream()
				// TODO: Move filtering to database
				.filter(tt -> hasData(tt, startDate))
				.map(TimekeeperPlugin::linkWithMylynTask);
	}
	
	private static boolean hasData/* this week */(Task task, LocalDate startDate) {
		// this should only be NULL if the database has not started yet. See databaseStateChanged()
		if (task == null) {
			return false;
		}
		LocalDate endDate = startDate.plusDays(7);
		Stream<Activity> filter = task
				.getActivities()
				.stream()
				.filter(a -> a.getDuration(startDate, endDate) != Duration.ZERO);
		return filter.count() > 0;
	}
	
	/**
	 * Finds and returns all activity label instances in the database.
	 * 
	 * @return a stream of labels
	 */
	public static Stream<ActivityLabel> getLabels(){
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
		Optional<TaskRepository> tr = TasksUi.getRepositoryManager()
				.getAllRepositories()
				.stream()
				.filter(r -> r.getRepositoryUrl().equals(tt.getRepositoryUrl())).findFirst();
		if (tr.isPresent()) {
			tt.linkWithMylynTask(TasksUi.getRepositoryModel().getTask(tr.get(), tt.getTaskId()));
			tt.setTaskLinkStatus(TaskLinkStatus.LINKED);
		} else {
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
			EntityTransaction transaction = entityManager.getTransaction();
			boolean activeTransaction = transaction.isActive();
			if (!activeTransaction) {
				transaction.begin();
			}
			entityManager.persist(activity);
			if (!activeTransaction) {
				transaction.commit();
			}
			log.debug("Dectivating task '{}'", task);
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
				id = TaskRepositoryManager.PREFIX_LOCAL + UUID.randomUUID().toString();
				repository.setProperty(TimekeeperPlugin.LOCAL_REPO_KEY_ID, id);
			}
			url = id;
		}
		return url;
	}

}
