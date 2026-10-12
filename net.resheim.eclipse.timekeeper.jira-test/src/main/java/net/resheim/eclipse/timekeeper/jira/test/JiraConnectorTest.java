package net.resheim.eclipse.timekeeper.jira.test;

import static org.junit.Assert.*;
import static net.resheim.eclipse.timekeeper.jira.test.JiraTestSupport.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.*;
import org.eclipse.mylyn.tasks.ui.*;
import org.eclipse.ui.PlatformUI;
import org.junit.*;
import org.junit.runners.MethodSorters;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.mylyn.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

@SuppressWarnings("restriction")
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class JiraConnectorTest {
    private final List<IStatus> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ILogListener listener = (status, plugin) -> {
        if (status.matches(IStatus.ERROR) && (plugin.startsWith("net.resheim.eclipse.timekeeper") || plugin.startsWith("me.glindholm")
                || status.getMessage().contains("net.resheim.eclipse.timekeeper.mylyn")))
            errors.add(status);
    };
    @Before public void before() { Platform.addLogListener(listener); ready(); connector(); }
    @After public void after() { Platform.removeLogListener(listener); }

    @Test public void a_basicQueryEditorTrackingReconnectAndOfflineHistory() throws Exception {
        try (var fixture = new JiraRestFixture(JiraRestFixture.Auth.BASIC, 0)) {
            var repository = repository(fixture, JiraRestFixture.Auth.BASIC, JiraRestFixture.TOKEN);
            try {
                query(repository);
                var data = fetch(repository, "1042");
                var first = importTask(repository, data);
                var second = importTask(repository, fetch(repository, "1043"));
                assertEquals("TK-42", first.getTaskKey());
                assertEquals("Jira CI issue 1042", first.getSummary());
                assertEquals(fixture.url() + "/browse/TK-42", first.getUrl());
                assertEquals("Timekeeper CI", MylynMetadata.getMylynProjectName(first, repository, data));
                var nativeTask = onUi(() -> MylynIntegration.getDefault().resolve(first));
                onUi(() -> {
                    assertTrue("Real Jira task editor must open", TasksUiUtil.openTask(first));
                    var editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
                    assertNotNull(editor);
                    assertTrue("Expected the Mylyn task editor: " + editor.getClass(), editor instanceof org.eclipse.mylyn.tasks.ui.editors.TaskEditor);
                    return null;
                });
                onUi(() -> { TasksUi.getTaskActivityManager().activateTask(first); return null; });
                waitFor(() -> service().activeActivity(OwnerId.LOCAL).filter(a -> a.taskId().equals(nativeTask.id())).isPresent(), "Jira activation did not start tracking");
                var initial = service().activeActivity(OwnerId.LOCAL).orElseThrow();
                // Mylyn deliberately deactivates/re-activates, even for the same task.
                // This differs from duplicate activation notifications covered by the adapter tests.
                onUi(() -> { TasksUi.getTaskActivityManager().activateTask(first); return null; });
                waitFor(() -> service().activity(initial.id()).orElseThrow().end().isPresent(), "Reactivation did not close the initial interval");
                var interval = service().activeActivity(OwnerId.LOCAL).orElseThrow();
                assertNotEquals(initial.id(), interval.id());
                assertEquals(nativeTask.id(), interval.taskId());
                onUi(() -> { TasksUi.getTaskActivityManager().activateTask(second); return null; });
                waitFor(() -> service().activity(interval.id()).orElseThrow().end().isPresent(), "Switch did not close the first interval");
                var successor = service().activeActivity(OwnerId.LOCAL).orElseThrow();
                assertNotEquals(nativeTask.id(), successor.taskId());
                onUi(() -> { TasksUi.getTaskActivityManager().deactivateTask(first); return null; });
                assertEquals(successor.id(), service().activeActivity(OwnerId.LOCAL).orElseThrow().id());
                onUi(() -> { TasksUi.getTaskActivityManager().deactivateActiveTask(); return null; });
                waitFor(() -> service().activeActivity(OwnerId.LOCAL).isEmpty(), "Deactivation did not stop tracking");
                onUi(() -> {
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false);
                    TasksUiPlugin.getTaskList().deleteTask((AbstractTask) first);
                    return null;
                });
                fixture.offline = true;
                int expectedErrors = errors.size();
                assertThrows(CoreException.class, () -> fetch(repository, "1042"));
                assertTrue(fixture.requests().stream().anyMatch(line -> line.contains(" -> 503")));
                acceptExpectedHttpErrors(expectedErrors, "503");
                var history = service().activity(interval.id()).orElseThrow();
                var label = service().labels().getFirst();
                var edited = service().updateActivity(new UpdateActivity(history.id(), history.version(), history.start(), history.end(),
                        "Edited while Jira unavailable", Set.of(label.id())));
                var report = service().activities(new ActivityQuery(Instant.EPOCH, Instant.now().plusSeconds(60), Optional.of(OwnerId.LOCAL),
                        Optional.of(nativeTask.id()), Optional.empty(), TimekeeperUiPlugin.getCalendarZone()));
                assertTrue(report.activities().stream().anyMatch(a -> a.id().equals(edited.id()) && a.summary().equals(edited.summary())));
                assertTrue(report.total().toNanos() > 0);
                var csv = proof().getParent().resolve("offline-csv");
                Files.createDirectories(csv);
                TimekeeperPlugin.getDefault().exportTo(csv);
                assertTrue(Files.readString(csv.resolve("activity.csv")).contains(edited.summary()));
                assertTrue(Files.readString(csv.resolve("external_task_reference.csv")).contains("1042"));
                fixture.offline = false; fixture.moved = true;
                me.glindholm.connector.eclipse.internal.jira.core.JiraClientFactory.getDefault().clearClients();
                var reconnected = importTask(repository, fetch(repository, "1042"));
                assertEquals("MOVED-42", reconnected.getTaskKey());
                var resolved = onUi(() -> MylynIntegration.getDefault().resolve(reconnected));
                assertEquals(nativeTask.id(), resolved.id());
                assertEquals(Optional.of("MOVED-42"), onUi(() -> MylynIntegration.getDefault().displayId(resolved)));
                assertEquals(edited.id(), service().activity(edited.id()).orElseThrow().id());
                var proof = new Properties();
                proof.setProperty("port", Integer.toString(fixture.port()));
                proof.setProperty("task", nativeTask.id().value().toString());
                proof.setProperty("activity", edited.id().value().toString());
                proof.setProperty("label", label.id().value().toString());
                proof.setProperty("firstProcess", Long.toString(ProcessHandle.current().pid()));
                writeProof(proof);
                assertTrue("Unexpected Jira/Timekeeper errors: " + errors, errors.isEmpty());
                assertTrue("Unknown REST requests: " + fixture.unexpected(), fixture.unexpected().isEmpty());
                assertEquals(0, fixture.rejectedCredentials);
            } finally { remove(repository); }
        }
    }

    @Test public void b_bearerQueryAndActualCustomFieldMapping() throws Exception {
        try (var fixture = new JiraRestFixture(JiraRestFixture.Auth.BEARER, 0)) {
            var repository = repository(fixture, JiraRestFixture.Auth.BEARER, JiraRestFixture.TOKEN);
            repository.setProperty(MylynMetadata.ATTR_GROUPING, "customfield_10001");
            try {
                query(repository);
                var task = importTask(repository, fetch(repository, "1042"));
                assertEquals("Platform team", MylynMetadata.getMylynProjectName(task));
                for (var state : List.of("missing", "null", "blank")) {
                    fixture.custom = state;
                    var data = fetch(repository, "1042");
                    assertEquals("Absent value: " + state, "None", MylynMetadata.getMylynProjectName(task, repository, data));
                }
                int expectedErrors = errors.size();
                assertThrows(CoreException.class, () -> fetch(repository, "9999"));
                acceptExpectedHttpErrors(expectedErrors, "404");
                assertTrue("Unexpected Jira/Timekeeper errors: " + errors, errors.isEmpty());
                assertTrue(fixture.requests().stream().anyMatch(line -> line.contains("/issue/9999") && line.contains(" -> 404")));
                assertEquals(0, fixture.rejectedCredentials);
                assertTrue("Unknown REST requests: " + fixture.unexpected(), fixture.unexpected().isEmpty());
            } finally { remove(repository); }
        }
    }

    @Test public void c_invalidCredentialsAreRejectedByRealConnector() throws Exception {
        try (var fixture = new JiraRestFixture(JiraRestFixture.Auth.BASIC, 0)) {
            var repository = repository(fixture, JiraRestFixture.Auth.BASIC, "invalid-fixture-token");
            int taskCount = service().tasks().size();
            try {
                try { fetch(repository, "1042"); fail("Invalid credentials unexpectedly succeeded"); }
                catch (CoreException | RuntimeException expected) {
                    assertTrue("The real client must have attempted authentication", fixture.rejectedCredentials > 0);
                    assertTrue(fixture.requests().stream().anyMatch(line -> line.contains(" -> 401")));
                }
                acceptExpectedHttpErrors(0, "401");
                assertTrue("Unexpected Jira/Timekeeper errors: " + errors, errors.isEmpty());
                assertEquals(taskCount, service().tasks().size());
                assertTrue("Unknown REST requests: " + fixture.unexpected(), fixture.unexpected().isEmpty());
            } finally { remove(repository); }
        }
    }
    @Test public void d_installedBundlesAndSingleLoggingProvider() throws Exception {
        var context = org.osgi.framework.FrameworkUtil.getBundle(connector().getClass()).getBundleContext();
        assertEquals(4, Platform.getBundle("org.eclipse.platform").getVersion().getMajor());
        assertEquals(41, Platform.getBundle("org.eclipse.platform").getVersion().getMinor());
        assertEquals(4, Platform.getBundle("org.eclipse.mylyn.tasks.core").getVersion().getMajor());
        assertEquals(12, Platform.getBundle("org.eclipse.mylyn.tasks.core").getVersion().getMinor());
        assertEquals("Use the Java 21 acceptance baseline", 21, Runtime.version().feature());
        var versions = new ArrayList<String>();
        var providers = new ArrayList<String>();
        for (var bundle : context.getBundles()) {
            if (bundle.getSymbolicName().startsWith("me.glindholm.connector.jira")) {
                assertNotEquals("Unresolved Jira bundle: " + bundle, org.osgi.framework.Bundle.INSTALLED, bundle.getState());
                assertEquals(VERSION, bundle.getVersion().toString());
            }
            if (bundle.getEntry("META-INF/services/org.slf4j.spi.SLF4JServiceProvider") != null)
                providers.add(bundle.getSymbolicName());
            if (bundle.getSymbolicName().startsWith("me.glindholm") || bundle.getSymbolicName().startsWith("org.eclipse.mylyn")
                    || bundle.getSymbolicName().equals("org.eclipse.platform"))
                versions.add(bundle.getSymbolicName() + "=" + bundle.getVersion());
        }
        assertEquals("Exactly one SLF4J provider: " + providers, 1, providers.size());
        versions.add("java=" + System.getProperty("java.version"));
        versions.add("logging=" + providers);
        versions.add("jira.repository=https://gnl42.github.io/JiraConnector-update/releases/6.0.0.202605160149/");
        Files.write(proof().getParent().resolve("installed-versions.txt"), versions);
    }

    private void acceptExpectedHttpErrors(int first, String code) throws Exception {
        var snapshot = List.copyOf(errors);
        var expected = snapshot.subList(first, snapshot.size());
        Files.writeString(proof().getParent().resolve("expected-http-errors.txt"),
                "Verified HTTP " + code + " response; matching logged statuses: " + expected.size() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        for (var status : expected) {
            var detail = new java.io.StringWriter();
            detail.append(status.toString());
            if (status.getException() != null) status.getException().printStackTrace(new java.io.PrintWriter(detail));
            assertTrue("Unexpected error during HTTP " + code + ": " + detail,
                    status.getPlugin().startsWith("me.glindholm") && detail.toString().contains(code));
            Files.writeString(proof().getParent().resolve("expected-http-errors.txt"), "Expected HTTP " + code + ": " + detail + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        errors.removeAll(expected);
    }

}
