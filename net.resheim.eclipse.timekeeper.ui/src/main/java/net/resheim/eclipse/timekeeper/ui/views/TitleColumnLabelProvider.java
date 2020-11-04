/*******************************************************************************
 * Copyright (c) 2015-2020 Torkild U. Resheim
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

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.TrackedTask;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

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
		if (element instanceof Activity) {
			return TimekeeperUiPlugin.getDefault().getImageRegistry().get(TimekeeperUiPlugin.OBJ_ACTIVITY);
		}
		// Mylyn stuff, should be rewritten to use Mylyn HiDPI images when these
		// are ready
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
		if (object instanceof UncategorizedTaskContainer || object instanceof UnsubmittedTaskContainer) {
			compositeDescriptor.icon = TasksUiImages.CATEGORY_UNCATEGORIZED;
			return compositeDescriptor;
		} else if (object instanceof TaskCategory) {
			compositeDescriptor.icon = TasksUiImages.CATEGORY;
		} else if (object instanceof TaskGroup) {
			compositeDescriptor.icon = CommonImages.GROUPING;
		}

		if (object instanceof TrackedTask) {
			ITask task = ((TrackedTask) object).getMylynTask();
			if (task == null) {
				compositeDescriptor.icon = TasksUiImages.TASK;
				return compositeDescriptor;
			} else {
				object = task;
			}
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
		return compositeDescriptor;
	}

	@Override
	public String getText(Object element) {
		if (element instanceof Project) {
			return ((Project) element).getName();
		}
		if (element instanceof TrackedTask) {
			ITask itask = ((TrackedTask) element).getMylynTask();
			TrackedTask task = (TrackedTask) element;
			StringBuilder sb = new StringBuilder();
			if (itask != null && itask.getTaskId() != null) {
				sb.append(itask.getTaskId());
				sb.append(": ");
			}
			sb.append(task.getTaskSummary());
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