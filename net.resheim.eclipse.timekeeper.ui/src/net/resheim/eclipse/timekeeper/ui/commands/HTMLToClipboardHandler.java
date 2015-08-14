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

import java.time.LocalDate;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import net.resheim.eclipse.timekeeper.ui.export.HTMLExporter;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

public class HTMLToClipboardHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPart activePart = HandlerUtil.getActivePart(event);
		if (activePart instanceof WorkWeekView) {
			LocalDate firstDayOfWeek = ((WorkWeekView) activePart).getFirstDayOfWeek();
			HTMLExporter export = new HTMLExporter();
			String result = export.getData(firstDayOfWeek);
			HTMLTransfer textTransfer = HTMLTransfer.getInstance();
			TextTransfer tt = TextTransfer.getInstance();
			Clipboard clipboard = new Clipboard(Display.getCurrent());
			clipboard.setContents(new String[] { result, result }, new Transfer[] { textTransfer, tt });
			clipboard.dispose();
		}
		return null;
	}

}
