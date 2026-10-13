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

import net.resheim.eclipse.timekeeper.client.RemoteFailure;
import net.resheim.eclipse.timekeeper.domain.FailureCode;
import net.resheim.eclipse.timekeeper.service.ServiceException;

final class RecordDialogErrors {
	private RecordDialogErrors() { }
    static void verifyRemoteSnapshot(net.resheim.eclipse.timekeeper.service.TimekeeperService service,Object original) {
        if(original==null || !net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin.getDefault().isRemote()) return;
        if(org.eclipse.swt.widgets.Display.getCurrent()!=null) throw new IllegalStateException("Remote record validation requires a background operation");
        java.util.Optional<?> current;
        if(original instanceof net.resheim.eclipse.timekeeper.domain.Task task) current=service.task(task.id());
        else if(original instanceof net.resheim.eclipse.timekeeper.domain.Project project) current=service.project(project.id());
        else if(original instanceof net.resheim.eclipse.timekeeper.domain.Activity activity) current=service.activity(activity.id());
        else throw new IllegalArgumentException("Unsupported record editor snapshot");
        if(current.isEmpty()) throw new RemoteFailure(RemoteFailure.Kind.NOT_FOUND,"NOT_FOUND");
        // A restored history may reuse a numeric version. Compare the captured fields too;
        // this read supplies the ETag used by the following conditional write.
        if(!original.equals(current.get())) throw new RemoteFailure(RemoteFailure.Kind.CONFLICT,"STALE_VERSION");
    }
    static String editorMessage(RuntimeException failure) {
        if (failure instanceof RemoteFailure problem && problem.kind() == RemoteFailure.Kind.CONFLICT
                && problem.code().equals("STALE_VERSION"))
            return "This record has changed. Close and reopen the editor before saving.";
        return message(failure);
    }
	static String message(RuntimeException failure) {
		if (failure instanceof ServiceException problem && problem.code() == FailureCode.CONFLICT
				&& (problem.field().equals("task") || problem.field().equals("project"))) {
			return "This record has changed. Close and reopen the editor before saving.";
		}
		return failure.getMessage();
	}
}
