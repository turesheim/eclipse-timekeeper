/*******************************************************************************
 * Copyright © 2017-2020 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.db;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.Query;

import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import freemarker.cache.FileTemplateLoader;
import freemarker.core.ParseException;
import freemarker.template.Configuration;
import freemarker.template.MalformedTemplateNameException;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateNotFoundException;
import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.TrackedTask;
import net.resheim.eclipse.timekeeper.db.report.FormatDateTimeMethodModel;
import net.resheim.eclipse.timekeeper.db.report.FormatDurationMethodModel;
import net.resheim.eclipse.timekeeper.db.report.GetActivitiesMethodModel;
import net.resheim.eclipse.timekeeper.db.report.model.WorkWeek;

/**
 * Used to verify that the report template mechanism works. This will run a
 * parameterized test over all templates found in the "templates" folder and for
 * each generate a file placed in "test-reports".
 * 
 * @author Torkild Ulvøy Resheim
 */
@SuppressWarnings("restriction")
public class TemplateTest {
	
	private final String[] VERBS = {"investigated","fixed", "studied"}; 
	private final String[] SUBJECTS = {"weird code","annoying bug", "new feature", "build service"}; 	
	private static EntityManager entityManager;
	
	static {
		entityManager = PersistenceHelper.getEntityManager(); 
	}

	static Configuration configuration;
	
	@BeforeAll
	public static void before() throws IOException {
		configuration = new Configuration(Configuration.VERSION_2_3_27);
		configuration.setTemplateLoader(new FileTemplateLoader(new File("./templates/")));
		TimekeeperPlugin.setEntityManager(entityManager);
		Files.createDirectories(Paths.get("./test-reports"));
	}

	@AfterEach
	public void after() {
		// empty all tables
		EntityTransaction transaction = entityManager.getTransaction();
		if (transaction.isActive()) {
			transaction.rollback();
		}
		// clean up after running tests
		transaction.begin();
		Query createQuery = entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE;TRUNCATE TABLE ACTIVITY;TRUNCATE TABLE TRACKEDTASK;SET REFERENTIAL_INTEGRITY TRUE");
		createQuery.executeUpdate();
		transaction.commit();
	}
	
	@ParameterizedTest
	@MethodSource(value = "listTemplates")
	public void testSimpleTemplate(String name) throws TemplateNotFoundException, MalformedTemplateNameException, ParseException,
			IOException, TemplateException {
		Template template = configuration.getTemplate(name, Locale.getDefault(), "utf-8", true);
		File result = new File("test-reports/" + name);
		FileOutputStream fos = new FileOutputStream(result);
		Writer out = new OutputStreamWriter(fos);
		
		// create the objects we're reporting on
		List<WorkWeek> weeks  = new ArrayList<>();
		// create a new work week instance with associated tasks
		weeks.add(new WorkWeek(LocalDate.of(1969, 3, 10), createTestTasks()));
		
		// add the various models that we need for formatting and data extraction
		HashMap<String, Object> contents = new HashMap<>();
		// utility for formatting DateTime instances
		contents.put("formatDateTime", new FormatDateTimeMethodModel());
		// utility for formatting duration
		contents.put("formatDuration", new FormatDurationMethodModel());
		// utility for getting activities from a task
		contents.put("getActivities", new GetActivitiesMethodModel());
		// add the actual data
		contents.put("weeks", weeks);
		// and do the processing
		template.process(contents, out);
	}
	
	static Stream<String> listTemplates() throws IOException{
		return Files.list(Paths.get("templates")).map(f -> f.getFileName().toString());
	}

	private void persist(TrackedTask ttask) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		entityManager.persist(ttask);
		transaction.commit();
	}

	/**
	 * Creates a number of tasks for use in the template test.
	 */
	private Set<TrackedTask> createTestTasks() {
		int vi = 0;
		int si = 0;
		TaskList tl = new TaskList();
		TaskCategory[] projects = new TaskCategory[] {
			new TaskCategory("project-a", "Project A"),
			new TaskCategory("project-b", "Project B")
		};
		tl.addCategory(projects[0]);
		tl.addCategory(projects[1]);
		Set<TrackedTask> tasks = new HashSet<>();
		// for each day of the week, create one task
		for (int i = 1; i < 7; i++) {
			AbstractTask mylynTask = new LocalTask(String.valueOf(i), "Task #" + i);
//			tasks.add(mylynTask);
			tl.addTask(mylynTask, projects[i%2]);
			TrackedTask task = new TrackedTask();
			// for each task, create one activity 
			for (int d = 1; d < 3 + (d%i); d++) {
				int offset = d + i - 2;
				// create a new activity
				Activity a1 = new Activity();
				a1.setSummary(String.format("Activity %1$s %2$s", VERBS[vi], SUBJECTS[si]));
				LocalDateTime start = LocalDateTime.of(1969, 3, 10+offset, 8, 0);
				task.addActivity(a1);
				a1.setStart(start);
				a1.setEnd(start.plusMinutes(30+(d*10)));
				vi++; si++;
				if (vi==VERBS.length)vi=0;
				if (si==VERBS.length)si=0;
			}
			persist(task);
			tasks.add(task);
		}
		return tasks;
	}
}
