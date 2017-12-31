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
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;

/**
 * This preference page allows for setting up various report templates.
 *
 * @author Torkild U. Resheim
 * @since 2.0
 */
public class TemplatePreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private List list;
	private Map<String, ReportTemplate> templates;
	private SourceViewer sourceViewer;

	private ReportTemplate selectedTemplate;

	public TemplatePreferencePage() {
	}

	@SuppressWarnings("unchecked")
	@Override
	public Control createContents(Composite parent) {
		Composite container = new Composite(parent, SWT.NULL);
		container.setLayout(new GridLayout(2, false));

		list = new List(container, SWT.BORDER);
		list.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 1, 2));
		list.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent e) {
				int[] selectedItems = list.getSelectionIndices();
				for (int i : selectedItems) {
					Optional<ReportTemplate> o = templates.values().stream()
							.filter(t -> t.getName().equals(list.getItem(i)))
							.findFirst();
					if (o.isPresent()) {
						selectedTemplate = o.get();
						sourceViewer.getDocument().set(selectedTemplate.getCode());
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
				ReportTemplate template = new ReportTemplate("Template #" + (templates.size() + 1), "");
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

		Label lblNewLabel = new Label(container, SWT.NONE);
		lblNewLabel.setText("Template code:");
		new Label(container, SWT.NONE);

		int styles = SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI | SWT.BORDER | SWT.FULL_SELECTION;

		sourceViewer = new SourceViewer(container, null, styles);
		StyledText styledText = sourceViewer.getTextWidget();
		styledText.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, true, 1, 1));
		sourceViewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1));
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

		new Label(container, SWT.NONE);

		// clear out all the templates
		templates = new HashMap<>();
		// and load the contents from the current preferences
		IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, TimekeeperPlugin.BUNDLE_ID);
		byte[] decoded = Base64.getDecoder().decode(store.getString(TimekeeperPlugin.REPORT_TEMPLATES));
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
		byte[] decoded = Base64.getDecoder().decode(store.getDefaultString(TimekeeperPlugin.REPORT_TEMPLATES));
		ByteArrayInputStream bis = new ByteArrayInputStream(decoded);
		try {
			ObjectInputStream ois = new ObjectInputStream(bis);
			java.util.List<ReportTemplate> defaults = (java.util.List<ReportTemplate>) ois.readObject();
			// remove all templates
			templates.clear();
			// and add the default ones
			for (ReportTemplate t : defaults) {
				templates.put(t.getName(), t);
			}
			updateListAndSelect(selectedTemplate);
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		}
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
			store.setValue(TimekeeperPlugin.REPORT_TEMPLATES, encoded);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
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
		boolean found = false;
		// select the specified template
		if (template != null) {
			for (int i = 0; i < array.length; i++) {
				String name = array[i];
				if (name.equals(template.getName())) {
					found = true;
					list.select(i);
					selectedTemplate = template;
					sourceViewer.getDocument().set(templates.get(name).getCode());
					sourceViewer.moveFocusToWidgetToken();
					break;
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
