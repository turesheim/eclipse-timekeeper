package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.time.Instant;
import java.util.*;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.RepositoryQuery;
import org.eclipse.mylyn.internal.tasks.core.UnmatchedTaskContainer;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.Queries.ActivityQuery;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Installed Task List activation must group provider tasks in the real server. */
@SuppressWarnings("restriction")
final class RemoteQueryProjectScenario {
    private static final String REPOSITORY="https://github.com/timekeeper-fixture/projects";
    private static final class GithubTask extends AbstractTask {
        GithubTask(String id) {super(REPOSITORY,id,"Query issue "+id);}
        @Override public boolean isLocal() {return false;}
        @Override public String getConnectorKind() {return "github";}
    }
    static void run(HttpTimekeeperService api) throws Exception {
        var before=api.metadata();assertTrue(before.active().isEmpty());
        var first=new GithubTask(Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits()));
        var second=new GithubTask(Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits()));
        String prefix="Query project "+UUID.randomUUID().toString().substring(0,8);
        var one=query(prefix+" one");var two=query(prefix+" two");
        var reference=new ExternalTaskReference("github",REPOSITORY,first.getTaskId(),Optional.empty());
        var secondReference=new ExternalTaskReference("github",REPOSITORY,second.getTaskId(),Optional.empty());
        var fallback=api.createProject(new CreateProject("Uncategorized"));
        var legacy=api.createTask(new CreateTask(Optional.of(fallback.id()),Optional.empty(),first.getSummary(),Optional.empty(),Set.of(reference)));
        var start=Instant.now().minusSeconds(600);
        var history=api.createActivity(new CreateActivity(legacy.id(),OwnerId.LOCAL,start,Optional.of(start.plusSeconds(300)),prefix+" history",Set.of()));
        RemoteTestSupport.onUi(()->{
            var list=TasksUiPlugin.getTaskList();
            list.addUnmatchedContainer(new UnmatchedTaskContainer("github",REPOSITORY));
            list.addQuery(one);list.addQuery(two);list.addTask(first,one);list.addTask(second,two);
            TasksUi.getTaskActivityManager().activateTask(first);return null;
        });
        try {
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(value->value.taskId().equals(legacy.id())).isPresent(),"Query activation did not start the existing remote task");
            var imported=api.task(legacy.id()).orElseThrow();
            assertEquals(one.getSummary(),api.project(imported.projectId().orElseThrow()).orElseThrow().name());
            assertEquals(history,api.activity(history.id()).orElseThrow());
            RemoteTestSupport.waitFor(()->{try {return new SWTWorkbenchBot().viewByTitle("Workweek").bot().treeWithId("workweek-editor-tree").getTreeItem(one.getSummary())!=null;}catch(RuntimeException pending) {return false;}},"Query project did not appear in installed Workweek");
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().activateTask(second);return null;});
            RemoteTestSupport.waitFor(()->api.findTask(secondReference.key()).filter(task->api.activeActivity(OwnerId.LOCAL).filter(value->value.taskId().equals(task.id())).isPresent()).isPresent(),"Second query activation did not create and track its remote task");
            var other=api.findTask(secondReference.key()).orElseThrow();
            assertEquals(two.getSummary(),api.project(other.projectId().orElseThrow()).orElseThrow().name());
            assertNotEquals(imported.projectId(),other.projectId());
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().deactivateActiveTask();return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).isEmpty(),"Query activation did not stop");
            var current=api.task(legacy.id()).orElseThrow();
            var assigned=api.updateTask(new UpdateTask(current.id(),current.version(),other.projectId(),current.parentTaskId(),current.summary(),current.url()));
            RemoteTestSupport.onUi(()->{TasksUi.getTaskActivityManager().activateTask(first);return null;});
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).filter(value->value.taskId().equals(legacy.id())).isPresent(),"Repeated query activation did not reuse task identity");
            assertEquals(assigned.projectId(),api.task(legacy.id()).orElseThrow().projectId());
            assertEquals(1,api.tasks().stream().filter(task->task.externalReferences().contains(reference)).count());
            assertEquals(history,api.activity(history.id()).orElseThrow());
        } finally {
            RemoteTestSupport.onUi(()->{
                TasksUi.getTaskActivityManager().deactivateActiveTask();
                var list=TasksUiPlugin.getTaskList();list.deleteTask(first);list.deleteTask(second);list.deleteQuery(one);list.deleteQuery(two);return null;
            });
            RemoteTestSupport.waitFor(()->api.activeActivity(OwnerId.LOCAL).isEmpty(),"Query cleanup left tracking active");
            for(var task:api.tasks()) if(before.tasks().stream().noneMatch(old->old.id().equals(task.id()))) {
                var query=new ActivityQuery(Instant.EPOCH,Instant.now().plusSeconds(60),Optional.empty(),Optional.of(task.id()),Optional.empty(),api.profile().zone());
                for(var activity:api.activities(query).activities()) api.deleteActivity(new DeleteActivity(activity.id(),activity.version()));
                var current=api.task(task.id()).orElseThrow();api.deleteTask(new DeleteTask(current.id(),current.version()));
            }
            for(var project:api.projects()) if(before.projects().stream().noneMatch(old->old.id().equals(project.id())))
                api.deleteProject(new DeleteProject(project.id(),project.version()));
            RemoteTestSupport.waitFor(()->{try {return RemoteTestSupport.onUi(()->TimekeeperUiPlugin.getDefault().isBackendReady() && TimekeeperUiPlugin.getDefault().getTimekeeperService().tasks().equals(before.tasks()) && TimekeeperUiPlugin.getDefault().getTimekeeperService().projects().equals(before.projects()));}catch(Exception pending) {return false;}},"Query cleanup did not reach installed client");
        }
        RemoteTestSupport.closedLocalStorage();
        RemoteBootstrap.receipt().setProperty("phase.mylyn.query-projects","passed");RemoteBootstrap.writeProof();
    }
    private static RepositoryQuery query(String name) {
        var query=new RepositoryQuery("github",UUID.randomUUID().toString());query.setSummary(name);query.setRepositoryUrl(REPOSITORY);return query;
    }
}
