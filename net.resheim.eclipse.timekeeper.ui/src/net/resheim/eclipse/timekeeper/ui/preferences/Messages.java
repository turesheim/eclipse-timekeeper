/*******************************************************************************
 * Copyright (c) 2015 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.preferences;

import org.eclipse.osgi.util.NLS;

public class Messages extends NLS {
	private static final String BUNDLE_NAME = "net.resheim.eclipse.timekeeper.ui.preferences.messages"; //$NON-NLS-1$
	public static String DatabasePreferences_PageTitle;
	public static String DatabasePreferences_URL;
	public static String DatabasePreferences_ExportMessage;
	public static String DatabasePreferences_ExportError;
	public static String DatabasePreferences_RestartRequired;
	public static String DatabasePreferences_ChangeMessage;
	public static String DatabasePreferences_Import;
	public static String DatabasePreferences_ChooseImportFolder;
	public static String DatabasePreferences_DataImported;
	public static String DatabasePreferences_CreatedMessage;
	public static String DatabasePreferences_ImportError;
	public static String DatabasePreferences_MixedMode;
	public static String DatabasePreferences_AutoDescription;
	public static String DatabasePreferences_ExportImportTitle;
	public static String DatabasePreferences_Export;
	public static String DatabasePreferences_ChooseExportFolder;
	public static String DatabasePreferences_DataExported;
	static {
		// initialize resource bundle
		NLS.initializeMessages(BUNDLE_NAME, Messages.class);
	}

	private Messages() {
	}
}
