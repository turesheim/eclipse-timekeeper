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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Assert;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.mylyn.tasks.core.ITask;

import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/**
 * Allows editing of the period an activity lasts if it starts or ends on the
 * column this has been installed for. The value for an activity can be
 * specified thus:
 * <ul>
 * <li>HH:MM-HH:MM specifies start and end time. The end time will be ignored if
 * the task is presently active</li>
 * <li>HH:MM specifies the start time</li>
 * </ul>
 * FIXME: The date of the activity cannot be changed
 *
 */
class TimeEditingSupport extends EditingSupport {

	private static final String TIME_RANGE = "([0-9]|0[0-9]|1[0-9]|2[0-3]):([0-5][0-9])\\-([0-9]|0[0-9]|1[0-9]|2[0-3]):([0-5][0-9])";
	private static final String TIME_POINT = "([0-9]|0[0-9]|1[0-9]|2[0-3]):([0-5][0-9])";

	private final int weekday;
	private int width_i;
	private int width_0;

	public TimeEditingSupport(TreeViewer viewer, WeekViewContentProvider contentProvider, int weekday) {
		super(viewer);
		this.weekday = weekday;
	}

	@Override
	protected boolean canEdit(Object element) {
		if (element instanceof Activity) {
			Activity activity = (Activity) element;
			if (startsOnThisDay(activity) || endsOnThisDay(activity)) {
				return true;
			}
		}
		return false;
	}

	private boolean startsOnThisDay(Activity activity) {
		return activity.getStart().getDayOfWeek().getValue() == (weekday + 1);
	}

	private boolean endsOnThisDay(Activity activity) {
		if (activity.getEnd() != null) {
			return activity.getEnd().getDayOfWeek().getValue() == (weekday + 1);
		}
		return false;
	}

	@Override
	protected CellEditor getCellEditor(Object element) {
		TextCellEditor textCellEditor = new TextCellEditor(((TreeViewer) getViewer()).getTree());
		// make more room for editing
		width_i = ((TreeViewer) getViewer()).getTree().getColumn(weekday + 1).getWidth();
		width_0 = ((TreeViewer) getViewer()).getTree().getColumn(0).getWidth();
		((TreeViewer) getViewer()).getTree().getColumn(weekday + 1).setWidth(width_i + 30);
		((TreeViewer) getViewer()).getTree().getColumn(0).setWidth(width_0 - 30);
		return textCellEditor;
	}

	@Override
	protected Object getValue(Object element) {
		if (element instanceof Activity) {
			Activity activity = (Activity) element;
			StringBuilder sb = new StringBuilder();
			LocalDateTime start = activity.getStart();
			LocalDateTime end = activity.getEnd();
			sb.append(start.format(DateTimeFormatter.ofPattern("HH:mm")));
			if (end != null) {
				sb.append("-");
				sb.append(end.format(DateTimeFormatter.ofPattern("HH:mm")));
			}
			return sb.toString();
		}
		return "";
	}

	@Override
	protected void setValue(Object element, Object value) {
		if (element instanceof Activity) {
			if (value instanceof String) {
				TrackedTask trackedTask = ((Activity) element).getTrackedTask();
				ITask task =  trackedTask.getTask() == null ? 
						TimekeeperPlugin.getDefault().getTask(trackedTask) : trackedTask.getTask();
				LocalDateTime start = ((Activity) element).getStart();
				// has time point or range been specified...
				Matcher range = Pattern.compile(TIME_RANGE).matcher((String) value);
				Matcher point = Pattern.compile(TIME_POINT).matcher((String) value);

				if (range.matches()) {
					start = start.withHour(Integer.parseInt(range.group(1)));
					start = start.withMinute(Integer.parseInt(range.group(2)));
					start = start.withSecond(0);
					start = start.withNano(0);
					((Activity) element).setStart(start);

					// only set the end time if the task is not active,
					// otherwise it will be reset
					if (!trackedTask.getCurrentActivity().isPresent()
							|| !element.equals(trackedTask.getCurrentActivity().get())) {
						LocalDateTime end = ((Activity) element).getEnd();
						if (end == null) {
							end = start;
						}
						end = end.withHour(Integer.parseInt(range.group(3)));
						end = end.withMinute(Integer.parseInt(range.group(4)));
						end = end.withSecond(0);
						end = end.withNano(0);
						// also reset the end date in want of a better solution
						end = end.withYear(start.getYear());
						end = end.withDayOfYear(start.getDayOfYear());
						((Activity) element).setEnd(end);
					}
					update(element, task);

				} else if (point.matches()) {
					Assert.isNotNull(start);
					start = start.withHour(Integer.parseInt(point.group(1)));
					start = start.withMinute(Integer.parseInt(point.group(2)));
					((Activity) element).setStart(start);
					update(element, task);
				}
			}
		}
	}

	private void update(Object element, ITask task) {
		Assert.isNotNull(element);
		Assert.isNotNull(task);
		getViewer().update(element, null);
		getViewer().update(task, null);
		getViewer().update(TimekeeperUiPlugin.getProjectName(task), null);
		getViewer().update(WeekViewContentProvider.WEEKLY_SUMMARY, null);
		// restore column sizes
		((TreeViewer) getViewer()).getTree().getColumn(weekday + 1).setWidth(width_i);
		((TreeViewer) getViewer()).getTree().getColumn(0).setWidth(width_0);
	}
}