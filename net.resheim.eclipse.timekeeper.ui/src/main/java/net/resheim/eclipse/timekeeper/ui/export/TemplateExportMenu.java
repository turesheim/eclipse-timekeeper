/*******************************************************************************
 * Copyright (c) 2019 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.export;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.services.IServiceLocator;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;
import net.resheim.eclipse.timekeeper.ui.commands.TemplateExportHandler;

public class TemplateExportMenu extends CompoundContributionItem implements IWorkbenchContribution {

	private static final String COMMAND_ID = "net.resheim.eclipse.timekeeper.ui.templateExportCommand";

	private IServiceLocator serviceLocator;

	@Override
	protected IContributionItem[] getContributionItems() {
		Map<String, ReportTemplate> templates = TimekeeperPlugin.getTemplates();
		IMenuManager copyMenu = new MenuManager("Copy as");
		IMenuManager saveMenu = new MenuManager("Save as");
		for (String name : templates.keySet()) {
			addToMenu(copyMenu, name, false);
			addToMenu(saveMenu, name, true);
		}
		return new IContributionItem[] { copyMenu, saveMenu };
	}

	private void addToMenu(IMenuManager menu, String name, boolean save) {
		Map<String, String> parameters = new HashMap<>();
		parameters.put(TemplateExportHandler.COMMAND_PARAMETER_TEMPLATE_NAME, name);
		parameters.put(TemplateExportHandler.COMMAND_PARAMETER_FILE, Boolean.toString(save));
		CommandContributionItemParameter contributionParameters = new CommandContributionItemParameter(serviceLocator,
				null, COMMAND_ID, parameters, null, null, null, name, null, null, CommandContributionItem.STYLE_PUSH,
				null, true);
		menu.add(new CommandContributionItem(contributionParameters));
	}

	@Override
	public void initialize(final IServiceLocator serviceLocator) {
		this.serviceLocator = serviceLocator;
	}

}
