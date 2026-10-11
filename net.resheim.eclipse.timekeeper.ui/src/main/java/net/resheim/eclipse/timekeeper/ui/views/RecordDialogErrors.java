/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.views;

import net.resheim.eclipse.timekeeper.domain.FailureCode;
import net.resheim.eclipse.timekeeper.service.ServiceException;

final class RecordDialogErrors {
	private RecordDialogErrors() { }
	static String message(RuntimeException failure) {
		if (failure instanceof ServiceException problem && problem.code() == FailureCode.CONFLICT
				&& (problem.field().equals("task") || problem.field().equals("project"))) {
			return "This record has changed. Close and reopen the editor before saving.";
		}
		return failure.getMessage();
	}
}
