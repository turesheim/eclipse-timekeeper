package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;
import java.util.Optional;

/** Opaque per-provider sync values; never use a remote revision as a Timekeeper version. */
public record ExternalTaskSource(Optional<Instant> createdAt, Optional<Instant> modifiedAt,
		Optional<String> remoteEtag, Optional<String> remoteRevision) {
	public ExternalTaskSource {
		createdAt = createdAt == null ? Optional.empty() : createdAt;
		modifiedAt = modifiedAt == null ? Optional.empty() : modifiedAt;
		remoteEtag = token(remoteEtag, "remoteEtag");
		remoteRevision = token(remoteRevision, "remoteRevision");
	}

	private static Optional<String> token(Optional<String> value, String field) {
		if (value == null) return Optional.empty();
		return value.map(text -> {
			if (text.isBlank() || text.length() > 4096) throw new DomainValidationException(field, "invalid remote version");
			return text;
		});
	}

	public static ExternalTaskSource empty() { return new ExternalTaskSource(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()); }
}
