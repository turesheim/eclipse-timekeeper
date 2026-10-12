package net.resheim.eclipse.timekeeper.jira.test;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.mylyn.commons.net.AuthenticationCredentials;
import org.eclipse.mylyn.commons.net.AuthenticationType;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.RepositoryQuery;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.*;
import org.eclipse.mylyn.tasks.core.data.*;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

@SuppressWarnings("restriction")
final class JiraTestSupport {
    static final String VERSION = "6.0.0.202605160149";
    static Path proof() { return Path.of(System.getProperty("timekeeper.jira.proof")); }
    static TimekeeperService service() { return TimekeeperUiPlugin.getDefault().getTimekeeperService(); }
    static void ready() {
        waitFor(() -> TimekeeperPlugin.getDefault() != null && TimekeeperPlugin.getDefault().isReady(), "Database not ready");
        waitFor(() -> net.resheim.eclipse.timekeeper.mylyn.MylynIntegration.getDefault() != null, "Adapter not ready");
    }
    static void waitFor(BooleanSupplier condition, String message) {
        new SWTWorkbenchBot().waitUntil(new DefaultCondition() {
            @Override public boolean test() { return condition.getAsBoolean(); }
            @Override public String getFailureMessage() { return message; }
        }, 20000);
    }
    static <T> T onUi(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        PlatformUI.getWorkbench().getDisplay().asyncExec(task);
        return task.get(30, TimeUnit.SECONDS);
    }
    static TaskRepository repository(JiraRestFixture fixture, JiraRestFixture.Auth auth, String token) throws Exception {
        var repository = new TaskRepository("jira", fixture.url());
        repository.setRepositoryLabel("Timekeeper CI " + auth);
        repository.setCredentials(AuthenticationType.REPOSITORY,
                new AuthenticationCredentials(auth == JiraRestFixture.Auth.BASIC ? JiraRestFixture.USER : "", token), false);
        onUi(() -> { TasksUi.getRepositoryManager().addRepository(repository); return null; });
        return repository;
    }
    static AbstractRepositoryConnector connector() {
        var connector = TasksUi.getRepositoryConnector("jira");
        assertNotNull("The released Jira connector must be installed", connector);
        assertEquals("me.glindholm.connector.eclipse.internal.jira.core.JiraRepositoryConnector", connector.getClass().getName());
        assertEquals(VERSION, org.osgi.framework.FrameworkUtil.getBundle(connector.getClass()).getVersion().toString());
        return connector;
    }
    static List<TaskData> query(TaskRepository repository) {
        var query = new RepositoryQuery("jira", "Timekeeper CI issues");
        query.setRepositoryUrl(repository.getRepositoryUrl());
        query.setAttribute("FilterID", "10000");
        query.setAttribute("FilterName", "Timekeeper CI");
        query.setAttribute("FilterJql", "project = TK ORDER BY key");
        var data = new ArrayList<TaskData>();
        var status = connector().performQuery(repository, query, new TaskDataCollector() {
            @Override public void accept(TaskData task) { data.add(task); }
        }, null, new NullProgressMonitor());
        assertTrue("Real Jira query failed: " + status, status.isOK());
        assertEquals(2, data.size());
        assertEquals(Set.of("1042", "1043"), data.stream().map(TaskData::getTaskId).collect(java.util.stream.Collectors.toSet()));
        return data;
    }
    static TaskData fetch(TaskRepository repository, String id) throws Exception {
        return connector().getTaskData(repository, id, new NullProgressMonitor());
    }
    static ITask importTask(TaskRepository repository, TaskData data) throws Exception {
        return onUi(() -> {
            var task = TasksUi.getRepositoryModel().createTask(repository, data.getTaskId());
            connector().updateTaskFromTaskData(repository, task, data);
            TasksUiPlugin.getTaskList().addTask((AbstractTask) task);
            TasksUiPlugin.getTaskDataManager().putUpdatedTaskData(task, data, true);
            return task;
        });
    }
    static void remove(TaskRepository repository) throws Exception {
        onUi(() -> {
            TasksUi.getTaskActivityManager().deactivateActiveTask();
            for (var task : List.copyOf(TasksUiPlugin.getTaskList().getAllTasks()))
                if (task.getRepositoryUrl().equals(repository.getRepositoryUrl())) TasksUiPlugin.getTaskList().deleteTask(task);
            TasksUiPlugin.getRepositoryManager().removeRepository(repository);
            return null;
        });
    }
    static Properties readProof() throws Exception {
        var result = new Properties();
        try (var stream = Files.newInputStream(proof())) { result.load(stream); }
        return result;
    }
    static void writeProof(Properties proof) throws Exception {
        Files.createDirectories(proof().getParent());
        try (var stream = Files.newOutputStream(proof())) { proof.store(stream, "First Eclipse process: durable Jira acceptance records"); }
    }
}
