/*******************************************************************************
 * Copyright (c) 2015-2017 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.views;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.mylyn.commons.ui.CommonImages;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskGroup;
import org.eclipse.mylyn.internal.tasks.core.UncategorizedTaskContainer;
import org.eclipse.mylyn.internal.tasks.core.UnsubmittedTaskContainer;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.IRepositoryElement;
import org.eclipse.mylyn.tasks.core.IRepositoryQuery;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskContainer;
import org.eclipse.mylyn.tasks.ui.AbstractRepositoryConnectorUi;
import org.eclipse.mylyn.tasks.ui.TasksUiImages;
import org.eclipse.swt.graphics.Image;

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.ui.Activator;

/**
 * Provides decorations fot the task information column.
 */
@SuppressWarnings("restriction")
class TitleColumnLabelProvider extends TimeColumnLabelProvider {

	public TitleColumnLabelProvider(WeekViewContentProvider contentProvider) {
		super(contentProvider);
	}

	private class CompositeImageDescriptor {
		ImageDescriptor icon;
		ImageDescriptor overlayKind;
	}

	@Override
	public Image getImage(Object element) {
		CompositeImageDescriptor compositeDescriptor = getImageDescriptor(element);
		if (element instanceof ITask) {
			if (compositeDescriptor.overlayKind == null) {
				compositeDescriptor.overlayKind = CommonImages.OVERLAY_CLEAR;
			}
			return CommonImages.getCompositeTaskImage(compositeDescriptor.icon, compositeDescriptor.overlayKind,
					false);
		} else if (element instanceof ITaskContainer) {
			return CommonImages.getCompositeTaskImage(compositeDescriptor.icon, CommonImages.OVERLAY_CLEAR, false);
		} else {
			return CommonImages.getCompositeTaskImage(compositeDescriptor.icon, null, false);
		}
	}

	private CompositeImageDescriptor getImageDescriptor(Object object) {
		CompositeImageDescriptor compositeDescriptor = new CompositeImageDescriptor();
		if (object instanceof UncategorizedTaskContainer) {
			compositeDescriptor.icon = TasksUiImages.CATEGORY_UNCATEGORIZED;
			return compositeDescriptor;
		} else if (object instanceof UnsubmittedTaskContainer) {
			compositeDescriptor.icon = TasksUiImages.CATEGORY_UNCATEGORIZED;
			return compositeDescriptor;
		} else if (object instanceof TaskCategory) {
			compositeDescriptor.icon = TasksUiImages.CATEGORY;
		} else if (object instanceof TaskGroup) {
			compositeDescriptor.icon = CommonImages.GROUPING;
		}
		if (object instanceof ITaskContainer) {
			IRepositoryElement element = (IRepositoryElement) object;

			AbstractRepositoryConnectorUi connectorUi = null;
			if (element instanceof ITask) {
				ITask repositoryTask = (ITask) element;
				connectorUi = TasksUiPlugin.getConnectorUi(((ITask) element).getConnectorKind());
				if (connectorUi != null) {
					compositeDescriptor.overlayKind = connectorUi.getTaskKindOverlay(repositoryTask);
				}
			} else if (element instanceof IRepositoryQuery) {
				connectorUi = TasksUiPlugin.getConnectorUi(((IRepositoryQuery) element).getConnectorKind());
			}

			if (connectorUi != null) {
				compositeDescriptor.icon = connectorUi.getImageDescriptor(element);
				return compositeDescriptor;
			} else {
				compositeDescriptor.icon = TasksUiImages.TASK;
				return compositeDescriptor;
			}
		}
		if (object instanceof Activity) {
			compositeDescriptor.icon = Activator.getImageDescriptor("icons/full/eview16/time_obj.gif");
		}
		return compositeDescriptor;
	}

	@Override
	public String getText(Object element) {
		if (element instanceof String) {
			return (String) element;
		}
		if (element instanceof ITask) {
			ITask task = ((ITask) element);
			StringBuilder sb = new StringBuilder();
			if (task.getTaskId() != null) {
				sb.append(task.getTaskId());
				sb.append(": ");
			}
			sb.append(task.getSummary());
			return sb.toString();
		}
		if (element instanceof Activity) {
			return ((Activity) element).getSummary();
		}
		if (element instanceof WeeklySummary) {
			return "Daily total";
		}
		return null;
	}

	@Override
	public String getToolTipText(Object element) {
		if (element instanceof Activity) {
			StringBuilder sb = new StringBuilder();
			sb.append("Started on ");
			sb.append(((Activity) element).getStart());
			if (((Activity) element).getEnd() != null) {
				sb.append(", ended on ");
				sb.append(((Activity) element).getEnd());
			}
			return sb.toString();
		}
		return super.getToolTipText(element);
	}

}