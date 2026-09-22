/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * Optional identity for a {@link Task} in an external task provider.
 * Timekeeper task identity never depends on this reference.
 */
@Entity
@Table(name = "EXTERNAL_TASK_REFERENCE", uniqueConstraints = @UniqueConstraint(
		name = "UK_EXTERNAL_TASK_REFERENCE",
		columnNames = { "PROVIDER_ID", "REPOSITORY_ID", "EXTERNAL_ID" }))
@NamedQuery(name = "ExternalTaskReference.findTask", query = "SELECT r.task FROM ExternalTaskReference r "
		+ "WHERE r.providerId = :providerId AND r.repositoryId = :repositoryId AND r.externalId = :externalId")
public class ExternalTaskReference implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "ID", nullable = false, updatable = false)
	private String id = UUID.randomUUID().toString();

	@Column(name = "PROVIDER_ID", nullable = false)
	private String providerId;

	@Column(name = "REPOSITORY_ID", nullable = false)
	private String repositoryId;

	@Column(name = "EXTERNAL_ID", nullable = false)
	private String externalId;

	@Column(name = "EXTERNAL_URL")
	private String externalUrl;

	@ManyToOne(optional = false)
	@JoinColumn(name = "TASK_ID", nullable = false)
	private Task task;

	protected ExternalTaskReference() {
	}

	public ExternalTaskReference(String providerId, String repositoryId, String externalId, String externalUrl) {
		this.providerId = normalizeRequired(providerId, "providerId");
		this.repositoryId = normalizeRequired(repositoryId, "repositoryId");
		this.externalId = normalizeRequired(externalId, "externalId");
		this.externalUrl = externalUrl;
	}

	static String normalizeRequired(String value, String name) {
		String result = Objects.requireNonNull(value, name).trim();
		if (result.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
		return result;
	}

	boolean matches(String providerId, String repositoryId, String externalId) {
		return this.providerId.equals(providerId) && this.repositoryId.equals(repositoryId)
				&& this.externalId.equals(externalId);
	}

	void attachTo(Task task) {
		this.task = Objects.requireNonNull(task, "task");
	}

	public String getId() {
		return id;
	}

	public String getProviderId() {
		return providerId;
	}

	public String getRepositoryId() {
		return repositoryId;
	}

	public String getExternalId() {
		return externalId;
	}

	public String getExternalUrl() {
		return externalUrl;
	}

	public void setExternalUrl(String externalUrl) {
		this.externalUrl = externalUrl;
	}

	public Task getTask() {
		return task;
	}
}
