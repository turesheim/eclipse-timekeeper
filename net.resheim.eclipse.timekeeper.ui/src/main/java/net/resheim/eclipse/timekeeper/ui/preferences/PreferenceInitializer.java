package net.resheim.eclipse.timekeeper.ui.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/**
 * Class used to initialize default preference values for the UI.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = TimekeeperUiPlugin.getDefault().getPreferenceStore();
		store.setDefault(PreferenceConstants.MINUTES_IDLE, 5);
		store.setDefault(PreferenceConstants.MINUTES_AWAY, 30);
		store.setDefault(PreferenceConstants.DEACTIVATE_WHEN_AWAY, true);
	}

}
