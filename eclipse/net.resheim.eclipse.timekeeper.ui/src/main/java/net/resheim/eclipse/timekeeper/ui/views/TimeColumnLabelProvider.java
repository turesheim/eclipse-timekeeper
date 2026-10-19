/*******************************************************************************
 * Copyright (c) 2015-2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.views;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;

import net.resheim.eclipse.timekeeper.domain.Activity;

/**
 * Provides label decorations for the columns containing time spent on each
 * task.
 */
abstract class TimeColumnLabelProvider extends ColumnLabelProvider {

	private final WeekViewContentProvider contentProvider;

	private final IThemeManager themeManager = PlatformUI.getWorkbench().getThemeManager();

	public TimeColumnLabelProvider(WeekViewContentProvider contentProvider) {
		this.contentProvider = contentProvider;
	}

	protected ZoneId getZoneId() {
		return contentProvider.getZoneId();
	}

	@Override
	public Color getBackground(Object element) {
		if (element instanceof WeeklySummary) {
			return themeManager.getCurrentTheme().getColorRegistry().get(JFacePreferences.INFORMATION_BACKGROUND_COLOR);
		}
		return super.getBackground(element);
	}

	@Override
	public Font getFont(Object element) {
		return contentProvider.active(element)
				? JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT)
				: JFaceResources.getDialogFont();
	}

	@Override
	public String getToolTipText(Object element) {
		if (element instanceof Activity) {
			StringBuilder sb = new StringBuilder();
			sb.append("Started at ");
			LocalDateTime start = LocalDateTime.ofInstant(((Activity) element).start(), contentProvider.getZoneId());
			sb.append(start.format(DateTimeFormatter.ofPattern("HH:mm")));
			sb.append(" on the ");
			sb.append(start.format(DateTimeFormatter.ofPattern("d")));
			sb.append("th");
			LocalDateTime end = ((Activity) element).end().isEmpty() ? null
					: LocalDateTime.ofInstant(((Activity) element).end().orElseThrow(), contentProvider.getZoneId());
			if (end != null) {
				sb.append(", ended at ");
				sb.append(end.format(DateTimeFormatter.ofPattern("HH:mm")));
				// will not work if the activity lastet for a year
				if (start.getDayOfYear() != end.getDayOfYear()) {
					sb.append(" on the ");
					sb.append(end.format(DateTimeFormatter.ofPattern("d")));
					sb.append("th");
				}
			}
			return sb.toString();
		}
		return super.getToolTipText(element);
	}

}
