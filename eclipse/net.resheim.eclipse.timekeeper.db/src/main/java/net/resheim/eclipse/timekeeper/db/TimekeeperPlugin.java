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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.PersistenceException;

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
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.Task;
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
    public static final String PREF_BACKEND_MODE = "backend-mode";
    public static final String PREF_BACKEND_URL = "backend-url";
    public static boolean remoteConfigured() {
        return "remote".equals(Platform.getPreferencesService().getString(BUNDLE_ID,PREF_BACKEND_MODE,"embedded",new IScopeContext[] {InstanceScope.INSTANCE}));
    }
    public static String backendUrl() {
        return Platform.getPreferencesService().getString(BUNDLE_ID,PREF_BACKEND_URL,"",new IScopeContext[] {InstanceScope.INSTANCE});
    }
	public static final String PREF_DATABASE_URL = "database-url";
	public static final String PREF_DATABASE_LOCATION = "database-location";
	public static final String PREF_DATABASE_LOCATION_SHARED = "shared";
	public static final String PREF_DATABASE_LOCATION_WORKSPACE = "workspace";
	public static final String PREF_DATABASE_LOCATION_URL = "url";
	public static final String PREF_REPORT_TEMPLATES = "report-templates";
	public static final String PREF_DEFAULT_TEMPLATE = "default-template";

	private static TimekeeperPlugin instance;

	private static volatile EntityManager entityManager = null;
    private volatile String databaseIdentity = "connecting";
    /** Opaque identity of the database actually opened, unaffected by pending preference changes. */
    public String databaseIdentity() {return databaseIdentity;}

	private static Job saveDatabaseJob;

	private static final ListenerList<DatabaseChangeListener> listeners = new ListenerList<>();

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
                String address = jdbcUrl.split(";", 2)[0];
                String identity = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(address.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
				initializeDefaultLabels(candidate);
				synchronized (this) {
					if (stopping) {
						DatabaseStartup.close(candidate);
						return;
					}
					entityManager = candidate;
                    databaseIdentity = identity;
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
				{ "Prototyping", "246,208,90" },{ "Planning", "255,0,255" }, { "Programming", "87,206,105" },
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
        databaseIdentity = "connecting";
		databaseStatus = new Status(IStatus.INFO, BUNDLE_ID, "Connecting to the Timekeeper database.");
        if (remoteConfigured()) {
            databaseStatus = new Status(IStatus.INFO,BUNDLE_ID,"Remote backend selected; embedded storage is closed.");
            return;
        }
		createSaveJob();
		connectToDatabase();
		ISaveParticipant saveParticipant = new WorkspaceSaveParticipant();
		ResourcesPlugin.getWorkspace().addSaveParticipant(BUNDLE_ID, saveParticipant);
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
			super.stop(context);
		}
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
			// Refresh cached task state after importing persisted records.
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
	 * Return tracked tasks with recorded time in the specified week.
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
				.filter(tt -> hasData(tt, startDate, zoneId));
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



}
