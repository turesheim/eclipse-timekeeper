/*******************************************************************************
 * Copyright (c) 2015 Torkild U. Resheim.
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.preferences;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

public class TimekeeperPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public TimekeeperPreferencePage() {
		super(GRID);
		setPreferenceStore(TimekeeperUiPlugin.getDefault().getPreferenceStore());
	}

	@Override
	public void createFieldEditors() {

		addField(new IntegerFieldEditor(PreferenceConstants.MINUTES_IDLE,
				"Number of minutes before user is considered &idle", getFieldEditorParent()));
		addField(new IntegerFieldEditor(PreferenceConstants.MINUTES_AWAY,
				"Number of minutes before user is considered &away", getFieldEditorParent()));

	}

	public void init(IWorkbench workbench) {
	}

}