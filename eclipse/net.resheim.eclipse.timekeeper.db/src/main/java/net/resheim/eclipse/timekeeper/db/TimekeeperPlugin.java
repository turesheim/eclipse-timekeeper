/*******************************************************************************
 * Copyright © 2016-2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
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
    private volatile long importGeneration;
    public long importGeneration() {return importGeneration;}

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
		manager.getTransaction().begin();
		for (var label : net.resheim.eclipse.timekeeper.domain.DefaultLabels.definitions()) {
			manager.persist(new ActivityLabel(label.name(), label.rgbColor()));
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
		Path labels = path.resolve("activitylabel.csv");
		Path activityLabels = path.resolve("activity_activitylabel.csv");
        Path workSessions = path.resolve("work_session.csv");
		EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        entityManager.createNativeQuery("MERGE INTO TIMEKEEPER_WRITE_LOCK KEY(ID) VALUES(1)").executeUpdate();
        int sessionsExported=entityManager.createNativeQuery("CALL CSVWRITE('"+workSessions.toString().replace("'","''")+"', 'SELECT * FROM WORK_SESSION');").executeUpdate();
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
		int labelsExported = entityManager.createNativeQuery("CALL CSVWRITE('" + labels + "', 'SELECT * FROM ACTIVITYLABEL');").executeUpdate();
		entityManager.createNativeQuery("CALL CSVWRITE('" + activityLabels + "', 'SELECT * FROM ACTIVITY_ACTIVITYLABEL');").executeUpdate();
		transaction.commit();
		return projectTypesExported + projectsExported + tasksExported + activitiesExported + referencesExported + labelsExported + sessionsExported;
	}

	/**
	 * Import and merge records from the specified location.
	 * 
	 * @param path root location of the
	 * @return
	 * @throws IOException
	 */
	public int importFrom(Path path) throws IOException {
		ActivityCsvValidation.validate(path.resolve("activity.csv"), java.time.Instant.now());
		try (var prepared = ActivityCsvDays.prepare(path, java.time.ZoneId.systemDefault(), java.time.Instant.now())) {
			return importDailyRows(prepared);
		}
	}

    private int mergeCsv(String table,Path file) throws IOException {
        var columns=new java.util.ArrayList<String>();
        try(var data=new org.h2.tools.Csv().read(file.toString(),null,"UTF-8")) {
            var metadata=data.getMetaData();for(int index=1;index<=metadata.getColumnCount();index++) {
                String name=metadata.getColumnName(index).toUpperCase(java.util.Locale.ROOT);
                if(!DatabaseSchema.CURRENT.get(table).contains(name) || columns.contains(name)) throw new IOException("Unexpected CSV column for "+table);
                columns.add(name);
            }
        } catch(java.sql.SQLException failure) {throw new IOException("Could not read CSV columns",failure);}
        String names=columns.stream().map(name -> "\""+name+"\"").collect(java.util.stream.Collectors.joining(","));
        return entityManager.createNativeQuery("MERGE INTO "+table+" ("+names+") SELECT * FROM CSVREAD('"+file.toString().replace("'","''")+"')").executeUpdate();
    }
    private int mergeWorkSessions(Path path) throws IOException {
        if(!Files.isRegularFile(path)) return 0;int count=0;
        try(var rows=new org.h2.tools.Csv().read(path.toString(),null,"UTF-8")) {
            while(rows.next()) {
                String id=rows.getString("ID"),owner=rows.getString("OWNER_ID"),start=rows.getString("START_TIME"),end=rows.getString("END_TIME"),summary=rows.getString("SUMMARY"),state=rows.getString("STATE");
                java.util.UUID.fromString(id);new net.resheim.eclipse.timekeeper.domain.OwnerId(owner);
                long version=rows.getLong("VERSION");if(version<0) throw new IOException("Invalid work session version");
                var existing=entityManager.createNativeQuery("SELECT OWNER_ID,START_TIME,END_TIME,SUMMARY,STATE FROM WORK_SESSION WHERE ID=?1").setParameter(1,id).getResultList();
                if(existing.isEmpty()) entityManager.createNativeQuery("INSERT INTO WORK_SESSION(ID,VERSION,OWNER_ID,START_TIME,END_TIME,SUMMARY,STATE) VALUES(?1,?2,?3,?4,?5,?6,?7)")
                        .setParameter(1,id).setParameter(2,version).setParameter(3,owner).setParameter(4,start).setParameter(5,end).setParameter(6,summary).setParameter(7,state).executeUpdate();
                else {
                    var original=(Object[])existing.getFirst();if(!owner.equals(original[0])) throw new IOException("A work session cannot change owner on import");
                    if(!java.util.Arrays.equals(original,new Object[]{owner,start,end,summary,state})) entityManager.createNativeQuery(
                            "UPDATE WORK_SESSION SET VERSION=VERSION+1,START_TIME=?1,END_TIME=?2,SUMMARY=?3,STATE=?4 WHERE ID=?5")
                            .setParameter(1,start).setParameter(2,end).setParameter(3,summary).setParameter(4,state).setParameter(5,id).executeUpdate();
                }
                count++;
            }
        } catch(java.sql.SQLException | IllegalArgumentException failure) {throw new IOException("Invalid work session CSV",failure);}
        return count;
    }
    private int importDailyRows(ActivityCsvDays prepared) throws IOException {
        Path path=prepared.folder;
        var files=java.util.Map.of("PROJECT_TYPE","project_type.csv","PROJECT","project.csv","TASK","trackedtask.csv","ACTIVITY","activity.csv",
                "TASK_ACTIVITY","trackedtask_activity.csv","PROJECT_TASK","project_task.csv","PROJECT_ACTIVITY","project_activity.csv","EXTERNAL_TASK_REFERENCE","external_task_reference.csv");
        for(var name:files.values()) if(!Files.isRegularFile(path.resolve(name))) throw new IOException("Missing required export file: "+name);
        Path labels=path.resolve("activitylabel.csv"),activityLabels=path.resolve("activity_activitylabel.csv");
        if(Files.exists(labels)!=Files.exists(activityLabels)) throw new IOException("The activity label export is incomplete.");
        EntityTransaction transaction=entityManager.getTransaction();
        try {
            transaction.begin();entityManager.createNativeQuery("MERGE INTO TIMEKEEPER_WRITE_LOCK KEY(ID) VALUES(1)").executeUpdate();
            int imported=0;
            imported+=mergeCsv("PROJECT_TYPE",path.resolve(files.get("PROJECT_TYPE")));
            imported+=mergeCsv("PROJECT",path.resolve(files.get("PROJECT")));
            imported+=mergeCsv("TASK",path.resolve(files.get("TASK")));
            imported+=mergeWorkSessions(path.resolve("work_session.csv"));
            if(Files.exists(labels)) imported+=mergeCsv("ACTIVITYLABEL",labels);
            imported+=mergeCsv("ACTIVITY",path.resolve(files.get("ACTIVITY")));
            for(var table:java.util.List.of("TASK_ACTIVITY","PROJECT_TASK","PROJECT_ACTIVITY")) mergeCsv(table,path.resolve(files.get(table)));
            imported+=mergeCsv("EXTERNAL_TASK_REFERENCE",path.resolve(files.get("EXTERNAL_TASK_REFERENCE")));
            if(Files.exists(activityLabels)) mergeCsv("ACTIVITY_ACTIVITYLABEL",activityLabels);
            else for(var archive:prepared.originals.entrySet()) entityManager.createNativeQuery(
                    "MERGE INTO ACTIVITY_ACTIVITYLABEL SELECT ?1,LABELS_ID FROM ACTIVITY_ACTIVITYLABEL WHERE ACTIVITY_ID=?2")
                    .setParameter(1,archive.getKey()).setParameter(2,archive.getValue()).executeUpdate();
            for(var active:prepared.currentActivities.entrySet()) entityManager.createNativeQuery("UPDATE TASK SET CURRENTACTIVITY_ID=?1 WHERE ID=?2")
                    .setParameter(1,active.getValue()).setParameter(2,active.getKey()).executeUpdate();
            if(!entityManager.createNativeQuery("SELECT t.ID FROM TASK t JOIN ACTIVITY a ON t.CURRENTACTIVITY_ID=a.ID WHERE a.TASK_ID IS DISTINCT FROM t.ID OR a.END_TIME IS NOT NULL").getResultList().isEmpty())
                throw new IOException("An active task must refer to its own open activity");
            // Legacy exports do not name sessions; preserve existing associations when splitting their rows.
            for(var archive:prepared.originals.entrySet()) entityManager.createNativeQuery(
                    "UPDATE ACTIVITY SET WORK_SESSION_ID=COALESCE(WORK_SESSION_ID,(SELECT WORK_SESSION_ID FROM ACTIVITY WHERE ID=?1)) WHERE ID=?2")
                    .setParameter(1,archive.getValue()).setParameter(2,archive.getKey()).executeUpdate();
            var duplicates=entityManager.createNativeQuery("SELECT OWNER_ID FROM WORK_SESSION WHERE END_TIME IS NULL GROUP BY OWNER_ID HAVING COUNT(*)>1").getResultList();
            if(!duplicates.isEmpty()) throw new IOException("Only one work session may run per owner");
            if(!entityManager.createNativeQuery("SELECT a.ID FROM ACTIVITY a JOIN WORK_SESSION s ON a.WORK_SESSION_ID=s.ID WHERE a.OWNER_ID<>s.OWNER_ID").getResultList().isEmpty())
                throw new IOException("An activity and its work session must have the same owner");
            transaction.commit();importGeneration++;entityManager.clear();entityManager.getEntityManagerFactory().getCache().evictAll();
            return imported;
        } catch(RuntimeException | IOException failure) {
            if(transaction.isActive()) transaction.rollback();throw new IOException(failure.getMessage(),failure);
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
