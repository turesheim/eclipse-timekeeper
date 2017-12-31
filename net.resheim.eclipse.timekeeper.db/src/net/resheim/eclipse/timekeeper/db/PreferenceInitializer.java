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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.Bundle;

import net.resheim.eclipse.timekeeper.db.report.ReportTemplate;

/**
 * Specifies default values for the core preferences. 
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, TimekeeperPlugin.BUNDLE_ID);
		try {
			store.setDefault(TimekeeperPlugin.DATABASE_LOCATION, TimekeeperPlugin.DATABASE_LOCATION_SHARED);
			store.setDefault(TimekeeperPlugin.DATABASE_LOCATION_URL, TimekeeperPlugin.getDefault().getSharedLocation());
			// read from the "templates" directory and create a list of templates
			Bundle bundle = TimekeeperPlugin.getDefault().getBundle();
			Enumeration<URL> findEntries = bundle.findEntries("templates", "*", true);
			List<ReportTemplate> templates = new ArrayList<>();
			while (findEntries.hasMoreElements()) {
				URL url = findEntries.nextElement();
				// open stream directly instead of resolving the URI, which would barf on spaces in path
				InputStream is = url.openStream();
				BufferedReader br = new BufferedReader(new InputStreamReader(is, Charset.forName("utf-8")));
				StringBuilder sb = new StringBuilder();
				String in = null;
				while ((in = br.readLine()) != null) {
					sb.append(in);
					sb.append("\n");
				}
				templates.add(new ReportTemplate(url.getFile(), sb.toString()));
			}
			// serialize the list of templates to a string and store it in the preferences
			try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ObjectOutputStream oos = new ObjectOutputStream(out)){
				oos.writeObject(templates);
				String encoded = Base64.getEncoder().encodeToString(out.toByteArray());
				store.setDefault(TimekeeperPlugin.REPORT_TEMPLATES, encoded);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
