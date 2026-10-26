package net.resheim.eclipse.timekeeper.domain;

/** Versioned, provider-owned fields that have no shared task representation yet. */
public record ExternalTaskProviderData(String namespace, int version, String json) {
	public ExternalTaskProviderData {
		namespace = Values.required(namespace, "namespace");
		if (!namespace.matches("[a-z][a-z0-9._-]{0,99}"))
			throw new DomainValidationException("namespace", "invalid provider-data namespace");
		if (version < 1) throw new DomainValidationException("version", "provider-data version must be positive");
		json = Values.required(json, "json");
		if (json.length() > 65536) throw new DomainValidationException("json", "provider data is too large");
	}
}
