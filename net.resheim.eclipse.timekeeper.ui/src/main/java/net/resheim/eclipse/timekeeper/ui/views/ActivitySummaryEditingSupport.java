/*******************************************************************************
 * Copyright (c) 2017 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.views;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.TreeViewer;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Writes the displayed snapshot through the client API with optimistic version checks. */
class ActivitySummaryEditingSupport extends EditingSupport {
	private final WeekViewContentProvider content;
	ActivitySummaryEditingSupport(TreeViewer viewer, WeekViewContentProvider content) {
		super(viewer);
		this.content = content;
	}
	@Override protected CellEditor getCellEditor(Object element) {
		return new TextCellEditor(((TreeViewer) getViewer()).getTree());
	}
	@Override protected boolean canEdit(Object element) { return element instanceof Activity; }
	@Override protected Object getValue(Object element) { return ((Activity) element).summary(); }
	@Override protected void setValue(Object element, Object value) {
		Activity activity = (Activity) element;
		try {
			TimekeeperUiPlugin.getDefault().getTimekeeperService().updateActivity(new UpdateActivity(
					activity.id(), activity.version(), activity.start(), activity.end(), value.toString(), activity.labelIds()));
		} catch (RuntimeException failure) {
			MessageDialog.openError(getViewer().getControl().getShell(), "Could not edit activity", ActivityDialog.failureMessage(failure));
		}
		content.databaseStateChanged();
	}
}
