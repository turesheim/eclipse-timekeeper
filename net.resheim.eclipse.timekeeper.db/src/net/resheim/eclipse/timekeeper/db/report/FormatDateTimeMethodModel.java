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

import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.List;

import freemarker.ext.beans.StringModel;
import freemarker.template.SimpleScalar;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;

/**
 * FreeMarker template model for formatting {@link TemporalAccessor} objects.
 * 
 * @author Torkild Ulvøy Resheim
 */
public class FormatDateTimeMethodModel implements TemplateMethodModelEx {

	public Object exec(@SuppressWarnings("rawtypes") List args) throws TemplateModelException {
		if (args.size() != 2) {
			throw new TemplateModelException("Wrong number of arguments, was expecting (DateTime, String)");
		}
		TemporalAccessor time = (TemporalAccessor) ((StringModel) args.get(0)).getWrappedObject();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern(((SimpleScalar) args.get(1)).getAsString());
		return formatter.format(time);
	}
	
}