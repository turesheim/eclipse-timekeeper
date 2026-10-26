package net.resheim.eclipse.timekeeper.domain;

import java.time.Instant;

/** Last field values agreed between Timekeeper and one provider identity. */
public record ExternalTaskBaseline(int version, Instant capturedAt, String json) {
	public ExternalTaskBaseline {
		if(version<1) throw new DomainValidationException("baselineVersion","baseline version must be positive");
		capturedAt=Values.required(capturedAt,"baselineCapturedAt");
		json=Values.required(json,"baselineFields");
		if(json.length()>65536 || !json.startsWith("{") || !json.endsWith("}"))
			throw new DomainValidationException("baselineFields","invalid baseline fields");
	}
}
