package net.resheim.eclipse.timekeeper.domain;

/** Notes retained independently of the legacy task title update. */
public record TaskBody(TaskBodyFormat format, String content) {
	public TaskBody {
		format = Values.required(format, "body.format");
		if (content == null) throw new DomainValidationException("body.content", "body content must not be null");
		if (content.length() > 65536) throw new DomainValidationException("body.content", "body exceeds 65536 characters");
	}

	public static TaskBody empty() { return new TaskBody(TaskBodyFormat.TEXT, ""); }
}
