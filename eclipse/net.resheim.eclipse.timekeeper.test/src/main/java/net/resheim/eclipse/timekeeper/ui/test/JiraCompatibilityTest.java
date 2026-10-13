package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMapper;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.core.data.TaskMapper;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.mylyn.JiraGroupingContribution;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.mylyn.MylynMetadata;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/**
 * JiraConnector 6 fixtures: numeric task ID, separate issue key, product ID
 * with options, and attribute.jira.custom::customfield_* attributes. These
 * exercise Timekeeper's adapter without contacting Jira or requiring secrets.
 * Field provenance is recorded in baseline/JIRA-COMPATIBILITY.md.
 */
@SuppressWarnings("restriction")
public class JiraCompatibilityTest {
	private static final String REPOSITORY = "https://jira.example.test/timekeeper-compat";
	private static final String CUSTOM = "attribute.jira.custom::customfield_10001";

	private static final class JiraTask extends AbstractTask {
		JiraTask(String id, String key, String summary) {
			super(REPOSITORY, id, summary);
			setTaskKey(key);
			setUrl(REPOSITORY + "/browse/" + key);
		}
		@Override public boolean isLocal() { return false; }
		@Override public String getConnectorKind() { return "jira"; }
	}

	private static TaskData data(TaskRepository repository) {
		var data = new TaskData(new TaskAttributeMapper(repository), "jira", REPOSITORY, "1042");
		data.getRoot().createAttribute(TaskAttribute.TASK_KEY);
		data.getRoot().createAttribute(TaskAttribute.SUMMARY);
		data.getRoot().createAttribute(TaskAttribute.TASK_URL);
		var mapper = new TaskMapper(data);
		mapper.setTaskKey("TK-42");
		mapper.setSummary("Check updated connector");
		mapper.setTaskUrl(REPOSITORY + "/browse/TK-42");
		var project = data.getRoot().createAttribute(TaskAttribute.PRODUCT);
		project.putOption("10000", "Timekeeper");
		project.setValue("10000");
		return data;
	}

	@Test
	public void jiraProjectUsesDisplayNameRatherThanNumericId() {
		var repository = new TaskRepository("jira", REPOSITORY);
		var task = new JiraTask("1042", "TK-42", "");
		var data = data(repository);
		new TaskMapper(data).applyTo(task);
		assertEquals("1042", task.getTaskId());
		assertEquals("TK-42", task.getTaskKey());
		assertEquals("Check updated connector", task.getSummary());
		assertEquals(REPOSITORY + "/browse/TK-42", task.getUrl());
		assertEquals("Timekeeper", MylynMetadata.getMylynProjectName(task, repository, data));
		assertEquals("None", MylynMetadata.getMylynProjectName(task, repository, null));
	}

	@Test
	public void absentEmptyAndUnavailableCustomValuesGroupUnderNone() {
		var repository = new TaskRepository("jira", REPOSITORY);
		repository.setProperty(MylynMetadata.ATTR_GROUPING, "customfield_10001");
		var task = new JiraTask("1042", "TK-42", "Check grouping");
		var data = data(repository);
		assertEquals("None", MylynMetadata.getMylynProjectName(task, repository, data));
		assertEquals("None", MylynMetadata.getMylynProjectName(task, repository, null));
		var attribute = data.getRoot().createAttribute(CUSTOM);
		for (String value : List.of("", "   ")) {
			attribute.setValue(value);
			assertEquals("None", MylynMetadata.getMylynProjectName(task, repository, data));
		}
		attribute.putOption("10", "Platform team");
		attribute.putOption("20", "Release team");
		attribute.setValues(List.of("10", "20"));
		assertEquals("Platform team, Release team", MylynMetadata.getMylynProjectName(task, repository, data));
		repository.setProperty(MylynMetadata.ATTR_GROUPING, CUSTOM);
		assertEquals("Platform team, Release team", MylynMetadata.getMylynProjectName(task, repository, data));
		attribute.setValue("Unlisted value");
		assertEquals("Unlisted value", MylynMetadata.getMylynProjectName(task, repository, data));
	}

	@Test
	public void categoryGroupingRemainsAvailableWithoutCachedTaskData() {
		var repository = new TaskRepository("jira", REPOSITORY);
		repository.setProperty(MylynMetadata.ATTR_GROUPING, MylynMetadata.GROUP_MYLYN_CATEGORY);
		var task = new JiraTask("1042", "TK-42", "Check category");
		var list = new TaskList();
		list.addUnmatchedContainer(new org.eclipse.mylyn.internal.tasks.core.UnmatchedTaskContainer("jira", REPOSITORY));
		var category = new TaskCategory("jira-category-fixture", "Release work");
		list.addCategory(category);
		list.addTask(task, category);
		assertEquals("Release work", MylynMetadata.getMylynProjectName(task, repository, null));
	}

	@Test
	public void groupingEditorValidatesPersistsAndRestoresSelection() throws Exception {
		onUi(() -> {
			var repository = new TaskRepository("jira", REPOSITORY);
			var shell = new Shell(PlatformUI.getWorkbench().getDisplay());
			shell.setText("Jira grouping acceptance");
			shell.setLayout(new org.eclipse.swt.layout.FillLayout());
			try {
				var contribution = new JiraGroupingContribution();
				contribution.init("jira", repository);
				contribution.createControl(shell);
				shell.pack();
				shell.open();
				var bot = new SWTBot(shell);
				bot.comboBox().setSelection("Custom field");
				assertFalse(contribution.isPageComplete());
				bot.text().setText(" customfield_10001 ");
				assertTrue(contribution.isPageComplete());
				contribution.applyTo(repository);
				assertEquals("customfield_10001", repository.getProperty(MylynMetadata.ATTR_GROUPING));
				shell.getChildren()[0].dispose();
				var restored = new JiraGroupingContribution();
				restored.init("jira", repository);
				restored.createControl(shell);
				shell.pack();
				assertEquals("Custom field", bot.comboBox().selection());
				assertEquals("customfield_10001", bot.text().getText());
				bot.comboBox().setSelection("Mylyn category");
				restored.applyTo(repository);
				assertEquals(MylynMetadata.GROUP_MYLYN_CATEGORY, repository.getProperty(MylynMetadata.ATTR_GROUPING));
				bot.comboBox().setSelection("Jira project");
				restored.applyTo(repository);
				assertNull(repository.getProperty(MylynMetadata.ATTR_GROUPING));
			} finally {
				shell.dispose();
			}
		});
	}

	@Test
	public void switchingReconnectAndOfflineEditingKeepNumericIdentityAndHistory() throws Exception {
		var bot = new SWTWorkbenchBot();
		bot.waitUntil(new DefaultCondition() {
			@Override public boolean test() { return TimekeeperPlugin.getDefault().isReady(); }
			@Override public String getFailureMessage() { return "Database not ready"; }
		});
		var service = TimekeeperUiPlugin.getDefault().getTimekeeperService();
		Set<?> previousProjects = service.projects().stream().map(project -> project.id()).collect(java.util.stream.Collectors.toSet());
		var first = new JiraTask("1042", "TK-42", "Track Jira fixture");
		var second = new JiraTask("1043", "TK-43", "Switch Jira fixture");
		Task[] tracked = new Task[2];
		try {
			onUi(() -> {
				var adapter = MylynIntegration.getDefault();
				tracked[0] = adapter.resolve(first);
				tracked[1] = adapter.resolve(second);
				assertEquals("jira", tracked[0].externalReferences().iterator().next().providerId());
				assertEquals("1042", tracked[0].externalReferences().iterator().next().externalId());
				var list = TasksUiPlugin.getTaskList();
				if (list.getUnmatchedContainers().stream().noneMatch(container -> container.getRepositoryUrl().equals(REPOSITORY)))
					list.addUnmatchedContainer(new org.eclipse.mylyn.internal.tasks.core.UnmatchedTaskContainer("jira", REPOSITORY));
				list.addTask(first);
				first.setTaskKey("LIVE-42");
				assertEquals(Optional.of("LIVE-42"), adapter.displayId(tracked[0]));
				assertEquals("LIVE-42: Track Jira fixture", renderedTitle(tracked[0]));
				first.setTaskKey(first.getTaskId());
				assertEquals("TK-42: Track Jira fixture", renderedTitle(tracked[0]));
				first.setTaskKey("TK-42");
				adapter.activated(first);
				var original = service.activeActivity(OwnerId.LOCAL).orElseThrow();
				adapter.activated(first);
				assertEquals(original.id(), service.activeActivity(OwnerId.LOCAL).orElseThrow().id());
				adapter.activated(second);
				var successor = service.activeActivity(OwnerId.LOCAL).orElseThrow();
				assertEquals(tracked[1].id(), successor.taskId());
				assertTrue(service.activity(original.id()).orElseThrow().end().isPresent());
				adapter.deactivated(first);
				adapter.deactivated(first);
				assertEquals(successor.id(), service.activeActivity(OwnerId.LOCAL).orElseThrow().id());
				adapter.deactivated(second);
				assertTrue(service.activeActivity(OwnerId.LOCAL).isEmpty());
				list.deleteTask(first);
				assertEquals(Optional.of("TK-42"), adapter.displayId(service.task(tracked[0].id()).orElseThrow()));
				assertEquals("TK-42: Track Jira fixture", renderedTitle(service.task(tracked[0].id()).orElseThrow()));
				var replacement = new JiraTask("1042", "MOVED-42", "Updated Jira summary");
				assertEquals(tracked[0].id(), adapter.resolve(replacement).id());
				var history = service.activity(original.id()).orElseThrow();
				assertEquals(tracked[0].id(), history.taskId());
				assertEquals(Optional.of("MOVED-42"), adapter.displayId(service.task(tracked[0].id()).orElseThrow()));
				var label = service.labels().getFirst();
				var edited = service.updateActivity(new UpdateActivity(history.id(), history.version(), history.start(),
						history.end(), "Edited while Jira is offline", Set.of(label.id())));
				assertEquals("Edited while Jira is offline", edited.summary());
				assertTrue(edited.labelIds().contains(label.id()));
			});
			var exported = Files.createTempDirectory("timekeeper-jira-csv-");
			try {
				TimekeeperPlugin.getDefault().exportTo(exported);
				assertTrue(Files.readString(exported.resolve("activity.csv")).contains("Edited while Jira is offline"));
				assertTrue(Files.readString(exported.resolve("external_task_reference.csv")).contains("1042"));
			} finally {
				try (var paths = Files.walk(exported)) {
					for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
				}
			}
		} finally {
			onUi(() -> {
				TasksUiPlugin.getTaskList().deleteTask(first);
				TasksUiPlugin.getTaskList().deleteTask(second);
			});
			for (Task task : tracked) {
				if (task == null) continue;
				for (var activity : service.activities(new ActivityQuery(Instant.EPOCH, Instant.now().plusSeconds(60),
						Optional.empty(), Optional.of(task.id()), Optional.empty(), TimekeeperUiPlugin.getCalendarZone())).activities())
				{
					var closed = activity.end().isEmpty() ? service.stopActivity(new StopActivity(activity.id(), activity.version())) : activity;
					service.deleteActivity(new DeleteActivity(closed.id(), closed.version()));
				}
				service.task(task.id()).ifPresent(current -> service.deleteTask(new DeleteTask(current.id(), current.version())));
			}
			for (var project : service.projects())
				if (!previousProjects.contains(project.id()) && service.tasks().stream().noneMatch(task -> task.projectId().filter(project.id()::equals).isPresent()))
					service.deleteProject(new DeleteProject(project.id(), project.version()));
		}
	}

	private static String renderedTitle(Task task) {
		try {
			var type = Class.forName("net.resheim.eclipse.timekeeper.ui.views.TitleColumnLabelProvider", true,
					TimekeeperUiPlugin.class.getClassLoader());
			var constructor = type.getDeclaredConstructor(net.resheim.eclipse.timekeeper.ui.views.WeekViewContentProvider.class);
			constructor.setAccessible(true);
			var provider = (org.eclipse.jface.viewers.ColumnLabelProvider) constructor.newInstance((Object) null);
			try {
				return provider.getText(task);
			} finally {
				provider.dispose();
			}
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static void onUi(Runnable action) throws Exception {
		var task = new FutureTask<Void>(action, null);
		PlatformUI.getWorkbench().getDisplay().asyncExec(task);
		task.get(20, TimeUnit.SECONDS);
	}
}
