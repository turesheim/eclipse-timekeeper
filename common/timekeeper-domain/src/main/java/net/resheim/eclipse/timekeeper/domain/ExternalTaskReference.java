package net.resheim.eclipse.timekeeper.domain;

import java.util.Optional;
import java.util.UUID;

/** Optional identity supplied by Mylyn, Jira, GitHub or another task provider. */
public record ExternalTaskReference(String providerId, String repositoryId, String externalId,
		Optional<String> externalUrl, ExternalTaskSource source, Optional<UUID> taskListId) {
	public ExternalTaskReference {
		providerId = Values.required(providerId, "providerId");
		repositoryId = Values.required(repositoryId, "repositoryId");
		externalId = Values.required(externalId, "externalId");
		externalUrl = externalUrl == null ? Optional.empty() : externalUrl
				.map(String::trim).filter(value -> !value.isEmpty());
		source = source == null ? ExternalTaskSource.empty() : source;
		taskListId = taskListId == null ? Optional.empty() : taskListId;
	}

	public ExternalTaskReference(String providerId, String repositoryId, String externalId,
			Optional<String> externalUrl, ExternalTaskSource source) {
		this(providerId, repositoryId, externalId, externalUrl, source, Optional.empty());
	}

	public ExternalTaskReference(String providerId, String repositoryId, String externalId, Optional<String> externalUrl) {
		this(providerId, repositoryId, externalId, externalUrl, ExternalTaskSource.empty());
	}

	public ExternalTaskReference(String providerId, String repositoryId, String externalId, String externalUrl) {
		this(providerId, repositoryId, externalId, Optional.ofNullable(externalUrl));
	}

	public ExternalTaskReferenceKey key() {
		return new ExternalTaskReferenceKey(providerId, repositoryId, externalId);
	}

	public ExternalTaskReference withSource(ExternalTaskSource newSource) {
		return new ExternalTaskReference(providerId, repositoryId, externalId, externalUrl, newSource, taskListId);
	}

	public ExternalTaskReference withTaskList(Optional<UUID> newTaskListId) {
		return new ExternalTaskReference(providerId, repositoryId, externalId, externalUrl, source, newTaskListId);
	}
}
