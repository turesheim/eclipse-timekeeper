/*******************************************************************************
 * Copyright © 2018 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db.report;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.mylyn.tasks.core.ITask;

import freemarker.ext.beans.StringModel;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;

/**
 * FreeMarker template model for formatting {@link Duration} instances. 
 * 
 * @author Torkild Ulvøy Resheim
 */
public class FormatDurationMethodModel implements TemplateMethodModelEx {
	
	public Object exec(@SuppressWarnings("rawtypes") List args) throws TemplateModelException {
		if (args.size() == 1) {
			Duration duration = (Duration) ((StringModel) args.get(0)).getWrappedObject();
			long seconds = duration.getSeconds();
			if (seconds > 60) {
				return DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true);
			} else return "";
		}
		if (args.size() != 2) {
			throw new TemplateModelException("Wrong number of arguments, was expecting (LocalDate, ITask)");
		}
		LocalDate day = (LocalDate) ((StringModel) args.get(0)).getWrappedObject();
		long seconds = 0;
		if ((((StringModel) args.get(1)).getWrappedObject()) instanceof ITask) {
			ITask task = (ITask) ((StringModel) args.get(1)).getWrappedObject();
			TrackedTask ttask = TimekeeperPlugin.getDefault().getTask(task);
			seconds = ttask.getDuration(day).getSeconds();
		}
		if ((((StringModel) args.get(1)).getWrappedObject()) instanceof Activity) {
			Activity task = (Activity) ((StringModel) args.get(1)).getWrappedObject();
			seconds = task.getDuration(day).getSeconds();
		}
		if (seconds > 60) {
			return DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true);
		}
		return "";
	}
}