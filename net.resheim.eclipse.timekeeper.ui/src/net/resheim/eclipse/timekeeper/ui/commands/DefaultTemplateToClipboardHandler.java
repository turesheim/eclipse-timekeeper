/*******************************************************************************
 * Copyright (c) 2018 Torkild U. Resheim
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
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.RTFTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;
import net.resheim.eclipse.timekeeper.db.report.TemplateExporter;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

/**
 * This handler takes care of the default action when the user activates the
 * copy current week to clipboard.
 *
 * @author Torkild U. Resheim
 */
public class DefaultTemplateToClipboardHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPart activePart = HandlerUtil.getActivePart(event);
		if (activePart instanceof WorkWeekView) {
			Map<String, ReportTemplate> templates = TimekeeperPlugin.getTemplates();
			IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, TimekeeperPlugin.BUNDLE_ID);
			String defaultTemplate = store.getString(TimekeeperPlugin.PREF_DEFAULT_TEMPLATE);
			LocalDate firstDayOfWeek = ((WorkWeekView) activePart).getFirstDayOfWeek();
			ReportTemplate template = templates.get(defaultTemplate);
			TemplateExporter export = new TemplateExporter(template);
			String result = export.getData(firstDayOfWeek);
			if (result != null) {
				Clipboard clipboard = new Clipboard(Display.getCurrent());
				ByteArrayTransfer targetTransfer = getTransfer(template);
				Transfer[] transfers = new Transfer[] { targetTransfer };
				Object[] data = new Object[] { result };
				clipboard.setContents(data, transfers);
				clipboard.dispose();
			}
		}
		return null;
	}

	/**
	 * Figure out and return the appropriate transfer type. The default is
	 * {@link TextTransfer}.
	 *
	 * @param template
	 *                     the report template
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

}
