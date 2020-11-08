/*******************************************************************************
 * Copyright © 2018-2020 Torkild U. Resheim
 * 
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

import freemarker.ext.beans.StringModel;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.Task;

/**
 * FreeMarker template model for getting retrieving activities from a task.
 * 
 * @author Torkild Ulvøy Resheim
 */
public class GetActivitiesMethodModel implements TemplateMethodModelEx {

	private boolean hasData(Activity activity, LocalDate date) {
		LocalDate endDate = date.plusDays(1);
		return activity.getDuration(date, endDate) != Duration.ZERO;
	}

	public Object exec(@SuppressWarnings("rawtypes") List args) throws TemplateModelException {
		if (args.size() != 2) {
			throw new TemplateModelException("Wrong number of arguments, was expecting (LocalDate, ITask)");
		}
		LocalDate date = (LocalDate) ((StringModel) args.get(0)).getWrappedObject();
		Task task = (Task) ((StringModel) args.get(1)).getWrappedObject();
		return task.getActivities()
				.stream()
				.filter(a -> hasData(a, date))
				.toArray();
	}
}