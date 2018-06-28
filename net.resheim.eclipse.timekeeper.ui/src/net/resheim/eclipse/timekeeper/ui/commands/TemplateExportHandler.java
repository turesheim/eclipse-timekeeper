/*******************************************************************************
 * Copyright © 2018 Torkild U. Resheim
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
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.RTFTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;
import net.resheim.eclipse.timekeeper.db.report.TemplateExporter;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

public class TemplateExportHandler extends AbstractHandler implements IHandler {
	/** The template name */
	public static final String COMMAND_PARAMETER_TEMPLATE_NAME = "net.resheim.eclipse.timekeeper.ui.templateExportCommand_name";
	/** Whether or not to write the result to a file */
	public static final String COMMAND_PARAMETER_FILE = "net.resheim.eclipse.timekeeper.ui.templateExportCommand_file";

	private static final DateTimeFormatter fileNameFormat = DateTimeFormatter.ofPattern("'Workweek_'w'-'YYYY");

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		@SuppressWarnings("unchecked")
		Map<String, String> parameters = event.getParameters();
		IWorkbenchPart activePart = HandlerUtil.getActivePart(event);
		if (activePart instanceof WorkWeekView) {
			Map<String, ReportTemplate> templates = TimekeeperPlugin.getTemplates();
			LocalDate firstDayOfWeek = ((WorkWeekView) activePart).getFirstDayOfWeek();
			ReportTemplate template = templates.get(parameters.get(COMMAND_PARAMETER_TEMPLATE_NAME));
			TemplateExporter export = new TemplateExporter(template);
			String result = export.getData(firstDayOfWeek);
			if (result != null) {
				// save to file or copy to clipboard
				boolean save = Boolean.parseBoolean(parameters.getOrDefault(COMMAND_PARAMETER_FILE, "false"));
				if (save) {
					try {
						FileDialog fd = new FileDialog(activePart.getSite().getShell(), SWT.SAVE);
						fd.setFilterExtensions(new String[] { getFileExtension(template) });
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

				} else {
					Clipboard clipboard = new Clipboard(Display.getCurrent());
					ByteArrayTransfer targetTransfer = getTransfer(template);
					Transfer[] transfers = new Transfer[] { targetTransfer };
					Object[] data = new Object[] { result };
					clipboard.setContents(data, transfers);
					clipboard.dispose();
				}
			}
		}
		return null;
	}

	/**
	 * Figure out and return the appropriate transfer type. The default is
	 * {@link TextTransfer}.
	 *
	 * @param template
	 *            the report template
	 * @return the transfer type
	 */
	private ByteArrayTransfer getTransfer(ReportTemplate template) {
		switch (template.getType()) {
		case HTML:
			return HTMLTransfer.getInstance();
		case RTF:
			return RTFTransfer.getInstance();
		default:
			return TextTransfer.getInstance();
		}
	}

	private String getFileExtension(ReportTemplate template) {
		switch (template.getType()) {
		case HTML:
			return "html";
		case RTF:
			return "rtf";
		default:
			return "txt";
		}
	}
}
