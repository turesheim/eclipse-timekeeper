/*******************************************************************************
 * Copyright © 2018-2020 Torkild U. Resheim
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

import javax.persistence.Column;
import javax.persistence.Embeddable;

@Embeddable
public class TrackedTaskId implements Serializable {

	private static final long serialVersionUID = 109302590471553755L;

	@Column(name = "REPOSITORY_URL")
	private String repositoryUrl;

	@Column(name = "TASK_ID")
	private String taskId;

	public TrackedTaskId() {
	}

	/**
	 * Create a new primary key based on the URL and the task identifier. This
	 * identifiers will be unique across all repositories.
	 * 
	 * @param repositoryUrl the task repository URL
	 * @param taskId        the task identifier within the repository
	 */
	public TrackedTaskId(String repositoryUrl, String taskId) {
		super();
		this.repositoryUrl = repositoryUrl;
		this.taskId = taskId;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = result * prime + repositoryUrl.hashCode();
		result = result * prime + taskId.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof TrackedTaskId) {
			TrackedTaskId other = (TrackedTaskId) obj;
			return other.getRepositoryUrl().equals(getRepositoryUrl()) && other.getTaskId().equals(getTaskId());
		}
		return false;
	}

	public String getRepositoryUrl() {
		return repositoryUrl;
	}

	public String getTaskId() {
		return taskId;
	}

}
