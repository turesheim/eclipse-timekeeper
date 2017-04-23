package net.resheim.eclipse.timekeeper.db;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
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
			store.setDefault(TimekeeperPlugin.DATABASE_LOCATION, TimekeeperPlugin.DATABASE_LOCATION_SHARED);
			store.setDefault(TimekeeperPlugin.DATABASE_LOCATION_URL, TimekeeperPlugin.getDefault().getSharedLocation());
		} catch (Exception e){
			e.printStackTrace();
		}
	}

}
