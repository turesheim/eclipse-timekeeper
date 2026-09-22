package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
import net.resheim.eclipse.timekeeper.db.model.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.db.model.OwnerIdentity;
import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.ProjectType;
import net.resheim.eclipse.timekeeper.db.model.Task;

/** The baseline SQL/label specification, constructed through the current JPA model. */
final class CurrentModelFixture {
	static final String REPOSITORY_A = "https://example.invalid/a";
	static final String REPOSITORY_B = "https://example.invalid/b";
	static final String PROJECT_A = "Upgrade – Unicode: æøå";
	static final String ADJUSTED = "Manually adjusted – Unicode: æøå";

	static void seed(EntityManager manager) {
		manager.getTransaction().begin();
		ProjectType type = new ProjectType("synthetic");
		manager.persist(type);
		Project a = project(type, PROJECT_A, REPOSITORY_A, "BASELINE-A");
		Project b = project(type, "Reports", REPOSITORY_B, "BASELINE-B");
		Task first = task(a, "1", "Build and dependencies");
		Task second = task(b, "1", "Report with Unicode: æøå");
		task(a, "2", "Task without activities");
		ActivityLabel billable = new ActivityLabel("Billable", "0,128,0");
		ActivityLabel internal = new ActivityLabel("Internal", "128,128,128");
		manager.persist(billable);
		manager.persist(internal);
		activity(first, "2022-09-19T09:00", 90, "Investigated the build", false).toggleLabel(billable);
		activity(first, "2022-09-19T13:00", 75, ADJUSTED, true).toggleLabel(billable);
		activity(second, "2022-09-19T23:30", 60, "Across midnight", false);
		activity(second, "2022-09-18T23:30", 60, "Across the week boundary", false);
		activity(second, "2022-09-20T10:00", 45, "Exported the report", false).toggleLabel(internal);
		manager.persist(a);
		manager.persist(b);
		manager.getTransaction().commit();
	}

	private static Project project(ProjectType type, String name, String repository, String externalId) {
		Project project = new Project(type, name);
		project.setRepositoryUrl(repository);
		project.setExternalId(externalId);
		return project;
	}

	private static Task task(Project project, String id, String summary) {
		Task task = new Task(summary);
		task.linkExternalTask("synthetic", project.getRepositoryUrl(), id, project.getRepositoryUrl() + "/" + id);
		task.setTaskUrl(project.getRepositoryUrl() + "/" + id);
		task.setProject(project);
		return task;
	}

	static Task task(EntityManager manager, String repository, String externalId) {
		return manager.createNamedQuery("ExternalTaskReference.findTask", Task.class)
				.setParameter("providerId", "synthetic")
				.setParameter("repositoryId", repository)
				.setParameter("externalId", externalId)
				.getSingleResult();
	}

	private static Activity activity(Task task, String start, int minutes, String summary, boolean adjusted) {
		Activity activity = new Activity(task, Instant.parse(start + ":00Z"));
		activity.setSummary(summary);
		if (adjusted) {
			activity.setDuration(Duration.ofMinutes(minutes));
		} else {
			activity.setEnd(activity.getStart().plus(Duration.ofMinutes(minutes)));
		}
		task.addActivity(activity);
		return activity;
	}

	static void verify(EntityManager manager) {
		List<Task> tasks = manager.createNamedQuery("Task.findAll", Task.class).getResultList();
		List<Activity> activities = manager.createQuery("SELECT a FROM Activity a", Activity.class).getResultList();
		List<ActivityLabel> labels = manager.createNamedQuery("ActivityLabel.findAll", ActivityLabel.class).getResultList();
		assertEquals(3, tasks.size());
		assertEquals(5, activities.size());
		assertEquals(2, labels.size());
		assertEquals(2, manager.createNamedQuery("Project.findAll", Project.class).getResultList().size());
		assertEquals(1, activities.stream().filter(Activity::isEdited).count());
		assertEquals(ADJUSTED, activities.stream().filter(Activity::isEdited).findFirst().orElseThrow().getSummary());
		assertEquals(19800, activities.stream().mapToLong(a -> a.getDuration().getSeconds()).sum());
		Map<String, Long> labelledSeconds = new HashMap<>();
		for (Activity activity : activities) {
			assertEquals(OwnerIdentity.LOCAL, activity.getOwner());
			assertNotNull(activity.getTrackedTask());
			assertTrue(activity.getTrackedTask().getActivities().contains(activity));
			for (ActivityLabel label : activity.getLabels()) {
				labelledSeconds.merge(label.getName(), activity.getDuration().getSeconds(), Long::sum);
			}
		}
		assertEquals(Map.of("Billable", 9900L, "Internal", 2700L), labelledSeconds);
		assertEquals(3, activities.stream().mapToInt(a -> a.getLabels().size()).sum());
		assertEquals(7200, activities.stream().filter(a -> a.getLabels().isEmpty())
				.mapToLong(a -> a.getDuration().getSeconds()).sum());
		for (ActivityLabel label : labels) {
			assertNotNull(label.getId());
			assertEquals(Map.of("Billable", "0,128,0", "Internal", "128,128,128").get(label.getName()), label.getColor());
		}
		for (Task task : tasks) {
			assertNotNull(task.getId());
			assertNull(task.getMylynTask(), "Reports must not require a Mylyn link");
			assertNotNull(task.getProject());
			assertTrue(task.getProject().getTasks().contains(task));
			assertEquals(1, task.getExternalReferences().size());
			ExternalTaskReference reference = task.getExternalReferences().get(0);
			assertEquals("synthetic", reference.getProviderId());
			assertEquals(task.getRepositoryUrl() + "/" + task.getTaskId(), task.getTaskUrl());
			assertEquals("synthetic", task.getProject().getProjectType().getId());
			assertTrue(task.getCurrentActivity().isEmpty());
		}
		assertEquals(3, tasks.stream().map(Task::getId).distinct().count());
		Task first = task(manager, REPOSITORY_A, "1");
		Task second = task(manager, REPOSITORY_B, "1");
		Task empty = task(manager, REPOSITORY_A, "2");
		assertNotNull(first);
		assertNotNull(second);
		assertNotNull(empty);
		assertEquals(PROJECT_A, first.getProject().getName());
		assertEquals("Reports", second.getProject().getName());
		assertEquals("BASELINE-A", first.getProject().getExternalId());
		assertEquals("BASELINE-B", second.getProject().getExternalId());
		assertEquals("Build and dependencies", first.getTaskSummary());
		assertEquals("Report with Unicode: æøå", second.getTaskSummary());
		assertEquals("Task without activities", empty.getTaskSummary());
		assertEquals(9900, first.getActivities().stream().mapToLong(a -> a.getDuration().getSeconds()).sum());
		assertEquals(9900, second.getActivities().stream().mapToLong(a -> a.getDuration().getSeconds()).sum());
		assertTrue(empty.getActivities().isEmpty());
		long[] dailySeconds = { 1800, 13500, 4500 };
		for (int i = 0; i < dailySeconds.length; i++) {
			LocalDate day = LocalDate.of(2022, 9, 18).plusDays(i);
			assertEquals(dailySeconds[i], tasks.stream()
					.mapToLong(t -> t.getDuration(day, ZoneOffset.UTC).getSeconds()).sum());
		}
	}
}
