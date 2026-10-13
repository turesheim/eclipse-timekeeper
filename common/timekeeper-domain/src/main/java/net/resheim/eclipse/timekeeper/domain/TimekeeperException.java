package net.resheim.eclipse.timekeeper.domain;

/** Base type for failures that clients can map without parsing messages. */
public abstract class TimekeeperException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final FailureCode code;
	private final String field;

	protected TimekeeperException(FailureCode code, String field, String message) {
		super(message);
		this.code = code;
		this.field = field;
	}

	public FailureCode code() {
		return code;
	}

	public String field() {
		return field;
	}
}
