/*******************************************************************************
 * Copyright © 2020 Torkild U. Resheim
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
import java.util.HashSet;
import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;

import org.eclipse.mylyn.internal.tasks.core.Category;

/**
 * The {@link Project} type represents a project where time is spent on
 * {@link Task} {@link Activity}. A project is typically a representation of a
 * Mylyn query or category but can be independent of Mylyn.
 * 
 * A project can be a Mylyn {@link Category} which is typically a query into one
 * of the task repositories added, or it can also be independent of Mylyn. There
 * is currently no way of manually adding a project to the workweek view.
 * 
 * @author Torkild U. Resheim
 */
@Entity
@Table(name = "PROJECT")
@NamedQuery(name="Project.findAll", query="SELECT p FROM Project p")
public class Project implements Comparable<Project>, Serializable {
	
	private static final long serialVersionUID = 4761882953730015432L;

	/** The project name */
	@Id
	@Column
	private String name;

	/** The issue tracker / tasks URL (if any) */
	@Column(name = "TASKS_URL")
	private String tasksUrl;

	/** The project URL (if any) */
	@Column(name = "PROJECT_URL")
	private String projectUrl;

	/** The source repository URL (if any) */
	@Column(name = "REPOSITORY_URL")
	private String repositoryUrl;
	
	/** The project type */
	@JoinColumn(name = "TYPE")
	private ProjectType projectType;

	/** For linking to accounting systems etc. */
	@Column(name = "EXTERNAL_ID")
	private String externalId;

	/**
	 * A list of all activities spent directly on the project without being
	 * associated with a particular task.
	 */
	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	private Set<Activity> children;

	/**
	 * A list of all tasks worked on in this project.
	 */
	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	private Set<Task> tasks;

	protected Project() {
		children = new HashSet<>();
		tasks = new HashSet<>();
	}

	public Project(ProjectType type, String title) {
		this();
		this.setName(title);
		this.setProjectType(type);
	}
	
	public Set<Task> getTasks() {
		return tasks;
	}
	
	public void addTask(Task task) {
		tasks.add(task);
	}


	@Override
	public int compareTo(Project o) {
		return name.compareTo(o.name); 
	}

	public String getTasksUrl() {
		return tasksUrl;
	}

	public void setTasksUrl(String tasksUrl) {
		this.tasksUrl = tasksUrl;
	}

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getProjectUrl() {
		return projectUrl;
	}

	public void setProjectUrl(String projectUrl) {
		this.projectUrl = projectUrl;
	}

	public String getRepositoryUrl() {
		return repositoryUrl;
	}

	public void setRepositoryUrl(String repositoryUrl) {
		this.repositoryUrl = repositoryUrl;
	}

	public ProjectType getProjectType() {
		return projectType;
	}

	public void setProjectType(ProjectType projectType) {
		this.projectType = projectType;
	}

}
