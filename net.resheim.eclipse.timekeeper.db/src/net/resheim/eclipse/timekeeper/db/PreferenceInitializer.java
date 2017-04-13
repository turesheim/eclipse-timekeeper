package net.resheim.eclipse.timekeeper.db;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Class used to initialize default preference values.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE,
				TimekeeperPlugin.BUNDLE_ID);
		try {
			Location instanceLocation = Platform.getInstanceLocation();			
			URL dataArea = instanceLocation.getURL();
			Path path = Paths.get(dataArea.toURI()).resolve(".timekeeper");
			if (!path.toFile().exists()) {
				Files.createDirectory(path);
			}
			store.setDefault(TimekeeperPlugin.DATABASE_URL,"jdbc:h2:"+path+"/h2db");
		} catch (Exception e){
			e.printStackTrace();
		}
	}

}
