/*******************************************************************************
 * Copyright © 2016-2018 Torkild U. Resheim
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.persistence.config.PersistenceUnitProperties;
import org.eclipse.persistence.jpa.PersistenceProvider;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.flywaydb.core.Flyway;
import org.osgi.framework.BundleContext;

import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;

/**
 * Core features for the time keeping application. Handles database and basic
 * time tracking.
 * 
 * @author Torkild U. Resheim
 */
@SuppressWarnings("restriction")
public class TimekeeperPlugin extends Plugin {

	/* Preferences */
	public static final String PREF_DATABASE_URL = "database-url";
	public static final String PREF_DATABASE_LOCATION = "database-location";
	public static final String PREF_DATABASE_LOCATION_SHARED = "shared";
	public static final String PREF_DATABASE_LOCATION_WORKSPACE = "workspace";
	public static final String PREF_DATABASE_LOCATION_URL = "url";
	public static final String PREF_REPORT_TEMPLATES = "report-templates";
	public static final String PREF_DEFAULT_TEMPLATE = "default-template";

	public static final String KEY_VALUELIST_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$
	
	public static final String BUNDLE_ID = "net.resheim.eclipse.timekeeper.db"; //$NON-NLS-1$

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
	
	public void addListener(DatabaseChangeListener listener){
		listeners.add(listener);
	}
	
	public void removeListener(DatabaseChangeListener listener){
		listeners.remove(listener);
	}
	
	private void notifyListeners(){
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
	
	// Database configuration:
	// Since we need a database pretty early, this method starts first, before 
	// the preference initializer have had the chance to run. So we ensure 
	// that there always is a database up and running. If the user changes
	// the database URL a restart is required.
	private void connectToDatabase() {
		Job connectDatabaseJob = new Job("Connecting to Timekeeper database") {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				Map<String, Object> props = new HashMap<String, Object>();
				// default, default location
				String jdbc_url = "jdbc:h2:~/.timekeeper/h2db";
				try {
					
					String location = Platform.getPreferencesService().getString(BUNDLE_ID, PREF_DATABASE_LOCATION,
							PREF_DATABASE_LOCATION_SHARED, new IScopeContext[] { InstanceScope.INSTANCE });
					switch (location){
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
					// baseline the database
			        Flyway flyway = new Flyway();
			        flyway.setDataSource(jdbc_url, "sa", "");
			        flyway.setLocations("classpath:/db/");
			        flyway.setBaselineOnMigrate(true);
			        flyway.migrate();
					// https://www.eclipse.org/forums/index.php?t=msg&goto=541155&
					props.put(PersistenceUnitProperties.CLASSLOADER, TimekeeperPlugin.class.getClassLoader());
					
					// ensure only a in-memory database is used when testing
					if (Thread.currentThread().getName().equals("WorkbenchTestable")) {
						jdbc_url = "jdbc:h2:mem:test_mem";
					}
					props.put(PersistenceUnitProperties.JDBC_URL, jdbc_url);
					props.put(PersistenceUnitProperties.JDBC_DRIVER, "org.h2.Driver");
					props.put(PersistenceUnitProperties.JDBC_USER, "sa");
					props.put(PersistenceUnitProperties.JDBC_PASSWORD, "");
					props.put(PersistenceUnitProperties.LOGGING_LEVEL, "fine");
					// we want Flyway to create the database, it gives us better control over migrating
					props.put(PersistenceUnitProperties.DDL_GENERATION, "none");
					entityManager = new PersistenceProvider()	
						.createEntityManagerFactory("net.resheim.eclipse.timekeeper.db", props)
						.createEntityManager();
				} catch (Exception e) {
					return new Status(IStatus.ERROR, BUNDLE_ID,
							"Could not connect to Timekeeper database at " + jdbc_url, e);
				}
		        cleanTaskActivities();
				notifyListeners();
				return Status.OK_STATUS;			}

		};
		connectDatabaseJob.setSystem(false);
		connectDatabaseJob.schedule();
	}		
	
	public class WorkspaceSaveParticipant implements ISaveParticipant {

		@Override
		public void doneSaving(ISaveContext context) {
		}

		@Override
		public void prepareToSave(ISaveContext context) throws CoreException {
		}

		@Override
		public void rollback(ISaveContext context) {
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

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		connectToDatabase();
		createSaveJob();
		ISaveParticipant saveParticipant = new WorkspaceSaveParticipant();
        ResourcesPlugin.getWorkspace().addSaveParticipant(BUNDLE_ID, saveParticipant);
	}

	/**
	 * In some cases the Mylyn task can be deactivated without the tracked task
	 * being properly updated. This can happen for instance when the workbench
	 * is closed before the database has been updated. In this case some
	 * guesswork is applied using data from Mylyn.
	 */
	private void cleanTaskActivities() {
		//long ps = System.currentTimeMillis();
		TypedQuery<TrackedTask> createQuery = entityManager.createQuery("SELECT tt FROM TRACKEDTASK tt",
				TrackedTask.class);
		List<TrackedTask> resultList = createQuery.getResultList();
		for (TrackedTask trackedTask : resultList) {
			if (trackedTask.getCurrentActivity().isPresent()) {
				ITask task =  trackedTask.getTask() == null ? 
						TimekeeperPlugin.getDefault().getTask(trackedTask) : trackedTask.getTask();
				// note that the ITask may not exist in this workspace
				if (task != null && !task.isActive()) {
					// try to figure out when it was last active
					Activity activity = trackedTask.getCurrentActivity().get();
					ZonedDateTime start = activity.getStart().atZone(ZoneId.systemDefault());
					ZonedDateTime end = start.plusMinutes(30);
					while (true) {
						Calendar s = Calendar.getInstance();
						Calendar e = Calendar.getInstance();
						s.setTime(Date.from(start.toInstant()));
						e.setTime(Date.from(end.toInstant()));
						long elapsedTime = TasksUi.getTaskActivityManager().getElapsedTime(task, s, e);
						// update the end time on the activity
						if (elapsedTime == 0 || e.after(Calendar.getInstance())) {
							activity.setEnd(LocalDateTime.ofInstant(e.toInstant(), ZoneId.systemDefault()));
							trackedTask.endActivity();
							break;
						}
						start.plusMinutes(30);
						end.plusMinutes(30);
					}
				}
			}
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
	 * Returns the {@link TrackedTask} associated with the given Mylyn task. If
	 * no such task exists it will be created.
	 * 
	 * @param task
	 *            the Mylyn task
	 * @return a {@link TrackedTask} associated with the Mylyn task
	 */
	public TrackedTask getTask(ITask task) {
		// this may happen if the UI asks for task details before the database is ready
		if (entityManager == null) {
			return null;
		}
		TrackedTaskId id = new TrackedTaskId(TrackedTask.getRepositoryUrl(task), task.getTaskId());
		TrackedTask found = entityManager.find(TrackedTask.class, id);
		if (found == null) {
			// no such tracked task exists, create one
			TrackedTask tt = new TrackedTask(task);
			entityManager.persist(tt);
			return tt;
		} else {
			return found;
		}			
	}
	
	/**
	 * Returns the {@link ITask} associated with the given {@link TrackedTask} task. If
	 * no such task exists <code>null</code> will be returned
	 * 
	 * @param task
	 *            the time tracked task
	 * @return a Mylyn task or <code>null</code>
	 */
	public ITask getTask(TrackedTask task) {
		// get the repository then find the task. Seems like the Mylyn API is
		// a bit limited in this area as I could not find something more usable
		Optional<TaskRepository> tr = TasksUi.getRepositoryManager().getAllRepositories()
				.stream()
				.filter(r -> r.getRepositoryUrl().equals(task.getRepositoryUrl()))
				.findFirst();
		if (tr.isPresent()) {
			return TasksUi.getRepositoryModel().getTask(tr.get(), task.getTaskId());
		}
		return null;		
	}

	/**
	 * Exports Timekeeper related data to two separate CSV files. One for
	 * {@link TrackedTask}, another for {@link Activity} instances and yet
	 * another for the relations between these two.
	 * 
	 * TODO: Compress into zip
	 * 
	 * @param path
	 *            the path to the directory
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
				.createNativeQuery("CALL CSVWRITE('"+tasks+"', 'SELECT * FROM TRACKEDTASK');")
				.executeUpdate();
		int activitiesExported = entityManager
				.createNativeQuery("CALL CSVWRITE('"+activities+"', 'SELECT * FROM ACTIVITY');")
				.executeUpdate();
		// relations are not automatically created, so we do this the easy way
		entityManager
			.createNativeQuery("CALL CSVWRITE('"+relations+"', 'SELECT * FROM TRACKEDTASK_ACTIVITY');")
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
		Path activities = path.resolve("activitiy.csv");
		Path relations = path.resolve("trackedtask_activity.csv");
		if (!tasks.toFile().exists()){
			throw new IOException("'trackedtask.csv' does not exist in the specified location.");
		}
		if (!activities.toFile().exists()){
			throw new IOException("'activity.csv' does not exist in the specified location.");
		}
		if (!relations.toFile().exists()){
			throw new IOException("'trackedtask_activity.csv' does not exist in the specified location.");
		}
		EntityTransaction transaction = entityManager.getTransaction();
		try {
			transaction.begin();		
			entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE;").executeUpdate();
			int tasksImported = entityManager
					.createNativeQuery("MERGE INTO TRACKEDTASK (SELECT * FROM CSVREAD('"+tasks+"'));")
					.executeUpdate();
			int activitiesImported = entityManager
					.createNativeQuery("MERGE INTO ACTIVITY (SELECT * FROM CSVREAD('"+activities+"'));")
					.executeUpdate();
			entityManager
				.createNativeQuery("MERGE INTO TRACKEDTASK_ACTIVITY (SELECT * FROM CSVREAD('"+relations+"'));")
				.executeUpdate();
			entityManager
				.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE;")
				.executeUpdate();
			transaction.commit();
			// update all instances with potentially new content 
			TypedQuery<TrackedTask> createQuery = entityManager.createQuery("SELECT tt FROM TRACKEDTASK tt",
					TrackedTask.class);
			List<TrackedTask> resultList = createQuery.getResultList();
			for (TrackedTask trackedTask : resultList) {
				entityManager.refresh(trackedTask);
			}
			return tasksImported+activitiesImported;
		} catch (PersistenceException e){
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
	 * If the lock file exists, and the lock method is 'socket', then the
	 * process checks if the port is in use. If the original process is still
	 * running, the port is in use and this process throws an exception
	 * (database is in use). If the original process died (for example due to a
	 * power failure, or abnormal termination of the virtual machine), then the
	 * port was released. The new process deletes the lock file and starts
	 * again.
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

	private void createSaveJob() {
		saveDatabaseJob = new Job("Saving Timekeeper database") {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				if (entityManager != null && entityManager.isOpen()) {
					Collection<AbstractTask> allTasks = TasksUiPlugin.getTaskList().getAllTasks();
					EntityTransaction transaction = entityManager.getTransaction();
					transaction.begin();
					for (AbstractTask abstractTask : allTasks) {
						TrackedTask task = getTask(abstractTask);
						entityManager.persist(task);
					}
					transaction.commit();
					return Status.OK_STATUS;
				} else {
					return new Status(IStatus.ERROR, BUNDLE_ID, "Cannot persist data – no database connection.");
				}
			}

		};
	}
	
	public void saveDatabase(){
		if (saveDatabaseJob != null){
			saveDatabaseJob.setUser(true);
			saveDatabaseJob.schedule();
		}
	}

	/**
	 * Returns the name of the container holding the supplied task.
	 *
	 * @param task
	 *            task to find the name for
	 * @return the name of the task
	 */
	public static String getParentContainerSummary(AbstractTask task) {
		if (task.getParentContainers().size() > 0) {
			AbstractTaskContainer next = task.getParentContainers().iterator().next();
			return next.getSummary();
		}
		// FIXME: Should return null
		return "Uncategorized";
	}

	/**
	 * Returns the project name for the task if it can be determined.
	 *
	 * @param task
	 *            the task to get the project name for
	 * @return the project name or "&lt;undetermined&gt;"
	 */
	public static String getProjectName(ITask task) {
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
			default:
				break;
			}
		} catch (CoreException e) {
			e.printStackTrace();
		}
		return "<undetermined>";
	}
	
	/**
	 * Provides means of setting the {@link EntityManager} of the plug-in. This
	 * method should only be used for testing.
	 * 
	 * @param entityManager
	 * @see #start(BundleContext)
	 * @see #connectToDatabase()
	 */
	public static void setEntityManager(EntityManager entityManager) {
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
			e.printStackTrace();
		}
		return templates;
	}

}
