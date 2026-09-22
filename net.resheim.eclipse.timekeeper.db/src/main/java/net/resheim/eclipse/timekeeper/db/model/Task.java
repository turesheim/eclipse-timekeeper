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
package net.resheim.eclipse.timekeeper.db.model;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.Version;

import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskCategory;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
import org.eclipse.mylyn.tasks.core.IRepositoryQuery;
import org.eclipse.mylyn.tasks.core.ITask;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.converters.InstantAttributeConverter;

/**
 * A provider-independent unit of work tracked by Timekeeper. It holds a number
 * of {@link Activity} instances and can optionally be linked to Mylyn or other
 * external task providers through {@link ExternalTaskReference} instances.
 * 
 * @author Torkild U. Resheim
 */
@SuppressWarnings("restriction")
@Entity
@Table(name = "TASK")
@NamedQuery(name="Task.findAll", query="SELECT t FROM Task t")
public class Task implements Serializable {
	
	private static final long serialVersionUID = -2455754936217658613L;

	@Transient
	private transient Lock lock = new ReentrantLock();

	@Id
	@Column(name = "ID", nullable = false, updatable = false)
	private String id = UUID.randomUUID().toString();

	@Version
	@Column(name = "VERSION", nullable = false)
	private long version;

	@ManyToOne
	@JoinColumn(name = "TASK_PROJECT")
	private Project taskProject;

	@ManyToOne
	@JoinColumn(name = "PARENT_TASK")
	private Task parentTask;

	@Column(name = "TASK_URL")
	private String taskUrl;

	@Column(name = "TASK_SUMMARY")
	private String taskSummary;

	@OneToOne
	@JoinColumn(name = "CURRENTACTIVITY_ID")
	private Activity currentActivity;

	/** The last time the task was active while the user was not idle */
	@Convert(converter = InstantAttributeConverter.class)
	@Column(name = "TICK", columnDefinition = "VARCHAR(30)")
	private Instant tick;

	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	private List<Activity> activities;

	@OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("providerId ASC, repositoryId ASC, externalId ASC")
	private List<ExternalTaskReference> externalReferences;
	
	/** Optional link to a Mylyn task */
	@Transient
	private transient ITask mylynTask;
	
	/**
	 * Used to determine whether or not this task has been linked to a corresponding
	 * Mylyn task or if an attempt has been made.
	 */
	@Transient
	private transient TaskLinkStatus taskLinkStatus = TaskLinkStatus.UNDETERMINED;

	public Task() {
		activities = new ArrayList<>();
		externalReferences = new ArrayList<>();
	}

	/** Creates a native Timekeeper task with no external provider dependency. */
	public Task(String summary) {
		this();
		setTaskSummary(summary);
	}

	public Task(String id, String summary) {
		this(summary);
		this.id = id;
	}

	/**
	 * Creates a new tracked task and associates the instance with the given Mylyn
	 * task.
	 * 
	 * @param task the associated Mylyn task
	 */
	public Task(ITask task) {
		this(task.getSummary());
		linkWithMylynTask(task);
		if (taskProject != null) {
			taskProject.addTask(this);
		}
	}

	public void addActivity(Activity activity) {
		if (!activities.contains(activity)) activities.add(activity);
	}

	public void removeActivity(Activity activity) {
		activities.remove(activity);
		if (currentActivity == activity) currentActivity = null;
	}

	public void setCurrentActivity(Activity activity) {
		currentActivity = activity;
		if (activity != null) addActivity(activity);
	}

	/**
	 * Ends the current activity.
	 * 
	 * @return the current activity
	 * @see #startActivity()
	 * @see #endActivity(Instant)
	 */
	public Activity endActivity() {
		Activity returnActivity = null;
		if (currentActivity != null) {
			lock.lock();
			if (currentActivity.getEnd() == null) {
				currentActivity.setEnd(Instant.now());
			}
			returnActivity = currentActivity;
			currentActivity = null;
			lock.unlock();
		}
		return returnActivity;
	}

	/**
	 * Ends the current activity.
	 * 
	 * @param time the date and time the activity was ended
	 * @return the current activity
	 * @see #startActivity()
	 * @see #endActivity()
	 */
	public void endActivity(Instant time) {
		if (currentActivity != null) {
			lock.lock();
			currentActivity.setEnd(time);
			currentActivity = null;
			lock.unlock();
		}
	}

	/**
	 * Returns a list of all activities associated with this task. These may span
	 * over several days, months or be concentrated to one single day.
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
	 * accumulated from all the recorded activities between 00:00 and 23:59 on that
	 * day.
	 * 
	 * @param date the date to get duration for
	 * @param zoneId the calendar time zone that defines the date boundaries
	 * @return the total duration of work on the date
	 */
	public Duration getDuration(LocalDate date, ZoneId zoneId) {
		Duration total = Duration.ZERO;
		// sum up the duration
		return getActivities()
				.stream()
				.map(a -> a.getDuration(date, zoneId))
				.reduce(total, (t, u) -> t.plus(u));
	}

	public String getTaskUrl() {
		return taskUrl;
	}

	public void setTaskUrl(String taskUrl) {
		this.taskUrl = taskUrl;
	}

	public String getTaskSummary() {
		return taskSummary;
	}

	public void setTaskSummary(String taskSummary) {
		this.taskSummary = taskSummary;
	}

	/**
	 * Returns the last time the task was active and not idle
	 * 
	 * @return the last time the task was active and not idle
	 */
	public Instant getTick() {
		return tick;
	}

	/**
	 * Associates given Mylyn Task with this instance.
	 * 
	 * @param task the Mylyn task
	 */
	public void linkWithMylynTask(ITask task) {
		// associate this tracked task with the Mylyn task
		this.mylynTask = task;
		if (task == null) {
			// Unlink without erasing the persisted identity or reporting metadata.
			taskLinkStatus = TaskLinkStatus.UNLINKED;
			return;
		}
		String providerId = task.getConnectorKind() == null ? "mylyn" : task.getConnectorKind();
		String repositoryId = TimekeeperPlugin.getRepositoryUrl(task);
		linkExternalTask(providerId, repositoryId, task.getTaskId(), task.getUrl());
		taskUrl = task.getUrl();
		taskSummary = task.getSummary();
		taskLinkStatus = TaskLinkStatus.LINKED;

		// figure out the project name and set this
		if (task instanceof AbstractTask) {
			Set<AbstractTaskContainer> parentContainers = ((AbstractTask) task).getParentContainers();
			parentContainers.forEach(p -> {
				String projectName = null;
				// it's a remote task
				if (p instanceof IRepositoryQuery) {
					projectName = TimekeeperPlugin.getMylynProjectName(task);
				}
				// it's a local task
				if (p instanceof AbstractTaskCategory) {
					projectName = p.getSummary();
				}
				// the project is probably already in the database
				Project project = TimekeeperPlugin.getProject(projectName);
				if (project == null) {
					project = TimekeeperPlugin.createAndSaveProject(task);
				}
				this.setProject(project);
			});
		}
	}

	/** Adds or refreshes an optional external identity for this Timekeeper task. */
	public ExternalTaskReference linkExternalTask(String providerId, String repositoryId, String externalId,
			String externalUrl) {
		providerId = ExternalTaskReference.normalizeRequired(providerId, "providerId");
		repositoryId = ExternalTaskReference.normalizeRequired(repositoryId, "repositoryId");
		externalId = ExternalTaskReference.normalizeRequired(externalId, "externalId");
		String normalizedProviderId = providerId;
		String normalizedRepositoryId = repositoryId;
		String normalizedExternalId = externalId;
		ExternalTaskReference reference = externalReferences.stream()
				.filter(candidate -> candidate.matches(normalizedProviderId, normalizedRepositoryId, normalizedExternalId))
				.findFirst().orElseGet(() -> {
					ExternalTaskReference created = new ExternalTaskReference(normalizedProviderId,
							normalizedRepositoryId, normalizedExternalId, externalUrl);
					created.attachTo(this);
					externalReferences.add(created);
					return created;
				});
		reference.setExternalUrl(externalUrl);
		return reference;
	}

	public void unlinkExternalTask(String providerId, String repositoryId, String externalId) {
		externalReferences.removeIf(reference -> reference.matches(providerId, repositoryId, externalId));
	}

	public List<ExternalTaskReference> getExternalReferences() {
		return Collections.unmodifiableList(externalReferences);
	}

	private Optional<ExternalTaskReference> firstExternalReference() {
		return externalReferences.stream().findFirst();
	}

	/**
	 * Sets the last time the task was active while the user was not idle.
	 * 
	 * @param tick the tick time
	 */
	public void setTick(Instant tick) {
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
		return startActivity(OwnerIdentity.LOCAL, Instant.now());
	}

	/** Starts an activity for an explicit owner at an explicit instant. */
	public Activity startActivity(OwnerIdentity owner, Instant start) {
		if (currentActivity == null) {
			lock.lock();
			currentActivity = new Activity(this, owner, start);
			addActivity(currentActivity);
			lock.unlock();
			return currentActivity;
		}
		return currentActivity;
	}

	/**
	 * Compatibility accessor returning the first external repository in stable
	 * provider/repository/ID order. Internally an UUID for each Eclipse
	 * workspace is postfixed the local repository URL in order to keep them apart.
	 * This method will only return "local" for local repositories.
	 * 
	 * @return the repository URL or "local"
	 */
	public String getRepositoryUrl() {
		String repositoryUrl = firstExternalReference().map(ExternalTaskReference::getRepositoryId).orElse(null);
		if (repositoryUrl != null && repositoryUrl.startsWith("local-")) {
			return TimekeeperPlugin.KIND_LOCAL;
		}
		return repositoryUrl;
	}

	/**
	 * Compatibility accessor returning the first external task identifier in stable
	 * provider/repository/ID order. If it's a
	 * local task, only the number will be returned and one would have to use the
	 * repository to correctly identify the {@link ITask} instance. If the task is
	 * linked to a Mylyn task, this task's identifier will be returned.
	 * 
	 * @return the task identifier
	 */
	public String getTaskId() {
		return firstExternalReference().map(ExternalTaskReference::getExternalId).orElse(null);
	}

	/** Returns the provider-independent Timekeeper identity. */
	public String getId() {
		return id;
	}

	public long getVersion() {
		return version;
	}

	/**
	 * Returns the referenced {@link ITask} if available. If not, it can be obtained
	 * from {@link TimekeeperPlugin#getMylynTask(Task)} which will examine the
	 * Mylyn task repository.
	 * 
	 * @return the {@link ITask} or <code>null</code>
	 */
	public ITask getMylynTask() {
		return mylynTask;
	}

	public Project getProject() {
		return taskProject;
	}

	public Task getParentTask() {
		return parentTask;
	}

	public void setParentTask(Task parentTask) {
		this.parentTask = parentTask;
	}

	public void setProject(Project project) {
		if (this.taskProject == project) return;
		if (this.taskProject != null) this.taskProject.removeTask(this);
		this.taskProject = project;
		if (this.taskProject != null) this.taskProject.addTask(this);
	}
	
	public TaskLinkStatus getTaskLinkStatus() {
		return taskLinkStatus;
	}

	public void setTaskLinkStatus(TaskLinkStatus taskLinkStatus) {
		this.taskLinkStatus = taskLinkStatus;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(getTaskId() == null ? id : getTaskId());
		sb.append(": ");
		sb.append(getTaskSummary());
		return sb.toString();
	}

}
