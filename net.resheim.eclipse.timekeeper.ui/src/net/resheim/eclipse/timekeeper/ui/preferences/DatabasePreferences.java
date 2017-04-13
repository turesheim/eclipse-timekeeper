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

package net.resheim.eclipse.timekeeper.ui.preferences;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
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

public class DatabasePreferences extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public DatabasePreferences() {
		super(FieldEditorPreferencePage.GRID);
	}

	@Override
	protected void createFieldEditors() {
		addField(new StringFieldEditor(TimekeeperPlugin.DATABASE_URL, "Database URL:", getFieldEditorParent()));
		Group g = new Group(getFieldEditorParent(), SWT.SHADOW_ETCHED_IN);
		g.setText("Export/Import to comma separated files:");
		g.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
		g.setLayout(new GridLayout(2, true));
		addExportButton(g);
		addImportButton(g);
		adjustGridLayout();
	}

	private void addExportButton(Composite g) {
		Button button = new Button(g, SWT.PUSH);
		button.setText("Export...");
		button.setLayoutData(new GridData());
		button.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				DirectoryDialog dialog = new DirectoryDialog(getFieldEditorParent().getShell(), SWT.SAVE);
				dialog.setText("Please choose a folder to store the exported files");
				String open = dialog.open();
				if (open!=null){
					Path location = Paths.get(open);
					try {
						int count = TimekeeperPlugin.getDefault().exportTo(location);
						MessageDialog.openInformation(g.getShell(), "Data exported", String
								.format("Files have been created with %1$s records from the current database.", count));
					} catch (IOException e1) {
						MessageDialog.openError(g.getShell(), "Could not export data", e1.getMessage());
					}
				}
			}
		});
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		super.propertyChange(event);
		if (event.getProperty().equals(TimekeeperPlugin.DATABASE_URL)) {
			MessageDialog.openInformation(Display.getCurrent().getActiveShell(), "Restart Required",
					"Please note that this application must be restarted in order for the database URL changes to work. You may want to export existing data first, so that they can be imported into the new database.");
		}
	}

	@Override
	public void dispose() {
		getPreferenceStore().removePropertyChangeListener(this);
		super.dispose();
	}

	private void addImportButton(Composite g) {
		Button button = new Button(g, SWT.PUSH);
		button.setText("Import...");
		button.setLayoutData(new GridData());
		button.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				DirectoryDialog dialog = new DirectoryDialog(getFieldEditorParent().getShell(), SWT.OPEN);
				dialog.setText("Please choose a folder to import from");
				String open = dialog.open();
				if (open != null) {
					Path location = Paths.get(open);
					try {
						int i = TimekeeperPlugin.getDefault().importFrom(location);
						IViewPart showView = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
								.findView(WorkWeekView.VIEW_ID);
						((WorkWeekView) showView).refresh();
						MessageDialog.openInformation(g.getShell(), "Data imported",
								String.format("A total of %1$s records was merged or created from selected files.", i));
					} catch (IOException e1) {
						MessageDialog.openError(g.getShell(), "Could not import data", e1.getMessage());
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
