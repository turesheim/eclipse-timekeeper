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

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.RTFTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;
import net.resheim.eclipse.timekeeper.db.report.TemplateExporter;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
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
		IWorkbenchPart part = HandlerUtil.getActivePart(event);
		if (part instanceof WorkWeekView view) {
			ReportTemplate template = TimekeeperPlugin.getTemplates().get(parameters.get(COMMAND_PARAMETER_TEMPLATE_NAME));
			export(part, template, view.getFirstDayOfWeek(),
					Boolean.parseBoolean(parameters.getOrDefault(COMMAND_PARAMETER_FILE, "false")));
		}
		return null;
	}

	static void export(IWorkbenchPart part, ReportTemplate template, LocalDate firstDay, boolean save) {
		var plugin = TimekeeperUiPlugin.getDefault();
		var shell = part.getSite().getShell();
		if (!plugin.isBackendReady()) {
			MessageDialog.openError(shell, "Could not export week", plugin.backendStatus());
			return;
		}
		if (template == null) {
			MessageDialog.openError(shell, "Could not export week", "Select a report template in Timekeeper preferences.");
			return;
		}
		String filename = null;
		if (save) {
			FileDialog dialog = new FileDialog(shell, SWT.SAVE);
			dialog.setFilterExtensions(new String[] { "*." + getFileExtension(template) });
			dialog.setFileName(fileNameFormat.format(firstDay));
			filename = dialog.open();
			if (filename == null) return;
		}
		Path destination = filename == null ? null : Path.of(filename);
		var zone = TimekeeperUiPlugin.getCalendarZone();
		plugin.runRead("Export Timekeeper week", () -> {
			// The remote service runs on the worker, so this fetches an actual report
			// instead of exporting the UI's temporary loading snapshot.
			String result = new TemplateExporter(template, zone).getData(firstDay, plugin.getTimekeeperService());
			if (result == null) throw new IllegalStateException("The report template could not be rendered.");
			if (destination != null) {
				try { Files.writeString(destination, result, StandardCharsets.UTF_8); }
				catch (IOException failure) { throw new UncheckedIOException(failure); }
			}
			return result;
		}, result -> {
			if (shell.isDisposed() || destination != null) return;
			Clipboard clipboard = new Clipboard(shell.getDisplay());
			try { clipboard.setContents(new Object[] { result }, new Transfer[] { getTransfer(template) }); }
			finally { clipboard.dispose(); }
		}, failure -> {
			if (!shell.isDisposed()) MessageDialog.openError(shell, "Could not export week", failure.getMessage());
		});
	}

	/**
	 * Figure out and return the appropriate transfer type. The default is
	 * {@link TextTransfer}.
	 *
	 * @param template
	 *            the report template
	 * @return the transfer type
	 */
	private static ByteArrayTransfer getTransfer(ReportTemplate template) {
		switch (template.getType()) {
		case HTML:
			return HTMLTransfer.getInstance();
		case RTF:
			return RTFTransfer.getInstance();
		default:
			return TextTransfer.getInstance();
		}
	}

	private static String getFileExtension(ReportTemplate template) {
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
