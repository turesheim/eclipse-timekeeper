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

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.model.TrackedTask;
import net.resheim.eclipse.timekeeper.db.report.model.WorkWeek;

/**
 * This type us used to export Timekeeper data. The format is specified by a
 * FreeMarker template.
 * 
 * @author Torkild Ulvøy Resheim
 */
public class TemplateExporter extends AbstractExporter {

	static Configuration configuration;

	private final ReportTemplate reportTemplate;

	public TemplateExporter(ReportTemplate template) {
		// TODO: Clean up this mess
		configuration = new Configuration(Configuration.VERSION_2_3_26);
		StringTemplateLoader templateLoader = new StringTemplateLoader();
		configuration.setTemplateLoader(templateLoader);
		templateLoader.putTemplate(template.getName(), template.getCode());		
		this.reportTemplate = template;
	}

	@Override
	public String getData(LocalDate firstDateOfWeek) {
		try {
			Template template = configuration.getTemplate(reportTemplate.getName());
			StringWriter out = new StringWriter();

			Set<TrackedTask>filtered = TimekeeperPlugin
					.getTasks(firstDateOfWeek)
					.collect(Collectors.toSet());

			// create the objects we're reporting on
			List<WorkWeek> weeks = new ArrayList<>();
			weeks.add(new WorkWeek(firstDateOfWeek, filtered));
			// add the various models that we need for formatting and data extraction
			HashMap<String, Object> contents = new HashMap<>();
			// utility for formatting DateTime instances
			contents.put("formatDateTime", new FormatDateTimeMethodModel());
			// utility for formatting duration
			contents.put("formatDuration", new FormatDurationMethodModel());
			//
			contents.put("getActivities", new GetActivitiesMethodModel());
			// add the actual data
			contents.put("weeks", weeks);
			// and do the processing
			template.process(contents, out);
			return out.toString();

		} catch (IOException | TemplateException e) {
			e.printStackTrace();
		}
		// ignore
		return null;
	}

}
