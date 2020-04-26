/*******************************************************************************
 * Copyright (c) 2015 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.commands;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.handlers.HandlerUtil;

import net.resheim.eclipse.timekeeper.db.model.TrackedTask;

public class CopyTaskDetailsToHTML extends AbstractHandler implements IHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		Object obj = ((IStructuredSelection) selection).getFirstElement();
		if (obj instanceof TrackedTask) {
			copyTaskAsHTML((TrackedTask) obj);
		}
		return null;
	}

	public void copyTaskAsHTML(TrackedTask task) {
		StringBuilder sb = new StringBuilder();
		String taskKey = task.getTaskId();
		if (taskKey != null) {
			sb.append("<a href=\"" + task.getTaskUrl() + "\">");
			sb.append(taskKey);
			sb.append("</a>");
			sb.append(": ");
		}
		sb.append(task.getTaskSummary());
		HTMLTransfer textTransfer = HTMLTransfer.getInstance();
		TextTransfer tt = TextTransfer.getInstance();
		Clipboard clipboard = new Clipboard(Display.getCurrent());
		clipboard.setContents(new String[] { sb.toString(), sb.toString() }, new Transfer[] { textTransfer, tt });
		clipboard.dispose();
	}

}
