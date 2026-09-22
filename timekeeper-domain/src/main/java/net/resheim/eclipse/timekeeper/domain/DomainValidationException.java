package net.resheim.eclipse.timekeeper.domain;

/** A stable validation failure that application adapters can map without parsing text. */
public final class DomainValidationException extends TimekeeperException {
	private static final long serialVersionUID = 1L;

	public DomainValidationException(String field, String message) {
		super(FailureCode.VALIDATION, field, message);
	}
}
