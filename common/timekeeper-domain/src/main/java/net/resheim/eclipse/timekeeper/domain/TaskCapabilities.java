package net.resheim.eclipse.timekeeper.domain;

import java.util.Map;

/** Versioned per-connection capability declaration; unlisted fields are unknown. */
public record TaskCapabilities(int version, String providerApiVersion, Map<String,TaskFieldCapability> fields) {
	public TaskCapabilities {
		if(version<1) throw new DomainValidationException("capabilityVersion","version must be positive");
		providerApiVersion=Values.required(providerApiVersion,"providerApiVersion").trim();
		if(providerApiVersion.length()>200 || providerApiVersion.codePoints().anyMatch(Character::isISOControl))
			throw new DomainValidationException("providerApiVersion","invalid provider API version");
		fields=fields==null?Map.of():Map.copyOf(fields);
		if(fields.size()>200 || fields.keySet().stream().anyMatch(key -> key==null || !key.matches("[a-z][A-Za-z0-9.]{0,99}")))
			throw new DomainValidationException("capabilityFields","invalid field key or too many fields");
	}
	public static TaskCapabilities unknown() { return new TaskCapabilities(1,"unknown",Map.of()); }
}
