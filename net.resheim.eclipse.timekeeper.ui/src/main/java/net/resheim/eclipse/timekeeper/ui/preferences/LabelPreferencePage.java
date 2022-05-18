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

import java.util.ArrayList;
import java.util.List;

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
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
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

public class LabelPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private List<Image> colorPreviewImages;
	private ColorSelector fAppearanceColorEditor;
	private TableViewer fAppearanceColorTableViewer;

	public LabelPreferencePage() {
	}

	private Control createAppearancePage(Composite parent) {
		Composite appearanceComposite = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.numColumns = 2;
		layout.marginHeight = 0;
		layout.marginWidth = 0;

		appearanceComposite.setLayout(layout);

		// Set up Appearance Color Options Table
		Composite tableComposite = new Composite(appearanceComposite, SWT.NONE);
		GridData tableGD = new GridData(GridData.FILL_VERTICAL);
		tableComposite.setLayoutData(tableGD);
		fAppearanceColorTableViewer = new TableViewer(tableComposite, SWT.SINGLE | SWT.V_SCROLL | SWT.BORDER);
		initializeAppearColorTable(tableComposite);

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
				ActivityLabel selectedColor = getSelectedAppearanceColorOption();
				text.setText(selectedColor.getName());
				selectedColor.setColor(StringConverter.asString(fAppearanceColorEditor.getColorValue()));
				// Make the newly selected color display in the table
				fAppearanceColorTableViewer.update(selectedColor, null);
				text.setText(selectedColor.getName());
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
		ActivityLabel selectedColor = getSelectedAppearanceColorOption();
		RGB rgb = StringConverter.asRGB(selectedColor.getColor());
		fAppearanceColorEditor.setColorValue(rgb);
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

	private void initializeAppearColorTable(Composite tableComposite) {
		fAppearanceColorTableViewer
		.addSelectionChangedListener((SelectionChangedEvent event)
				-> handleAppearanceColorListSelection());
		colorPreviewImages = new ArrayList<>();

		fAppearanceColorTableViewer.setLabelProvider(new LabelProvider() {
			@Override
			public Image getImage(Object element) {
				ActivityLabel colorEntry = ((ActivityLabel) element);
				System.out.println(colorEntry.getName() + "=" + colorEntry.getColor());
				RGB rgb = StringConverter.asRGB(colorEntry.getColor());
				Color color = new Color(tableComposite.getParent().getDisplay(), rgb.red, rgb.green, rgb.blue);
				int dimensions = 13;
				Image image = new Image(tableComposite.getParent().getDisplay(), 15, 15);
				GC gc = new GC(image);
				gc.setAdvanced(true);
				// make a transparent background
				Color transparent = new Color(tableComposite.getParent().getDisplay(), 255, 255, 255, 0);
				gc.setBackground(transparent);
				gc.fillRectangle(0, 0, dimensions, dimensions);
				// draw color preview
				gc.setBackground(color);
				gc.fillOval(0, 0, dimensions, dimensions);
				// draw outline around color preview
				gc.setForeground(new Color(tableComposite.getParent().getDisplay(), darken(rgb, 0.6f)));
				gc.setLineWidth(1);
				gc.drawOval(0, 0, dimensions, dimensions);
				gc.dispose();
				color.dispose();
				colorPreviewImages.add(image);
				return image;
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

	private RGB darken(RGB color, float factor) {
		float[] hsb = color.getHSB();
		return new RGB(hsb[0], hsb[1], Math.min(hsb[2] * factor, 1));
	}

}
