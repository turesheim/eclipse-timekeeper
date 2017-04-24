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

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BinaryOperator;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Transient;

import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.tasks.core.IRepositoryManager;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.ui.TasksUi;

import net.resheim.eclipse.timekeeper.db.converters.LocalDateTimeAttributeConverter;

/**
 * A {@link TrackedTask} is the persisted link to an {@link AbstractTask}. It
 * holds a number of {@link Activity} instances which each represent a period of
 * work on the task.
 * <p>
 * Mylyn tasks from the same repository in multiple workspaces must have the ID
 * field updated.
 * </p>
 * 
 * @author Torkild U. Resheim
 */
@SuppressWarnings("restriction")
@Entity(name = "TRACKEDTASK")
@IdClass(value = TrackedTaskId.class)
public class TrackedTask implements Serializable {

	private static final String LOCAL_REPO_ID = "local";
	private static final String LOCAL_REPO_KEY_ID = "net.resheim.eclipse.timekeeper.repo-id"; //$NON-NLS-1$

	private static final long serialVersionUID = 2025738836825780128L;

	@Id
	@Column(name = "REPOSITORY_URL")
	private String repositoryUrl;

	@Id
	@Column(name = "TASK_ID")
	private String taskId;

	@OneToOne
	@JoinColumn(name = "CURRENTACTIVITY_ID")
	private Activity currentActivity;

	/** The last time the task was active while the user was not idle */
	@Convert(converter = LocalDateTimeAttributeConverter.class)
	@Column(name = "TICK")
	private LocalDateTime tick;
	
	@OneToMany(cascade = javax.persistence.CascadeType.ALL)
	private List<Activity> activities;
		
	@Transient
	private ITask task;
		
	protected TrackedTask() {
		activities = new ArrayList<>();
	}

	/**
	 * Creates a new tracked task and associates the instance with the given
	 * Mylyn task.
	 * 
	 * @param task
	 *            the associated Mylyn task
	 */
	public TrackedTask(ITask task) {
		this();
		setTask(task);
	}

	public void addActivity(Activity activity) {
		activities.add(activity);
	}

	/**
	 * Ends the current activity.
	 * 
	 * @return the current activity
	 * @see #startActivity()
	 * @see #endActivity(LocalDateTime)
	 */
	public void endActivity() {
		if (currentActivity !=null) {
			synchronized (currentActivity) {
				if (currentActivity.getEnd()==null){
					currentActivity.setEnd(LocalDateTime.now());
				}
				currentActivity = null;
			}
		}
	}

	/**
	 * Ends the current activity.
	 * 
	 * @param time
	 *            the date and time the activity was ended
	 * @return the current activity
	 * @see #startActivity()
	 * @see #endActivity()
	 */
	public void endActivity(LocalDateTime time) {
		if (currentActivity !=null) {
			synchronized (currentActivity) {
				currentActivity.setEnd(LocalDateTime.now());
				currentActivity = null;
			}
		}
	}
	/**
	 * Returns a list of all activities associated with this task. These may
	 * span over several days, months or be concentrated to one single day.
	 * 
	 * @return
	 */
	public List<Activity> getActivities() {
		return activities;
	}

	/**
	 * Returns the current activity
	 * 
	 * @return the current activity.
	 */
	public Optional<Activity> getCurrentActivity() {
		return Optional.ofNullable(currentActivity);
	}

	/**
	 * Returns the duration of work on this task at the given date. This is
	 * accumulated from all the recorded activities between 00:00 and 23:59 on
	 * that day.
	 * 
	 * @param date
	 *            the date to get duration for
	 * @return the total duration of work on the date
	 */
	public Duration getDuration(LocalDate date) {
		Duration total = Duration.ZERO;
		// sum up the duration
		return getActivities()
				.stream()
				.map(a -> a.getDuration(date))
				.reduce(total, new BinaryOperator<Duration>() {
					@Override
					public Duration apply(Duration t, Duration u) {
						return t.plus(u);
					}
		});
	}
	
	/**
	 * Returns the last time the task was active and not idle
	 * 
	 * @return the last time the task was active and not idle
	 */
	public LocalDateTime getTick() {
		return tick;
	}

	/**
	 * Migrates time tracking data from the Mylyn key-value store to the
	 * database. A new {@link TrackedTask} will be created and {@link Activity}
	 * instances for each of the days work has been done on the task.
	 */
	public void migrate() {
		ITask task = (this.task) == null ? TimekeeperPlugin.getDefault().getTask(this) : this.task;
		String attribute = task.getAttribute(TimekeeperPlugin.KEY_VALUELIST_ID);
		if (attribute == null) {
			// nothing to migrate
			return;
		}
		String[] split = attribute.split(";");
		for (String string : split) {
			if (string.length() > 0) {
				String[] kv = string.split("=");
				LocalDate parsed = LocalDate.parse(kv[0]);
				Activity recordedActivity = new Activity(this, parsed.atStartOfDay());
				recordedActivity.setEnd(parsed.atStartOfDay().plus(Long.parseLong(kv[1]), ChronoUnit.SECONDS));
				addActivity(recordedActivity);
			}
		}
		// clear values we won't need any longer
		task.setAttribute(TimekeeperPlugin.KEY_VALUELIST_ID, null);
		task.setAttribute("start", null);
	}

	/**
	 * Associates given Mylyn Task with this instance. If the Mylyn task already
	 * have a Timekeeper identifier attribute it will be used as an identifier
	 * for this instance, otherwise an indentifier string will be created.
	 * 
	 * @param task
	 *            the Mylyn task
	 */
	void setTask(ITask task) {
		// associate this tracked task with the Mylyn task
		this.task = task;
		taskId = task.getTaskId();
		repositoryUrl = getRepositoryUrl(task);

		// we have an old fashioned value here. migrate the old data 
		if (task.getAttribute(TimekeeperPlugin.KEY_VALUELIST_ID) != null) {
			try {
				migrate();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * This method will return the repository URL for tasks in repositories that
	 * are not local. If the task is in a local repository, the Timekeeper
	 * repository identifier is returned if it exists. If it does not exist, it
	 * will be created, associated with the repository and returned.
	 * 
	 * @param task
	 *            the task to get the repository URL for
	 * @return the repository URL or {@link UUID}
	 */
	public static String getRepositoryUrl(ITask task) {
		String url = task.getRepositoryUrl();
		if (LOCAL_REPO_ID.equals(task.getRepositoryUrl())){
			IRepositoryManager repositoryManager = TasksUi.getRepositoryManager();
			if (repositoryManager == null) { // may happen during testing
				return LOCAL_REPO_ID;
			}			
			TaskRepository repository = repositoryManager.getRepository(task.getConnectorKind(), task.getRepositoryUrl());
			String id = repository.getProperty(LOCAL_REPO_KEY_ID);
			if (id == null) {
				id = LOCAL_REPO_ID+"-"+UUID.randomUUID().toString();
				repository.setProperty(LOCAL_REPO_KEY_ID, id);
			}
			url = id;
		}
		return url;
	}
	

	/**
	 * Sets the last time the task was active while the user was not idle.

	 * @param tick the tick time
	 */
	public void setTick(LocalDateTime tick) {
		this.tick = tick;
	}

	/**
	 * Starts a new activity and sets this as the current activity. If there is
	 * already another activity active this will be returned.
	 * 
	 * @return the current activity
	 * @see #getCurrentActivity()
	 */
	public Activity startActivity() {
		if (currentActivity == null) {
			currentActivity = new Activity(this, LocalDateTime.now());
			addActivity(currentActivity);
			return currentActivity;
		}
		return currentActivity;
	}

	public String getRepositoryUrl() {
		return repositoryUrl;
	}

	public String getTaskId() {
		return taskId;
	}

	/**
	 * Returns the {@link ITask} if available. If not, it can be obtained from
	 * {@link TimekeeperPlugin#getTask(TrackedTask)} which will look in the task
	 * repository.
	 * 
	 * @return the {@link ITask} or <code>null</code>
	 */
	public ITask getTask() {
		return task;
	}

}
