package net.resheim.eclipse.timekeeper.service;

import net.resheim.eclipse.timekeeper.domain.FailureCode;
import net.resheim.eclipse.timekeeper.domain.TimekeeperException;

/** A stable application failure such as a missing resource or version conflict. */
public final class ServiceException extends TimekeeperException {
	private static final long serialVersionUID = 1L;

	public ServiceException(FailureCode code, String field, String message) {
		super(code, field, message);
	}

	public static ServiceException notFound(String field, Object id) {
		return new ServiceException(FailureCode.NOT_FOUND, field, field + " not found: " + id);
	}

	public static ServiceException conflict(String field, String message) {
		return new ServiceException(FailureCode.CONFLICT, field, message);
	}
}
