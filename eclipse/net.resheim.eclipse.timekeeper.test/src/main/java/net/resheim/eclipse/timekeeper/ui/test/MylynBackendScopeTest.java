package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.persistence.EntityManager;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;

import net.resheim.eclipse.timekeeper.db.DatabaseStartup;
import net.resheim.eclipse.timekeeper.db.adapter.JpaServicePorts;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.mylyn.MylynBackendScope;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.mylyn.MylynTaskSynchronizer;
import net.resheim.eclipse.timekeeper.service.DefaultTimekeeperService;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;

/** Real Task List synchronization against independent databases, with owner/backend switching. */
@SuppressWarnings("restriction")
public class MylynBackendScopeTest {
    @Test public void projectRenameDoesNotMakeTheFollowingTaskEditStale() throws Exception {
        var bot=new SWTWorkbenchBot();
        await(bot,()->TimekeeperUiPlugin.getDefault()!=null && TimekeeperUiPlugin.getDefault().isBackendReady(),"Backend not ready");
        String nonce=UUID.randomUUID().toString();
        EntityManager manager=DatabaseStartup.open("jdbc:h2:mem:projection_version_"+nonce);
        var service=new DefaultTimekeeperService(new JpaServicePorts(()->manager).ports(event->{}));
        var project=service.createProject(new CreateProject("Original project"));
        var task=service.createTask(new CreateTask(Optional.of(project.id()),"Original task",Optional.empty()));
        var scope=new MylynBackendScope("embedded:projection-version-"+nonce,false);
        var list=new TaskList();var display=PlatformUI.getWorkbench().getDisplay();
        var sync=new MylynTaskSynchronizer(service,display,()->scope,list);
        var original=MylynIntegration.getDefault().synchronizer();
        var warnings=new java.util.concurrent.CopyOnWriteArrayList<org.eclipse.core.runtime.IStatus>();
        org.eclipse.core.runtime.ILogListener listener=(status,bundle)->{
            if(status.getPlugin().equals(MylynIntegration.PLUGIN_ID) && status.getMessage().contains("Could not synchronize Mylyn")) warnings.add(status);
        };
        boolean[] previous=new boolean[1];
        try {
            onUi(()->{
                original.stop();
                var preferences=TimekeeperUiPlugin.getDefault().getPreferenceStore();
                previous[0]=preferences.getBoolean(PreferenceConstants.PROJECT_NATIVE_TASKS);
                preferences.setValue(PreferenceConstants.PROJECT_NATIVE_TASKS,true);sync.start();
            });
            await(bot,()->projection(list,scope,task).isPresent(),"Task was not projected");
            var projected=projection(list,scope,task).orElseThrow();
            var category=list.getContainerForHandle(scope.category(project.id()));
            onUi(()->{
                list.renameContainer(category,"Renamed project");
                projected.setSummary("Renamed task");list.notifyElementChanged(projected);
            });
            await(bot,()->service.project(project.id()).orElseThrow().name().equals("Renamed project")
                    && service.task(task.id()).orElseThrow().summary().equals("Renamed task"),
                    "Project rename left the following Task List edit on a stale task version");
            assertEquals(1,service.projects().size());assertEquals(1,service.tasks().size());
            assertEquals(Optional.of(task.id()),scope.task(projected));
            // A further edit must use the acknowledged version, not the original baseline.
            onUi(()->{projected.setSummary("Edited again");list.notifyElementChanged(projected);});
            await(bot,()->service.task(task.id()).orElseThrow().summary().equals("Edited again"),"A subsequent task edit remained stale");
            // Real competing changes must still reject the captured projection version.
            onUi(sync::stop);
            var prior=service.task(task.id()).orElseThrow();
            var competing=service.updateTask(new net.resheim.eclipse.timekeeper.service.Commands.UpdateTask(
                    prior.id(),prior.version(),prior.projectId(),prior.parentTaskId(),"Independent backend edit",prior.url()));
            org.eclipse.core.runtime.Platform.addLogListener(listener);
            onUi(()->{projected.setSummary("Competing Mylyn draft");list.notifyElementChanged(projected);sync.start();});
            await(bot,()->!warnings.isEmpty(),"A real competing task edit did not report a conflict");
            assertEquals(competing,service.task(task.id()).orElseThrow());
            assertEquals("Competing Mylyn draft",projected.getSummary());
            assertTrue(warnings.get(0).getException().getCause() instanceof net.resheim.eclipse.timekeeper.service.ServiceException);
        } finally {
            onUi(()->{
                sync.stop();
                TimekeeperUiPlugin.getDefault().getPreferenceStore().setValue(PreferenceConstants.PROJECT_NATIVE_TASKS,previous[0]);original.start();
            });
            org.eclipse.core.runtime.Platform.removeLogListener(listener);
            var factory=manager.getEntityManagerFactory();manager.close();factory.close();
        }
    }

    @Test public void ownerAndServerSwitchingDoesNotCopyOrModifyForeignProjections() throws Exception {
        var bot=new SWTWorkbenchBot();
        bot.waitUntil(new DefaultCondition() {
            public boolean test() {return TimekeeperUiPlugin.getDefault()!=null && TimekeeperUiPlugin.getDefault().isBackendReady();}
            public String getFailureMessage() {return "Timekeeper backend did not become ready";}
        });
        String nonce=UUID.randomUUID().toString();
        EntityManager a=DatabaseStartup.open("jdbc:h2:mem:scope_a_"+nonce),b=DatabaseStartup.open("jdbc:h2:mem:scope_b_"+nonce);
        var serviceA=new DefaultTimekeeperService(new JpaServicePorts(()->a).ports(event->{}));
        var serviceB=new DefaultTimekeeperService(new JpaServicePorts(()->b).ports(event->{}));
        var projectA=serviceA.createProject(new CreateProject("Same category name"));
        var parentA=serviceA.createTask(new CreateTask(Optional.of(projectA.id()),"A parent",Optional.empty()));
        var childA=serviceA.createTask(new CreateTask(Optional.of(projectA.id()),Optional.of(parentA.id()),"A child",Optional.empty()));
        var projectB=serviceB.createProject(new CreateProject("Same category name"));
        var taskB=serviceB.createTask(new CreateTask(Optional.of(projectB.id()),"B task",Optional.empty()));
        var scopeA=new MylynBackendScope("remote:https://one.invalid/"+nonce+":alice",false);
        var scopeB=new MylynBackendScope("remote:https://one.invalid/"+nonce+":bob",false);
        var scopeC=new MylynBackendScope("remote:https://two.invalid/"+nonce+":bob",false);
        var list=new TaskList();var display=PlatformUI.getWorkbench().getDisplay();
        var syncA=new MylynTaskSynchronizer(serviceA,display,()->scopeA,list);
        var syncB=new MylynTaskSynchronizer(serviceB,display,()->scopeB,list);
        var syncC=new MylynTaskSynchronizer(serviceB,display,()->scopeC,list);
        var original=MylynIntegration.getDefault().synchronizer();
        boolean[] previous=new boolean[1];
        try {
            onUi(()->{
                original.stop();
                var preferences=TimekeeperUiPlugin.getDefault().getPreferenceStore();
                previous[0]=preferences.getBoolean(PreferenceConstants.PROJECT_NATIVE_TASKS);
                preferences.setValue(PreferenceConstants.PROJECT_NATIVE_TASKS,true);syncA.start();
            });
            await(bot,()->projection(list,scopeA,childA).isPresent(),"A hierarchy was not projected");
            var projectedA=projection(list,scopeA,childA).orElseThrow();
            assertTrue(projectedA.getParentContainers().contains(projection(list,scopeA,parentA).orElseThrow()));
            String categoryA=scopeA.category(projectA.id());
            onUi(()->{syncA.stop();syncB.start();});
            await(bot,()->projection(list,scopeB,taskB).isPresent(),"B task was not projected");
            var projectedB=projection(list,scopeB,taskB).orElseThrow();
            assertEquals(Optional.of(childA.id()),scopeA.task(projectedA));assertTrue(scopeB.task(projectedA).isEmpty());
            assertNotEquals(categoryA,scopeB.category(projectB.id()));
            assertEquals(2,serviceA.tasks().size());assertEquals(1,serviceB.tasks().size());
            assertEquals(1,serviceA.projects().size());assertEquals(1,serviceB.projects().size());
            int mainTasks=TimekeeperUiPlugin.getDefault().getTimekeeperService().tasks().size();
            onUi(()->assertThrows(IllegalStateException.class,()->MylynIntegration.getDefault().resolve(projectedA)));
            assertEquals(mainTasks,TimekeeperUiPlugin.getDefault().getTimekeeperService().tasks().size());
            onUi(()->{
                projectedA.setSummary("A draft while B selected");list.notifyElementChanged(projectedA);
                projectedB.setSummary("B edit while B selected");list.notifyElementChanged(projectedB);
            });
            await(bot,()->serviceB.task(taskB.id()).orElseThrow().summary().equals("B edit while B selected"),"B did not process the mixed batch of projection edits");
            var changedB=serviceB.task(taskB.id()).orElseThrow();
            assertEquals(2,list.getTaskCategories().size());
            assertEquals(childA,serviceA.task(childA.id()).orElseThrow());
            // A different server may contain identical UUIDs. Its projection must still be separate.
            onUi(()->{syncB.stop();syncC.start();});
            await(bot,()->projection(list,scopeC,taskB).isPresent(),"Second server task was not projected");
            var projectedC=projection(list,scopeC,taskB).orElseThrow();
            assertNotSame(projectedB,projectedC);assertTrue(scopeC.task(projectedB).isEmpty());
            assertNotEquals(scopeB.category(projectB.id()),scopeC.category(projectB.id()));
            assertEquals(changedB,serviceB.task(taskB.id()).orElseThrow());assertEquals(1,serviceB.projects().size());
            onUi(()->{syncC.stop();syncA.start();});
            await(bot,()->serviceA.task(childA.id()).orElseThrow().summary().equals("A draft while B selected"),"A draft was not applied after switching back");
            onUi(()->{
                list.deleteTask(projectedB);
                projectedA.setSummary("A final edit");list.notifyElementChanged(projectedA);
            });
            await(bot,()->serviceA.task(childA.id()).orElseThrow().summary().equals("A final edit"),"A did not process the mixed deletion/edit batch");
            assertEquals(changedB,serviceB.task(taskB.id()).orElseThrow());assertEquals(2,serviceA.tasks().size());
            var missingProject=new net.resheim.eclipse.timekeeper.domain.ProjectId(UUID.randomUUID());
            var missingTask=new net.resheim.eclipse.timekeeper.domain.TaskId(UUID.randomUUID());
            var staleCategory=new TaskCategory(scopeA.categoryHandle(missingProject),"Deleted backend project");
            var staleTask=new LocalTask(String.valueOf(list.getNextLocalTaskId()),"Deleted backend task");
            scopeA.remember(missingProject,staleCategory);scopeA.remember(staleTask,missingTask,true);
            // A late category projection must retain its UUID after deletion
            // removes the preference mapping; it must not import a new project.
            scopeA.forget(missingProject);
            onUi(()->{
                list.addCategory(staleCategory);list.addTask(staleTask,staleCategory);
                projectedA.setSummary("A edit after stale projections");list.notifyElementChanged(projectedA);
            });
            await(bot,()->serviceA.task(childA.id()).orElseThrow().summary().equals("A edit after stale projections")
                    && !list.getAllTasks().contains(staleTask) && !list.getTaskCategories().contains(staleCategory),"Stale projections were recreated or retained");
            assertEquals(2,serviceA.tasks().size());assertEquals(1,serviceA.projects().size());
        } finally {
            onUi(()->{
                syncA.stop();syncB.stop();syncC.stop();
                TimekeeperUiPlugin.getDefault().getPreferenceStore().setValue(PreferenceConstants.PROJECT_NATIVE_TASKS,previous[0]);original.start();
            });
            var factoryA=a.getEntityManagerFactory();var factoryB=b.getEntityManagerFactory();
            a.close();factoryA.close();b.close();factoryB.close();
        }
    }
    private static Optional<AbstractTask> projection(TaskList list,MylynBackendScope scope,Task task) {
        return list.getAllTasks().stream().filter(item->scope.task(item).filter(task.id()::equals).isPresent()).findFirst();
    }
    private static void await(SWTWorkbenchBot bot,java.util.function.BooleanSupplier condition,String failure) {
        bot.waitUntil(new DefaultCondition() {public boolean test() {return condition.getAsBoolean();}public String getFailureMessage() {return failure;}},10000);
    }
    private static void onUi(Runnable action) throws Exception {
        var task=new FutureTask<Void>(action,null);PlatformUI.getWorkbench().getDisplay().asyncExec(task);task.get(10,TimeUnit.SECONDS);
    }
}
