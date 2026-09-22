/*******************************************************************************
 * Copyright (c) 2022 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.commands;

import java.util.HashSet;
import java.util.UUID;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity;
import net.resheim.eclipse.timekeeper.ui.ActivityLabelMenu;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

/**
 * This command is used to add a label to an activity.
 *
 * @since 2.0
 */
public class ToggleLabelCommand extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		String id = event.getParameter(ActivityLabelMenu.TOGGLE_LABEL_PARAMETER_ID);
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		Object obj = ((IStructuredSelection) selection).getFirstElement();
		if (obj instanceof Activity activity) {
			var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
			var current = service.activity(new ActivityId(UUID.fromString(activity.getId()))).orElseThrow();
			LabelId labelId = new LabelId(UUID.fromString(id));
			var labels = new HashSet<>(current.labelIds());
			if (!labels.add(labelId)) labels.remove(labelId);
			service.updateActivity(new UpdateActivity(current.id(), current.version(), current.start(), current.end(),
					current.summary(), labels));
			IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
			IViewPart view = page.findView(WorkWeekView.VIEW_ID);
			((WorkWeekView) view).refresh(activity);
		}
		return null;
	}

}
