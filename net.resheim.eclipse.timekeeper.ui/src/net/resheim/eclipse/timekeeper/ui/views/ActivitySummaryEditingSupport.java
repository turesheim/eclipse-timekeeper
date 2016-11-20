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

import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.TreeViewer;

import net.resheim.eclipse.timekeeper.db.Activity;

/**
 * Enables editing of the activity summary.
 *
 * @author Torkild U. Resheim
 */
class ActivitySummaryEditingSupport extends EditingSupport {

	public ActivitySummaryEditingSupport(TreeViewer viewer) {
		super(viewer);
	}

	@Override
	protected CellEditor getCellEditor(Object element) {
		return new TextCellEditor(((TreeViewer) getViewer()).getTree());
	}

	@Override
	protected boolean canEdit(Object element) {
		if (element instanceof Activity) {
			return true;
		}
		return false;
	}

	@Override
	protected Object getValue(Object element) {
		if (element instanceof Activity) {
			return ((Activity) element).getSummary();
		}
		return "";
	}

	@Override
	protected void setValue(Object element, Object value) {
		if (element instanceof Activity) {
			((Activity) element).setSummary(value.toString());
			getViewer().update(element, null);
		}
	}

}