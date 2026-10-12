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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.TreeViewer;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Edits an interval without dereferencing an external task or changing the persisted date. */
class TimeEditingSupport extends EditingSupport {
	private final WeekViewContentProvider content;
	private final int weekday;
	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT);
	TimeEditingSupport(TreeViewer viewer, WeekViewContentProvider content, int weekday) {
		super(viewer);
		this.content = content;
		this.weekday = weekday;
	}
	@Override protected boolean canEdit(Object element) {
		if (!(element instanceof Activity activity)) return false;
		var date = content.getDate(weekday);
		return activity.start().atZone(content.getZoneId()).toLocalDate().equals(date)
				|| activity.end().map(end -> end.atZone(content.getZoneId()).toLocalDate().equals(date)).orElse(false);
	}
	@Override protected CellEditor getCellEditor(Object element) {
		return new TextCellEditor(((TreeViewer) getViewer()).getTree());
	}
	@Override protected Object getValue(Object element) {
		Activity activity = (Activity) element;
		return FORMAT.format(activity.start().atZone(content.getZoneId()))
				+ activity.end().map(end -> "-" + FORMAT.format(end.atZone(content.getZoneId()))).orElse("");
	}
	@Override protected void setValue(Object element, Object value) {
		Activity activity = (Activity) element;
		try {
			String text = value.toString().trim();
			if (text.equals(getValue(element))) return;
			if (!text.matches("\\d{1,2}:\\d{2}(-\\d{1,2}:\\d{2})?")) {
				throw new IllegalArgumentException("Use HH:mm or HH:mm-HH:mm.");
			}
			String[] parts = text.split("-");
			java.time.Instant start = endpoint(activity.start(), parts[0]);
			Optional<java.time.Instant> end = activity.end();
			if (parts.length == 2 && end.isPresent()) {
				end = Optional.of(endpoint(end.orElseThrow(), parts[1]));
			}
			if (end.isEmpty() && start.isAfter(java.time.Instant.now())) {
				throw new IllegalArgumentException("An ongoing activity cannot start in the future.");
			}
            var command=new UpdateActivity(activity.id(),activity.version(),start,end,activity.summary(),activity.labelIds());
            TimekeeperUiPlugin.getDefault().runOperation("Edit activity time",()->TimekeeperUiPlugin.getDefault().getTimekeeperService().updateActivity(command),saved->content.databaseStateChanged(),failure->
                MessageDialog.openError(getViewer().getControl().getShell(),"Could not edit activity",ActivityDialog.failureMessage(failure)));
		} catch (RuntimeException failure) {
			MessageDialog.openError(getViewer().getControl().getShell(), "Could not edit activity", ActivityDialog.failureMessage(failure));
		}
		content.databaseStateChanged();
	}
	private java.time.Instant endpoint(java.time.Instant stored, String value) {
		var local = stored.atZone(content.getZoneId());
		String normalized = padded(value);
		return FORMAT.format(local).equals(normalized) ? stored : local.toLocalDate()
				.atTime(LocalTime.parse(normalized, FORMAT)).atZone(content.getZoneId()).toInstant();
	}
	private String padded(String time) { return time.length() == 4 ? "0" + time : time; }
}
