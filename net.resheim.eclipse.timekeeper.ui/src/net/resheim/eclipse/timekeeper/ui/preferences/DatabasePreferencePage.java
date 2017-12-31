/*******************************************************************************
 * Copyright (c) 2017 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.preferences;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.ui.views.WorkWeekView;

public class DatabasePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public DatabasePreferencePage() {
		super(FieldEditorPreferencePage.GRID);
	}

	@Override
	protected void createFieldEditors() {

		addField(new RadioGroupFieldEditor(TimekeeperPlugin.DATABASE_LOCATION, "Database location", 1,
				new String[][] {
			{ "Shared (in ~/.timekeeper/)", TimekeeperPlugin.DATABASE_LOCATION_SHARED },
			{ "Relative to workspace (in .timekeeper/)", TimekeeperPlugin.DATABASE_LOCATION_WORKSPACE },
			{ "Specified by JDBC URL", TimekeeperPlugin.DATABASE_LOCATION_URL },
				}, getFieldEditorParent(), true));

		addField(new StringFieldEditor(TimekeeperPlugin.DATABASE_URL, Messages.DatabasePreferences_URL,
				getFieldEditorParent()));

		Group g2 = new Group(getFieldEditorParent(), SWT.SHADOW_ETCHED_IN);
		g2.setText(Messages.DatabasePreferences_ExportImportTitle);
		g2.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
		g2.setLayout(new GridLayout(2, true));
		addExportButton(g2);
		addImportButton(g2);
		adjustGridLayout();
	}

	private void addExportButton(Composite g) {
		Button button = new Button(g, SWT.PUSH);
		button.setText(Messages.DatabasePreferences_Export);
		button.setLayoutData(new GridData());
		button.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				DirectoryDialog dialog = new DirectoryDialog(getFieldEditorParent().getShell(), SWT.SAVE);
				dialog.setText(Messages.DatabasePreferences_ChooseExportFolder);
				String open = dialog.open();
				if (open!=null){
					Path location = Paths.get(open);
					try {
						int count = TimekeeperPlugin.getDefault().exportTo(location);
						MessageDialog.openInformation(g.getShell(), Messages.DatabasePreferences_DataExported, String
								.format(Messages.DatabasePreferences_ExportMessage, count));
					} catch (IOException e1) {
						MessageDialog.openError(g.getShell(), Messages.DatabasePreferences_ExportError, e1.getMessage());
					}
				}
			}
		});
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (event.getProperty().equals(TimekeeperPlugin.DATABASE_LOCATION)) {
			MessageDialog.openInformation(Display.getCurrent().getActiveShell(), Messages.DatabasePreferences_RestartRequired,
					Messages.DatabasePreferences_ChangeMessage);
		}
		super.propertyChange(event);
	}

	@Override
	public void dispose() {
		getPreferenceStore().removePropertyChangeListener(this);
		super.dispose();
	}

	private void addImportButton(Composite g) {
		Button button = new Button(g, SWT.PUSH);
		button.setText(Messages.DatabasePreferences_Import);
		button.setLayoutData(new GridData());
		button.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				DirectoryDialog dialog = new DirectoryDialog(getFieldEditorParent().getShell(), SWT.OPEN);
				dialog.setText(Messages.DatabasePreferences_ChooseImportFolder);
				String open = dialog.open();
				if (open != null) {
					Path location = Paths.get(open);
					try {
						int i = TimekeeperPlugin.getDefault().importFrom(location);
						IViewPart showView = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
								.findView(WorkWeekView.VIEW_ID);
						((WorkWeekView) showView).refresh();
						MessageDialog.openInformation(g.getShell(), Messages.DatabasePreferences_DataImported,
								String.format(Messages.DatabasePreferences_CreatedMessage, i));
					} catch (IOException e1) {
						MessageDialog.openError(g.getShell(), Messages.DatabasePreferences_ImportError, e1.getMessage());
					}
				}
			}
		});
	}

	@Override
	public void init(IWorkbench workbench) {
		IPreferenceStore preferenceStore = new ScopedPreferenceStore(InstanceScope.INSTANCE,
				TimekeeperPlugin.BUNDLE_ID);
		setPreferenceStore(preferenceStore);
		preferenceStore.addPropertyChangeListener(this);
	}

}
