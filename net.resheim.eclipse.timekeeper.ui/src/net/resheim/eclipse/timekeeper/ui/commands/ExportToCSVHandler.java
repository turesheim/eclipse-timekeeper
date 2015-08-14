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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import net.resheim.eclipse.timekeeper.ui.export.CSVExporter;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

public class ExportToCSVHandler extends AbstractHandler {

	private static final DateTimeFormatter fileNameFormat = DateTimeFormatter.ofPattern("'Workweek_'w'-'YYYY");

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPart activePart = HandlerUtil.getActivePart(event);
		if (activePart instanceof WorkWeekView) {
			LocalDate firstDayOfWeek = ((WorkWeekView) activePart).getFirstDayOfWeek();
			CSVExporter export = new CSVExporter();
			String result = export.getData(firstDayOfWeek);
			try {
				FileDialog fd = new FileDialog(activePart.getSite().getShell(), SWT.SAVE);
				fd.setFilterExtensions(new String[] { "csv" });
				fd.setFileName(fileNameFormat.format(firstDayOfWeek));
				String filename = fd.open();
				if (filename != null) {
					FileWriter fw = new FileWriter(new File(filename));
					BufferedWriter bw = new BufferedWriter(fw);
					bw.write(result);
					bw.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return null;
	}

}
