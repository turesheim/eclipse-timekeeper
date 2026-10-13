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
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;
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
			TemplateExportHandler.export(activePart, template, firstDayOfWeek, false);
		}
		return null;
	}

}
