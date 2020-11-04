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
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;

/**
 * The {@link Project} type represents a project where time is spent on
 * {@link TrackedTask} {@link Activity}. A project is typically a representation
 * of a Mylyn query or category but can be independent of Mylyn.
 * 
 * @author Torkild U. Resheim
 */
@Entity(name = "PROJECT")
@NamedQuery(name="Project.findAll", query="SELECT p FROM PROJECT p")
public class Project implements Comparable<Project>, Serializable {

	private static final long serialVersionUID = 4761882953730015432L;

	/** The project name */
	@Id
	@Column
	private String name;

	/** The issue tracker repository URL (if any) */
	@Column(name = "REPOSITORY_URL")
	private String repositoryUrl;

	/** For linking to accounting systems etc. */
	@Column(name = "EXTERNAL_ID")
	private String externalId;

	/**
	 * A list of all activities spent directly on the project without being
	 * associated with a particular task.
	 */
	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY, mappedBy = "project")
	private Set<Activity> children;

	/**
	 * A list of all tasks worked on in this project.
	 */
	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY, mappedBy = "project")
	private Set<TrackedTask> tasks;

	protected Project() {
		children = new HashSet<>();
		tasks = new HashSet<>();
	}

	public Project(String title) {
		this();
		this.setName(title);
	}
	
	public Set<TrackedTask> getTasks() {
		return tasks;
	}
	
	public void addTask(TrackedTask task) {
		tasks.add(task);
	}


	@Override
	public int compareTo(Project o) {
		return name.compareTo(o.name); 
	}

	public String getRepositoryUrl() {
		return repositoryUrl;
	}

	public void setRepositoryUrl(String repositoryUrl) {
		this.repositoryUrl = repositoryUrl;
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

}
