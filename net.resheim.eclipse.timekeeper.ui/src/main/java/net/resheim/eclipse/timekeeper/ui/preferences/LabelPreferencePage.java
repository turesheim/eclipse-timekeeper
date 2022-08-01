/*******************************************************************************
 * Copyright © 2022 Torkild U. Resheim
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.preferences;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.PixelConverter;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.preference.ColorSelector;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
import net.resheim.eclipse.timekeeper.ui.ActivityLabelPainter;

public class LabelPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private ColorSelector fAppearanceColorEditor;
	private TableViewer fAppearanceColorTableViewer;
	private ActivityLabelPainter painter;

	public LabelPreferencePage() {
	}

	private Control createAppearancePage(Composite parent) {
		Composite appearanceComposite = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.numColumns = 2;
		layout.marginHeight = 0;
		layout.marginWidth = 0;

		appearanceComposite.setLayout(layout);

		Composite tableComposite = new Composite(appearanceComposite, SWT.NONE);
		GridData tableGD = new GridData(GridData.FILL_VERTICAL);
		tableComposite.setLayoutData(tableGD);
		fAppearanceColorTableViewer = new TableViewer(tableComposite, SWT.SINGLE | SWT.V_SCROLL | SWT.BORDER);
		initializeLabelList(tableComposite);

		Composite stylesComposite = new Composite(appearanceComposite, SWT.NONE);
		layout = new GridLayout();
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		layout.numColumns = 2;
		stylesComposite.setLayout(layout);
		stylesComposite.setLayoutData(new GridData(GridData.FILL_BOTH));

		Label l = new Label(stylesComposite, SWT.LEFT);
		l.setText("&Color:");
		GridData gd = new GridData();
		gd.horizontalAlignment = GridData.BEGINNING;
		l.setLayoutData(gd);

		fAppearanceColorEditor = new ColorSelector(stylesComposite);
		Button foregroundColorButton = fAppearanceColorEditor.getButton();
		gd = new GridData(GridData.FILL_HORIZONTAL);
		gd.horizontalAlignment = GridData.BEGINNING;
		foregroundColorButton.setLayoutData(gd);

		l = new Label(stylesComposite, SWT.LEFT);
		l.setText("&Label:");
		gd = new GridData();
		gd.horizontalAlignment = GridData.BEGINNING;
		l.setLayoutData(gd);

		Text text = new Text(stylesComposite, SWT.LEFT | SWT.BORDER);
		gd = new GridData();
		gd.horizontalAlignment = GridData.BEGINNING | GridData.FILL_HORIZONTAL;
		text.setLayoutData(gd);

		text.addModifyListener(new ModifyListener() {

			@Override
			public void modifyText(ModifyEvent e) {
				// ignore
			}

		});

		foregroundColorButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				ActivityLabel selectedLabel = getSelectedAppearanceColorOption();
				text.setText(selectedLabel.getName());
				selectedLabel.setColor(StringConverter.asString(fAppearanceColorEditor.getColorValue()));
				// Make the newly selected color display in the table
				fAppearanceColorTableViewer.update(selectedLabel, null);
				text.setText(selectedLabel.getName());
			}
		});

		return appearanceComposite;
	}

	@Override
	protected Control createContents(Composite parent) {
		Control control = createAppearancePage(parent);
		initialize();
		Dialog.applyDialogFont(control);
		return control;
	}


	private ActivityLabel getSelectedAppearanceColorOption() {
		return (ActivityLabel) fAppearanceColorTableViewer.getStructuredSelection().getFirstElement();
	}

	private void handleAppearanceColorListSelection() {
		ActivityLabel selectedLabel = getSelectedAppearanceColorOption();
		RGB rgb = StringConverter.asRGB(selectedLabel.getColor());
		fAppearanceColorEditor.setColorValue(rgb);
		fAppearanceColorTableViewer.update(selectedLabel, null);
	}

	@Override
	public void init(IWorkbench workbench) {
		// ignore
	}

	private void initialize() {
		fAppearanceColorTableViewer.setContentProvider(ArrayContentProvider.getInstance());
		fAppearanceColorTableViewer.setInput(TimekeeperPlugin.getLabels().toArray());
		fAppearanceColorTableViewer.setSelection(new StructuredSelection(fAppearanceColorTableViewer.getElementAt(0)),
				true);
	}

	private void initializeLabelList(Composite tableComposite) {
		fAppearanceColorTableViewer
		.addSelectionChangedListener((SelectionChangedEvent event)
				-> handleAppearanceColorListSelection());
		painter = new ActivityLabelPainter();

		fAppearanceColorTableViewer.setLabelProvider(new LabelProvider() {
			@Override
			public Image getImage(Object element) {
				return painter.getLabelImage((ActivityLabel) element,
						fAppearanceColorTableViewer.getTable().getItemHeight(), false);
			}

			@Override
			public String getText(Object element) {
				return ((ActivityLabel) element).getName();
			}
		});

		TableColumn tc = new TableColumn(fAppearanceColorTableViewer.getTable(), SWT.NONE, 0);
		TableColumnLayout tableColumnLayout = new TableColumnLayout(true);
		PixelConverter pixelConverter = new PixelConverter(tableComposite.getParent().getFont());
		tableColumnLayout.setColumnData(tc, new ColumnWeightData(1, pixelConverter.convertWidthInCharsToPixels(30)));
		tableComposite.setLayout(tableColumnLayout);
		GridData gd = new GridData(GridData.VERTICAL_ALIGN_BEGINNING | GridData.FILL_BOTH);
		Table fAppearanceColorTable = fAppearanceColorTableViewer.getTable();
		gd.heightHint = fAppearanceColorTable.getItemHeight() * 8;
		fAppearanceColorTable.setLayoutData(gd);
	}

}
