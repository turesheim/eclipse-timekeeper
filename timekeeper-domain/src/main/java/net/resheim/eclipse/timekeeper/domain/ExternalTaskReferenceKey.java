package net.resheim.eclipse.timekeeper.domain;

/** Provider-scoped identity of an external task. */
public record ExternalTaskReferenceKey(String providerId, String repositoryId, String externalId) {
	public ExternalTaskReferenceKey {
		providerId = Values.required(providerId, "providerId");
		repositoryId = Values.required(repositoryId, "repositoryId");
		externalId = Values.required(externalId, "externalId");
	}
}
