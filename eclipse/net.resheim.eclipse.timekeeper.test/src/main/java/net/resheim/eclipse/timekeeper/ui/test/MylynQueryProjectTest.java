package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;

import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.RepositoryQuery;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.internal.tasks.core.UnmatchedTaskContainer;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.mylyn.MylynMetadata;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.ui.PlatformUI;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;

/** Actual Task List membership, including queries and nested provider tasks. */
@SuppressWarnings("restriction")
public class MylynQueryProjectTest {
    private static final String REPOSITORY="https://github.com/example/tasks";
    private static final class GithubTask extends AbstractTask {
        GithubTask(String id) {super(REPOSITORY,id,"Issue "+id);}
        @Override public boolean isLocal() {return false;}
        @Override public String getConnectorKind() {return "github";}
    }
    private static RepositoryQuery query(TaskList list,String name) {
        var query=new RepositoryQuery("github",UUID.randomUUID().toString());query.setSummary(name);
        query.setRepositoryUrl(REPOSITORY);list.addQuery(query);return query;
    }
    @Test public void queriesSeparateProjectsWithinOneRepositoryAndPropagateToSubtasks() {
        var list=new TaskList();list.addUnmatchedContainer(new UnmatchedTaskContainer("github",REPOSITORY));
        var first=new GithubTask("1");var second=new GithubTask("2");var child=new GithubTask("3");
        list.addTask(first,query(list,"Timekeeper"));list.addTask(second,query(list,"Other project"));
        list.addTask(child,first);
        assertEquals("Timekeeper",MylynMetadata.getMylynProjectName(first,null,null));
        assertEquals("Other project",MylynMetadata.getMylynProjectName(second,null,null));
        assertEquals("Timekeeper",MylynMetadata.getMylynProjectName(child,null,null));
    }
    @Test public void categoriesTakePriorityAndMultipleQueriesHaveStableFallback() {
        var list=new TaskList();list.addUnmatchedContainer(new UnmatchedTaskContainer("github",REPOSITORY));
        var task=new GithubTask("1");list.addTask(task);
        assertEquals("Uncategorized",MylynMetadata.getMylynProjectName(task,null,null));
        list.addTask(task,query(list,"Zulu"));list.addTask(task,query(list,"Alpha"));
        assertEquals("Alpha",MylynMetadata.getMylynProjectName(task,null,null));
        var category=new TaskCategory("explicit-category","Chosen project");list.addCategory(category);list.addTask(task,category);
        assertEquals("Chosen project",MylynMetadata.getMylynProjectName(task,null,null));
    }
    @Test public void activationUsesQueryProjectsAndKeepsExistingIdentityHistoryAndExplicitAssignments() throws Exception {
        new SWTWorkbenchBot().waitUntil(new DefaultCondition() {
            @Override public boolean test() {return TimekeeperPlugin.getDefault().isReady() && MylynIntegration.getDefault()!=null;}
            @Override public String getFailureMessage() {return "Embedded backend and Mylyn adapter did not start";}
        });
        var execution=new FutureTask<Void>(() -> {
            var service=TimekeeperUiPlugin.getDefault().getTimekeeperService();
            var previousProjects=service.projects().stream().map(Project::id).collect(java.util.stream.Collectors.toSet());
            var previousTasks=service.tasks().stream().map(Task::id).collect(java.util.stream.Collectors.toSet());
            var list=TasksUiPlugin.getTaskList();
            var first=new GithubTask(Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits()));
            var second=new GithubTask(Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits()));
            var one=query(list,"Query project one "+UUID.randomUUID());var two=query(list,"Query project two "+UUID.randomUUID());
            if(list.getUnmatchedContainers().stream().noneMatch(container -> container.getRepositoryUrl().equals(REPOSITORY)))
                list.addUnmatchedContainer(new UnmatchedTaskContainer("github",REPOSITORY));
            list.addTask(first,one);list.addTask(second,two);
            try {
                var fallback=service.projects().stream().filter(project -> project.name().equals("Uncategorized")).findFirst()
                    .orElseGet(() -> service.createProject(new CreateProject("Uncategorized")));
                var reference=new ExternalTaskReference("github",REPOSITORY,first.getTaskId(),Optional.empty());
                var legacy=service.createTask(new CreateTask(Optional.of(fallback.id()),Optional.empty(),first.getSummary(),Optional.empty(),Set.of(reference)));
                var start=Instant.now().minusSeconds(600);
                var history=service.createActivity(new CreateActivity(legacy.id(),OwnerId.LOCAL,start,Optional.of(start.plusSeconds(300)),"Keep original history",Set.of()));
                TasksUi.getTaskActivityManager().activateTask(first);
                var imported=service.findTask(reference.key()).orElseThrow();
                assertEquals(legacy.id(),imported.id());
                assertEquals(one.getSummary(),service.project(imported.projectId().orElseThrow()).orElseThrow().name());
                assertEquals(history,service.activity(history.id()).orElseThrow());
                assertEquals(imported.id(),service.activeActivity(OwnerId.LOCAL).orElseThrow().taskId());
                TasksUi.getTaskActivityManager().activateTask(second);
                var secondReference=new ExternalTaskReference("github",REPOSITORY,second.getTaskId(),Optional.empty());
                var other=service.findTask(secondReference.key()).orElseThrow();
                assertEquals(two.getSummary(),service.project(other.projectId().orElseThrow()).orElseThrow().name());
                assertNotEquals(imported.projectId(),other.projectId());
                TasksUi.getTaskActivityManager().deactivateActiveTask();
                assertTrue(service.activeActivity(OwnerId.LOCAL).isEmpty());
                var count=service.tasks().size();
                // An explicit reassignment must survive later query activation.
                var current=service.task(imported.id()).orElseThrow();
                var assigned=service.updateTask(new UpdateTask(current.id(),current.version(),other.projectId(),Optional.empty(),current.summary(),current.url()));
                TasksUi.getTaskActivityManager().activateTask(first);
                var retained=service.task(legacy.id()).orElseThrow();
                assertEquals(assigned.id(),retained.id());assertEquals(assigned.projectId(),retained.projectId());
                assertEquals(assigned.externalReferences(),retained.externalReferences());
                assertEquals(count,service.tasks().size());
                assertEquals(history,service.activity(history.id()).orElseThrow());
            } finally {
                TasksUi.getTaskActivityManager().deactivateActiveTask();
                list.deleteTask(first);list.deleteTask(second);list.deleteQuery(one);list.deleteQuery(two);
                for(var task:service.tasks()) {
                    if(previousTasks.contains(task.id())) continue;
                    var query=new ActivityQuery(Instant.EPOCH,Instant.now().plusSeconds(60),Optional.empty(),Optional.of(task.id()),Optional.empty(),TimekeeperUiPlugin.getCalendarZone());
                    for(var activity:service.activities(query).activities()) service.deleteActivity(new DeleteActivity(activity.id(),activity.version()));
                    var current=service.task(task.id()).orElseThrow();service.deleteTask(new DeleteTask(current.id(),current.version()));
                }
                for(var project:service.projects()) if(!previousProjects.contains(project.id()))
                    service.deleteProject(new DeleteProject(project.id(),project.version()));
            }
            return null;
        });
        PlatformUI.getWorkbench().getDisplay().asyncExec(execution);execution.get(30,TimeUnit.SECONDS);
    }
}
