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
package net.resheim.eclipse.timekeeper.db;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.Bundle;

import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;

/**
 * Specifies default values for the core Timekeeper preferences.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, TimekeeperPlugin.BUNDLE_ID);
		try {
			store.setDefault(TimekeeperPlugin.PREF_DATABASE_LOCATION, TimekeeperPlugin.PREF_DATABASE_LOCATION_SHARED);
			store.setDefault(TimekeeperPlugin.PREF_DATABASE_LOCATION_URL,
					TimekeeperPlugin.getDefault().getSharedLocation());
			// read from the "templates" directory and create a list of templates – all
			// files stored there
			// will be registered.
			Bundle bundle = TimekeeperPlugin.getDefault().getBundle();
			Enumeration<URL> findEntries = bundle.findEntries("templates", "*", true);
			List<ReportTemplate> templates = new ArrayList<>();
			while (findEntries.hasMoreElements()) {
				URL url = findEntries.nextElement();
				// open stream directly instead of resolving the URI, which would barf on spaces
				// in path
				InputStream is = url.openStream();
				BufferedReader br = new BufferedReader(new InputStreamReader(is, Charset.forName("utf-8")));
				StringBuilder sb = new StringBuilder();
				String in = null;
				while ((in = br.readLine()) != null) {
					sb.append(in);
					sb.append("\n");
				}
				// determine a useful name for the template
				String name = url.getFile().substring(url.getFile().lastIndexOf('/') + 1,
						url.getFile().lastIndexOf('.'));
				// figure out what type of code we have
				ReportTemplate.Type type = ReportTemplate.Type.TEXT;
				if (url.getFile().endsWith(".html")) {
					type = ReportTemplate.Type.HTML;
				} else if (url.getFile().endsWith(".rtf")) {
					type = ReportTemplate.Type.RTF;
				}
				templates.add(new ReportTemplate(name, type, sb.toString()));
			}
			// serialize the list of templates to a string and store it in the preferences
			try (ByteArrayOutputStream out = new ByteArrayOutputStream();
					ObjectOutputStream oos = new ObjectOutputStream(out)) {
				oos.writeObject(templates);
				String encoded = Base64.getEncoder().encodeToString(out.toByteArray());
				store.setDefault(TimekeeperPlugin.PREF_REPORT_TEMPLATES, encoded);
			}
			// specify which template is the default
			store.setDefault(TimekeeperPlugin.PREF_DEFAULT_TEMPLATE, "Default HTML");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
