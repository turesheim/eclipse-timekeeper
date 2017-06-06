/*******************************************************************************
 * Copyright (c) 2016-2017 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.persistence.config.PersistenceUnitProperties;
import org.eclipse.persistence.jpa.PersistenceProvider;
import org.eclipse.ui.statushandlers.StatusManager;
import org.flywaydb.core.Flyway;
import org.osgi.framework.BundleContext;

/**
 * Core features for the time keeping application. Handles database and basic
 * time tracking.
 * 
 * @author Torkild U. Resheim
 */
@SuppressWarnings("restriction")
public class TimekeeperPlugin extends Plugin {

	/* Preferences */
	public static final String DATABASE_URL = "database-url";
	public static final String DATABASE_LOCATION = "database-location";
	public static final String DATABASE_LOCATION_SHARED = "shared";
	public static final String DATABASE_LOCATION_WORKSPACE = "workspace";
	public static final String DATABASE_LOCATION_URL = "url";

	public static final String KEY_VALUELIST_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$
	
	public static final String BUNDLE_ID = "net.resheim.eclipse.timekeeper.db"; //$NON-NLS-1$

	private static TimekeeperPlugin instance;

	private static EntityManager entityManager = null;
	
	private static final ListenerList<DatabaseChangeListener> listeners = new ListenerList<>();
	
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
	// Since we need a database pretty early this method starts first, before 
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
					
					String location = Platform.getPreferencesService().getString(BUNDLE_ID, DATABASE_LOCATION, DATABASE_LOCATION_SHARED,new IScopeContext[] { InstanceScope.INSTANCE });
					switch (location){
						case DATABASE_LOCATION_SHARED:
							jdbc_url = getSharedLocation();
							// Fix https://github.com/turesheim/eclipse-timekeeper/issues/107
							System.setProperty("h2.bindAddress", "localhost");
						break;
						case DATABASE_LOCATION_WORKSPACE:
							jdbc_url = getWorkspaceLocation();
						break;
						case DATABASE_LOCATION_URL:
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
					StatusManager.getManager()
						.handle(new Status(IStatus.INFO, BUNDLE_ID, "Timekeeper is connected to database at " + jdbc_url));
				} catch (Exception e) {
					return new Status(IStatus.INFO,BUNDLE_ID,"Could not connect to Timekeeper database at "+jdbc_url, e);
				}
		        cleanTaskActivities();
				notifyListeners();
				return Status.OK_STATUS;			}

		};
		connectDatabaseJob.setSystem(false);
		connectDatabaseJob.schedule();
	}		
	
	public class WorkspaceSaveParticipant implements ISaveParticipant {

		private Job saveDatabaseJob;

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
			if (saveDatabaseJob == null) {
				saveDatabaseJob = new Job("Saving Timekeeper database") {

					@Override
					protected IStatus run(IProgressMonitor monitor) {
						if (entityManager != null) {
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
				saveDatabaseJob.setSystem(true);
			}
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
				ITask task = getTask(trackedTask);
				// note that the ITask may not exist in this workspace
				if (task!=null && !task.isActive()) {
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
//		long pe = System.currentTimeMillis();
//					return new Status(IStatus.INFO,BUNDLE_ID,String.format("Cleaned up Timekeeper database in %dms",(pe-ps)));
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		super.stop(context);
		if (entityManager != null) {
			entityManager.close();
		}
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
		if (entityManager == null){
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
		Path activities = path.resolve("activitiy.csv");
		Path relations = path.resolve("trackedtask_activity.csv");
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		int tasksExported = entityManager.createNativeQuery("CALL CSVWRITE('"+tasks+"', 'SELECT * FROM TRACKEDTASK');").executeUpdate();
		int activitiesExported = entityManager.createNativeQuery("CALL CSVWRITE('"+activities+"', 'SELECT * FROM ACTIVITY');").executeUpdate();
		// relations are not autmatically created, so we do this the easy way
		entityManager.createNativeQuery("CALL CSVWRITE('"+relations+"', 'SELECT * FROM TRACKEDTASK_ACTIVITY');").executeUpdate();
		transaction.commit();
		return tasksExported+activitiesExported;
	}

	public int importFrom(Path path) throws IOException {
		Path tasks = path.resolve("trackedtask.csv");
		Path activities = path.resolve("activitiy.csv");
		Path relations = path.resolve("trackedtask_activity.csv");
		if (!tasks.toFile().exists()){
			throw new IOException("'trackedtask.csv' does not exist in the specified location.");
		}
		if (!activities.toFile().exists()){
			throw new IOException("'activitiy.csv' does not exist in the specified location.");
		}
		if (!relations.toFile().exists()){
			throw new IOException("'trackedtask_activity.csv' does not exist in the specified location.");
		}
		EntityTransaction transaction = entityManager.getTransaction();
		try {
			transaction.begin();		
			entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE;").executeUpdate();
			int tasksImported = entityManager.createNativeQuery("MERGE INTO TRACKEDTASK (SELECT * FROM CSVREAD('"+tasks+"'));").executeUpdate();
			int activitiesImported = entityManager.createNativeQuery("MERGE INTO ACTIVITY (SELECT * FROM CSVREAD('"+activities+"'));").executeUpdate();
			entityManager.createNativeQuery("MERGE INTO TRACKEDTASK_ACTIVITY (SELECT * FROM CSVREAD('"+relations+"'));").executeUpdate();
			entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE;").executeUpdate();
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
		jdbc_url = Platform.getPreferencesService().getString(BUNDLE_ID, DATABASE_URL, 
				"jdbc:h2:tcp://localhost/~/.timekeeper/h2db", // note use server location per default
				new IScopeContext[] { InstanceScope.INSTANCE });
		return jdbc_url;
	}

}
