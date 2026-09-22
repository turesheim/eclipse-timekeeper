package net.resheim.eclipse.timekeeper.domain;

import java.util.Optional;

/** Optional identity supplied by Mylyn, Jira, GitHub or another task provider. */
public record ExternalTaskReference(String providerId, String repositoryId, String externalId,
		Optional<String> externalUrl) {
	public ExternalTaskReference {
		providerId = Values.required(providerId, "providerId");
		repositoryId = Values.required(repositoryId, "repositoryId");
		externalId = Values.required(externalId, "externalId");
		externalUrl = externalUrl == null ? Optional.empty() : externalUrl
				.map(String::trim).filter(value -> !value.isEmpty());
	}

	public ExternalTaskReference(String providerId, String repositoryId, String externalId, String externalUrl) {
		this(providerId, repositoryId, externalId, Optional.ofNullable(externalUrl));
	}

	public ExternalTaskReferenceKey key() {
		return new ExternalTaskReferenceKey(providerId, repositoryId, externalId);
	}
}
