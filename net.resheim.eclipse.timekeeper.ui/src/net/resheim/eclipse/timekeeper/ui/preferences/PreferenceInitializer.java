package net.resheim.eclipse.timekeeper.ui.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import net.resheim.eclipse.timekeeper.ui.Activator;

/**
 * Class used to initialize default preference values.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		store.setDefault(PreferenceConstants.MINUTES_IDLE, 5);
		store.setDefault(PreferenceConstants.MINUTES_AWAY, 30);
	}

}
