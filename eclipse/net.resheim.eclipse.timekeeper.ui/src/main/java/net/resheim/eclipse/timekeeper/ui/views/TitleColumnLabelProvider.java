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

import org.eclipse.swt.graphics.Image;

import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/**
 * Provides decorations for the task and activity information column.
 */
class TitleColumnLabelProvider extends TimeColumnLabelProvider {
	private final WeekViewContentProvider contentProvider;

	public TitleColumnLabelProvider(WeekViewContentProvider contentProvider) {
		super(contentProvider);
		this.contentProvider = contentProvider;
	}


	@Override
	public Image getImage(Object element) {
		if (element instanceof Activity) {
			return TimekeeperUiPlugin.getDefault().getImageRegistry().get(TimekeeperUiPlugin.OBJ_ACTIVITY);
		}
		if (element instanceof Project) {
			return org.eclipse.ui.PlatformUI.getWorkbench().getSharedImages().getImage(org.eclipse.ui.ISharedImages.IMG_OBJ_FOLDER);
		}
		if (element instanceof Task) {
			return TimekeeperUiPlugin.getDefault().getTaskIntegration().map(value -> value.image((Task) element))
					.orElseGet(() -> org.eclipse.ui.PlatformUI.getWorkbench().getSharedImages().getImage(org.eclipse.ui.ISharedImages.IMG_OBJ_FILE));
		}
		return null;
	}

	@Override
	public void dispose() {
		super.dispose();
	}

	@Override
	public String getText(Object element) {
		if (element instanceof Project) {
			return ((Project) element).name();
		}
		if (element instanceof Task) {
			Task task = (Task) element;
			String externalId = task.externalReferences().isEmpty() ? null
					: TimekeeperUiPlugin.getDefault().getTaskIntegration().flatMap(value -> value.displayId(task))
							.or(() -> task.externalReferences().stream()
									.map(reference -> reference.externalId()).sorted().findFirst()).orElse(null);
			return externalId == null ? task.summary() : externalId + ": " + task.summary();
		}
		if (element instanceof Activity) {
			return ((Activity) element).summary();
		}
		if (element instanceof WeeklySummary) {
			return "Daily total";
		}
		return null;
	}

	@Override
	public String getToolTipText(Object element) {
		if (element instanceof Task task) {
			if (task.externalReferences().isEmpty()) return "Native Timekeeper task";
			String references = task.externalReferences().stream().map(reference -> reference.providerId()
					+ ": " + reference.externalId()).sorted().collect(java.util.stream.Collectors.joining("\n"));
			return TimekeeperUiPlugin.getDefault().getTaskIntegration().filter(value -> value.isLinked(task)).isEmpty() ? references + "\nNo linked external task is available." : references;
		}
		if (element instanceof Activity) {
			Activity activity = (Activity) element;
			StringBuilder sb = new StringBuilder();
			sb.append("Started on ");
			sb.append(activity.start().atZone(getZoneId()).toLocalDateTime());
			if (activity.end().isPresent()) {
				sb.append(", ended on ");
				sb.append(activity.end().orElseThrow().atZone(getZoneId()).toLocalDateTime());
			}
			var labels = contentProvider.labels(activity);
			if (!labels.isEmpty()) {
				sb.append("\nLabels: ");
				sb.append(labels.stream().map(net.resheim.eclipse.timekeeper.domain.Label::name)
						.collect(java.util.stream.Collectors.joining(", ")));
			}
			return sb.toString();
		}
		return super.getToolTipText(element);
	}

}
