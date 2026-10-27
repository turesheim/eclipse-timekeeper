package net.resheim.eclipse.timekeeper.domain;

import java.util.UUID;

/** Owner-scoped mapping to a provider account; credentials live in the connector. */
public record TaskConnection(UUID id, String providerId, String remoteAccountId, String name,
		boolean readable, boolean writable, boolean enabled, long version) {
	public TaskConnection {
		id = Values.required(id, "connectionId");
		providerId = Values.required(providerId, "providerId");
		remoteAccountId = Values.required(remoteAccountId, "remoteAccountId");
		name = Values.required(name, "name");
		if (writable && !readable) throw new DomainValidationException("writable", "write access requires read access");
		version = Values.version(version);
	}

	public TaskConnection update(String newName, boolean newReadable, boolean newWritable, boolean newEnabled) {
		return new TaskConnection(id, providerId, remoteAccountId, newName, newReadable, newWritable, newEnabled, version + 1);
	}
}
