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
import java.time.ZoneId;
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
import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.db.report.model.WorkWeek;

/**
 * This type us used to export Timekeeper data. The format is specified by a
 * FreeMarker template.
 * 
 * @author Torkild Ulvøy Resheim
 */
public class TemplateExporter extends AbstractExporter {

	private final Configuration configuration;

	private final ReportTemplate reportTemplate;

	public TemplateExporter(ReportTemplate template, ZoneId zoneId) {
		super(zoneId);
		// TODO: Clean up this mess
		configuration = new Configuration(Configuration.VERSION_2_3_26);
		StringTemplateLoader templateLoader = new StringTemplateLoader();
		configuration.setTemplateLoader(templateLoader);
		templateLoader.putTemplate(template.getName(), template.getCode());		
		this.reportTemplate = template;
	}

	@Override
	public String getData(LocalDate firstDateOfWeek) {
		Set<Task> filtered = TimekeeperPlugin.getTasks(firstDateOfWeek, zoneId).collect(Collectors.toSet());
		return render(firstDateOfWeek, filtered);
	}

	/** Renders an immutable service report using detached template beans; no persistence context is accessed. */
	public String getData(LocalDate firstDateOfWeek, net.resheim.eclipse.timekeeper.service.TimekeeperService service) {
		var query = new net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery(
				firstDateOfWeek.atStartOfDay(zoneId).toInstant(), firstDateOfWeek.plusDays(7).atStartOfDay(zoneId).toInstant(),
				java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), zoneId);
		var snapshots = service.tasks().stream().collect(Collectors.toMap(
				net.resheim.eclipse.timekeeper.domain.Task::id, java.util.function.Function.identity()));
		var projects = service.projects().stream().collect(Collectors.toMap(
				net.resheim.eclipse.timekeeper.domain.Project::id,
				project -> new net.resheim.eclipse.timekeeper.db.model.Project(project.id().value().toString(), project.name())));
		var labels = service.labels().stream().collect(Collectors.toMap(
				net.resheim.eclipse.timekeeper.domain.Label::id,
				label -> new net.resheim.eclipse.timekeeper.db.model.ActivityLabel(label.id().value().toString(),
						label.name(), label.color().orElse(null))));
		var unassigned = new net.resheim.eclipse.timekeeper.db.model.Project("unassigned", "No project");
		java.util.Map<net.resheim.eclipse.timekeeper.domain.TaskId, Task> tasks = new HashMap<>();
		for (var recorded : service.activities(query).activities()) {
			Task task = tasks.computeIfAbsent(recorded.taskId(), id -> {
				var snapshot = snapshots.get(id);
				if (snapshot == null) snapshot = service.task(id).orElseThrow();
				Task bean = new Task(id.value().toString(), snapshot.summary());
				bean.setTaskUrl(snapshot.url().orElse(null));
				bean.setProject(snapshot.projectId().map(projects::get).orElse(unassigned));
				for (var reference : snapshot.externalReferences()) {
					bean.linkExternalTask(reference.providerId(), reference.repositoryId(), reference.externalId(),
							reference.externalUrl().orElse(null));
				}
				return bean;
			});
			var bean = new net.resheim.eclipse.timekeeper.db.model.Activity(recorded.id().value().toString(), task,
					new net.resheim.eclipse.timekeeper.db.model.OwnerIdentity(recorded.ownerId().value()), recorded.start());
			bean.setEnd(recorded.end().orElse(null));
			bean.setSummary(recorded.summary());
			bean.setManual(recorded.manual());
			bean.setLabels(recorded.labelIds().stream().map(labels::get).filter(java.util.Objects::nonNull).toList());
			task.addActivity(bean);
		}
		return render(firstDateOfWeek, new java.util.HashSet<>(tasks.values()));
	}

	private String render(LocalDate firstDateOfWeek, Set<Task> filtered) {
		try {
			Template template = configuration.getTemplate(reportTemplate.getName());
			StringWriter out = new StringWriter();

			// create the objects we're reporting on
			List<WorkWeek> weeks = new ArrayList<>();
			weeks.add(new WorkWeek(firstDateOfWeek, filtered, zoneId));
			// add the various models that we need for formatting and data extraction
			HashMap<String, Object> contents = new HashMap<>();
			// utility for formatting DateTime instances
			contents.put("formatDateTime", new FormatDateTimeMethodModel());
			// utility for formatting duration
			contents.put("formatDuration", new FormatDurationMethodModel(zoneId));
			//
			contents.put("getActivities", new GetActivitiesMethodModel(zoneId));
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
