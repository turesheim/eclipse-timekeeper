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

package net.resheim.eclipse.timekeeper.ui.export;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.menus.ExtensionContributionFactory;
import org.eclipse.ui.menus.IContributionRoot;
import org.eclipse.ui.services.IServiceLocator;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;
import net.resheim.eclipse.timekeeper.ui.commands.TemplateExportHandler;

/**
 * Creates a menu for exporting data from the currently week to various formats
 * determined by the actual templates available. There are two sub-menus, one
 * for saving to a file and another for copying to the paste buffer.
 *
 * @author Torkild U. Resheim
 */
public class TemplateExportMenu extends ExtensionContributionFactory {

	private static final String COMMAND_ID = "net.resheim.eclipse.timekeeper.ui.templateExportCommand";

	public TemplateExportMenu() {
		// ignore
	}

	@Override
	public void createContributionItems(IServiceLocator serviceLocator, IContributionRoot additions) {
		Map<String, ReportTemplate> templates = TimekeeperPlugin.getTemplates();
		IMenuManager copyMenu = new MenuManager("Copy as");
		IMenuManager saveMenu = new MenuManager("Save as");
		additions.addContributionItem(copyMenu, null);
		additions.addContributionItem(saveMenu, null);
		for (String name : templates.keySet()) {
			addToMenu(serviceLocator, copyMenu, name, false);
			addToMenu(serviceLocator, saveMenu, name, true);
		}
	}

	private void addToMenu(IServiceLocator serviceLocator, IMenuManager menu, String name, boolean file) {
		Map<String, String> parameters = new HashMap<>();
		parameters.put(TemplateExportHandler.COMMAND_PARAMETER_TEMPLATE_NAME, name);
		parameters.put(TemplateExportHandler.COMMAND_PARAMETER_FILE, Boolean.toString(file));
		CommandContributionItemParameter contributionParameters = new CommandContributionItemParameter(serviceLocator,
				null, COMMAND_ID, parameters, null, null, null, name, null, null, CommandContributionItem.STYLE_PUSH,
				null, false);
		menu.add(new CommandContributionItem(contributionParameters));
	}

}
