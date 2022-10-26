/*******************************************************************************
 * Copyright (c) 2022 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.services.IServiceLocator;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;

/**
 * This menu presents all available labels for an activity with image and text.
 * If an label has already been added to the activity, the label is removed when
 * an entry is activated, otherwise the label is added.
 */
public class ActivityLabelMenu extends CompoundContributionItem implements IWorkbenchContribution {
	// --> toggleLabelCommand
	public static final String TOGGLE_LABEL_COMMAND_ID = "net.resheim.eclipse.timekeeper.ui.toggleLabelCommand";
	public static final String TOGGLE_LABEL_PARAMETER_ID = "net.resheim.eclipse.timekeeper.ui.toggleLabelCommand_id";

	private IServiceLocator serviceLocator;
	private ActivityLabelPainter labelPainter;

	@Override
	public void initialize(final IServiceLocator serviceLocator) {
		this.serviceLocator = serviceLocator;
		this.labelPainter = new ActivityLabelPainter();
	}

	@Override
	protected IContributionItem[] getContributionItems() {
		return TimekeeperPlugin.getLabels().map(label -> addToMenu(label)).toArray(IContributionItem[]::new);
	}

	private IContributionItem addToMenu(ActivityLabel label) {
		Map<String, String> parameters = new HashMap<>();
		parameters.put(TOGGLE_LABEL_PARAMETER_ID, label.getId());
		Image image = labelPainter.getLabelImage(label, 16, false);
		ImageDescriptor id = ImageDescriptor.createFromImage(image);
		CommandContributionItemParameter contributionParameters = new CommandContributionItemParameter(serviceLocator,
				null, TOGGLE_LABEL_COMMAND_ID, parameters, id, null, null, label.getName(), null, null,
				CommandContributionItem.STYLE_PUSH,
				null, true);
		return new CommandContributionItem(contributionParameters);
	}

}
