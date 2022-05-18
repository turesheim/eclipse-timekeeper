package net.resheim.eclipse.timekeeper.ui.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.graphics.RGB;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
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
		while (!TimekeeperPlugin.getDefault().isReady()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		// Add default labels if the database is empty
		if (TimekeeperPlugin.getLabels().count() == 0) {
			TimekeeperPlugin.setLabel(new ActivityLabel("Red", StringConverter.asString(new RGB(244, 103, 88))));
			TimekeeperPlugin.setLabel(new ActivityLabel("Orange", StringConverter.asString(new RGB(245, 166, 81))));
			TimekeeperPlugin.setLabel(new ActivityLabel("Yellow", StringConverter.asString(new RGB(246, 208, 90))));
			TimekeeperPlugin.setLabel(new ActivityLabel("Green", StringConverter.asString(new RGB(87, 206, 105))));
			TimekeeperPlugin.setLabel(new ActivityLabel("Blue", StringConverter.asString(new RGB(66, 136, 243))));
			TimekeeperPlugin.setLabel(new ActivityLabel("Purple", StringConverter.asString(new RGB(177, 111, 209))));
			TimekeeperPlugin.setLabel(new ActivityLabel("Gray", StringConverter.asString(new RGB(156, 156, 160))));
		}

	}

}
