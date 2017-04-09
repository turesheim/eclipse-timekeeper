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
import java.net.URISyntaxException;
import java.net.URL;
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
import javax.persistence.TypedQuery;

import org.eclipse.core.resources.ISaveContext;
import org.eclipse.core.resources.ISaveParticipant;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
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

	public static final String KEY_VALUELIST_ID = "net.resheim.eclipse.timekeeper"; //$NON-NLS-1$
	
	public static final String BUNDLE_ID = "net.resheim.eclipse.timekeeper.db"; //$NON-NLS-1$

	private static TimekeeperPlugin instance;

	private static final EntityManager entityManager;		
	
	// Database configuration
	static {
		Map<String, Object> props = new HashMap<String, Object>();
		// default location
		String jdbc_url = "jdbc:h2:~/.timekeeper/h2db";
		try {
			// use workspace relative path or specified url
			Location instanceLocation = Platform.getInstanceLocation();			
			URL dataArea = instanceLocation.getURL();
			Path path = Paths.get(dataArea.toURI()).resolve(".timekeeper");
			if (!path.toFile().exists()) {
				Files.createDirectory(path);
			}
			jdbc_url = Platform.getPreferencesService().getString(BUNDLE_ID, "database-url", "jdbc:h2:"+path+"/h2db", new IScopeContext[] {InstanceScope.INSTANCE});			
			// baseline the database
	        Flyway flyway = new Flyway();
	        flyway.setDataSource(jdbc_url, "sa", "");
	        flyway.setLocations("classpath:/db/");
	        flyway.setBaselineOnMigrate(true);
	        flyway.migrate();
		} catch (IOException | URISyntaxException e) {
			e.printStackTrace();
		}
		
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
		// we want flyway to create the database, it gives us better control over migrating
		props.put(PersistenceUnitProperties.DDL_GENERATION, "none");
		entityManager = new PersistenceProvider()	
		.createEntityManagerFactory("net.resheim.eclipse.timekeeper.db", props)
		.createEntityManager();
	};		
	
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
						Collection<AbstractTask> allTasks = TasksUiPlugin.getTaskList().getAllTasks();
						EntityTransaction transaction = entityManager.getTransaction();
						transaction.begin();
						for (AbstractTask abstractTask : allTasks) {
							TrackedTask task = getTask(abstractTask);
							entityManager.persist(task);
						}
						transaction.commit();
						return Status.OK_STATUS;
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
        cleanTaskActivities();
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
		Job cleanJob = new Job("Clean up Timekeeper database"){

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				long ps = System.currentTimeMillis();
				TypedQuery<TrackedTask> createQuery = entityManager.createQuery("SELECT tt FROM TrackedTask tt",
						TrackedTask.class);
				List<TrackedTask> resultList = createQuery.getResultList();
				for (TrackedTask trackedTask : resultList) {
					if (trackedTask.getCurrentActivity().isPresent()) {
						ITask task = getTask(trackedTask);
						if (!task.isActive()) {
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
				long pe = System.currentTimeMillis();
				return new Status(IStatus.INFO,BUNDLE_ID,String.format("Cleaned up Timekeeper database in %dms",(pe-ps)));
			}			
		};
		cleanJob.setSystem(false);
		cleanJob.schedule();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		super.stop(context);
	}
	
	/**
	 * Returns the {@link TrackedTask} with the given identifier.
	 * 
	 * @param id the tracked task identifier
	 * @return
	 */
	public Optional<TrackedTask> getTask(String id) {
		// attempt to load the task from the persistent storage
		TrackedTask found = entityManager.find(TrackedTask.class, id);
		return Optional.ofNullable(found);
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
		String id = task.getAttribute(TrackedTask.KEY_IDENTIFIER_ATTR);
		if (id == null) {
			// no such tracked task exists, create one
			TrackedTask tt = new TrackedTask(task);
			return tt;
		} else {
			Optional<TrackedTask> optional = getTask(id);
			if (optional.isPresent()) {
				return optional.get();
			} else { 
				TrackedTask tt = new TrackedTask(task);
				entityManager.persist(tt);
				return tt;				
			}
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
		// get the repository then find the task, seems like the Mylyn API is
		// a bit limited in this area
		Optional<TaskRepository> tr = TasksUi.getRepositoryManager().getAllRepositories()
				.stream()
				.filter(r -> r.getRepositoryUrl().equals(task.getRepositoryUrl()))
				.findFirst();
		if (tr.isPresent()) {
			return TasksUi.getRepositoryModel().getTask(tr.get(), task.getTaskId());
		}
		return null;		
	}

}
