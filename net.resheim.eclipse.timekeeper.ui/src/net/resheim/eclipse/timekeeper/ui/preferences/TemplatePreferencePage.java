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

package net.resheim.eclipse.timekeeper.ui.preferences;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.LineNumberRulerColumn;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate.Type;

/**
 * This preference page allows for setting up various report templates.
 *
 * @author Torkild U. Resheim
 * @since 2.0
 */
public class TemplatePreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private List list;
	/** List of templates that can be modified */
	private Map<String, ReportTemplate> templates;
	private SourceViewer sourceViewer;
	/** The currently selected template */
	private ReportTemplate selectedTemplate;
	/** Name of the default template */
	private String defaultTemplate;
	private Button defaultTemplateButton;
	private Combo templateTypeButton;

	public TemplatePreferencePage() {
	}

	@SuppressWarnings("unchecked")
	@Override
	public Control createContents(Composite parent) {
		Composite container = new Composite(parent, SWT.NULL);
		container.setLayout(new GridLayout(4, false));

		list = new List(container, SWT.BORDER);
		list.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 3, 3));
		list.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				int[] selectedItems = list.getSelectionIndices();
				for (int i : selectedItems) {
					Optional<ReportTemplate> o = templates.values().stream()
							.filter(t -> t.getName().equals(list.getItem(i)))
							.findFirst();
					if (o.isPresent()) {
						select(o.get());
					}
				}
			}

		});

		Button addButton = new Button(container, SWT.NONE);
		addButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false, 1, 1));
		addButton.setText("Add");
		addButton.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				ReportTemplate template = new ReportTemplate("Template #" + (templates.size() + 1),
						ReportTemplate.Type.TEXT, "");
				templates.put(template.getName(),template);
				updateListAndSelect(template);
			}

		});

		Button removeButton = new Button(container, SWT.NONE);
		removeButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false, 1, 1));
		removeButton.setText("Remove");
		removeButton.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				if (selectedTemplate != null) {
					templates.remove(selectedTemplate.getName());
					updateListAndSelect(null);
				}
			}

		});

		Button duplicateButton = new Button(container, SWT.NONE);
		duplicateButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false, 1, 1));
		duplicateButton.setText("Duplicate");

		Label lblNewLabel = new Label(container, SWT.NONE);
		lblNewLabel.setText("Template code:");
		// spacers
		new Label(container, SWT.NONE);
		new Label(container, SWT.NONE);
		new Label(container, SWT.NONE);

		int styles = SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI | SWT.BORDER | SWT.FULL_SELECTION;
		CompositeRuler cr = new CompositeRuler();
		LineNumberRulerColumn lnrc = new LineNumberRulerColumn();
		cr.addDecorator(0, lnrc);
		sourceViewer = new SourceViewer(container, cr, styles);
		StyledText styledText = sourceViewer.getTextWidget();
		styledText.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, true, 3, 1));
		sourceViewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1));
		Document document = new Document();
		sourceViewer.setDocument(document);
		sourceViewer.configure(new SourceViewerConfiguration());
		Font font = JFaceResources.getFont(JFaceResources.TEXT_FONT);
		sourceViewer.getTextWidget().setFont(font);
		document.addDocumentListener(new IDocumentListener() {

			@Override
			public void documentChanged(DocumentEvent event) {
				if (selectedTemplate != null) {
					selectedTemplate.setCode(sourceViewer.getDocument().get());
				}
			}

			@Override
			public void documentAboutToBeChanged(DocumentEvent event) {
			}

		});

		// spacer
		new Label(container, SWT.NONE);

		Label label = new Label(container, SWT.NONE);
		label.setText("Content type:");
		templateTypeButton = new Combo(container, SWT.READ_ONLY);
		templateTypeButton.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 1, 1));
		templateTypeButton.setText("Type");
		templateTypeButton.setItems("HTML", "Plain Text", "Rich Text Format");
		templateTypeButton.select(0);
		templateTypeButton.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				switch (templateTypeButton.getSelectionIndex()) {
				case 0:
					selectedTemplate.setType(Type.HTML);
					break;
				case 1:
					selectedTemplate.setType(Type.TEXT);
					break;
				case 2:
					selectedTemplate.setType(Type.RTF);
					break;
				}
			}

		});

		defaultTemplateButton = new Button(container, SWT.CHECK);
		defaultTemplateButton.setText("Use as default template");
		defaultTemplateButton.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				defaultTemplate = selectedTemplate.getName();
			}

		});
		// spacer
		new Label(container, SWT.NONE);

		// clear out all the privately kept templates
		templates = new HashMap<>();
		// and load the contents from the current preferences
		IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, TimekeeperPlugin.BUNDLE_ID);
		defaultTemplate = store.getString(TimekeeperPlugin.PREF_DEFAULT_TEMPLATE);
		// not exactly the most future-proof method, but it will suffice
		byte[] decoded = Base64.getDecoder().decode(store.getString(TimekeeperPlugin.PREF_REPORT_TEMPLATES));
		ByteArrayInputStream bis = new ByteArrayInputStream(decoded);
		try {
			ObjectInputStream ois = new ObjectInputStream(bis);
			java.util.List<ReportTemplate> rt = (java.util.List<ReportTemplate>) ois.readObject();
			for (ReportTemplate t : rt) {
				templates.put(t.getName(), t);
			}
			java.util.List<String> names = templates.values().stream().map(t -> t.getName()).sorted()
					.collect(Collectors.toList());
			list.setItems(names.toArray(new String[names.size()]));
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		}

		return container;
	}

	/**
	 * Initialize the preference page.
	 */
	public void init(IWorkbench workbench) {
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void performDefaults() {
		// load _default_ templates to a list
		IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, TimekeeperPlugin.BUNDLE_ID);
		byte[] decoded = Base64.getDecoder().decode(store.getDefaultString(TimekeeperPlugin.PREF_REPORT_TEMPLATES));
		ByteArrayInputStream bis = new ByteArrayInputStream(decoded);
		try {
			ObjectInputStream ois = new ObjectInputStream(bis);
			java.util.List<ReportTemplate> defaults = (java.util.List<ReportTemplate>) ois.readObject();
			// remove all privately kept templates
			templates.clear();
			// and add the default ones to the list
			for (ReportTemplate t : defaults) {
				templates.put(t.getName(), t);
			}
			updateListAndSelect(selectedTemplate);
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		}
		defaultTemplate = store.getDefaultString(TimekeeperPlugin.PREF_DEFAULT_TEMPLATE);
	}

	@Override
	public boolean performOk() {
		IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, TimekeeperPlugin.BUNDLE_ID);
		// serialize the list of templates to a string and store it in the preferences
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
				ObjectOutputStream oos = new ObjectOutputStream(out)) {
			java.util.List<ReportTemplate> saveTemplates = new ArrayList<>();
			templates.values().forEach(t -> saveTemplates.add(t));
			oos.writeObject(saveTemplates);
			String encoded = Base64.getEncoder().encodeToString(out.toByteArray());
			store.setValue(TimekeeperPlugin.PREF_REPORT_TEMPLATES, encoded);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		// specify which template is the default
		store.setDefault(TimekeeperPlugin.PREF_DEFAULT_TEMPLATE, defaultTemplate);
		return true;
	}

	private void updateListAndSelect(ReportTemplate template) {
		// create a list of sorted template names
		java.util.List<String> collected = templates
				.values()
				.stream()
				.map(t -> t.getName())
				.sorted()
				.collect(Collectors.toList());
		String[] array = collected.toArray(new String[collected.size()]);
		list.setItems(array);
		select(template);
	}

	/**
	 * Handle that the specified template has been selected.
	 *
	 * @param template
	 *            the selected template
	 */
	private void select(ReportTemplate template) {
		boolean found = false;
		defaultTemplateButton.setSelection(false);
		// select the specified template
		if (template != null) {
			for (int i = 0; i < list.getItems().length; i++) {
				String name = list.getItem(i);
				if (name.equals(template.getName())) {
					found = true;
					list.select(i);
					selectedTemplate = template;
					sourceViewer.getDocument().set(templates.get(name).getCode());
					sourceViewer.moveFocusToWidgetToken();
					if (name.equals(defaultTemplate)) {
						defaultTemplateButton.setSelection(true);
					}
					switch (selectedTemplate.getType()) {
					case HTML:
						templateTypeButton.select(0);
						break;
					case TEXT:
						templateTypeButton.select(1);
						break;
					case RTF:
						templateTypeButton.select(3);
						break;
					}
				}
			}
		}
		// clear the source viewer
		if (template == null || !found) {
			selectedTemplate = null;
			sourceViewer.getDocument().set("");
		}
	}
}
