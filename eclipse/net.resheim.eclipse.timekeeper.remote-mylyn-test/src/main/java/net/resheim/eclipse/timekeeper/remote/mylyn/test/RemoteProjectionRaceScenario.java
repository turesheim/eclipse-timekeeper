package net.resheim.eclipse.timekeeper.remote.mylyn.test;

import static org.junit.Assert.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.mylyn.MylynBackendScope;
import net.resheim.eclipse.timekeeper.mylyn.MylynIntegration;
import net.resheim.eclipse.timekeeper.mylyn.MylynTaskSynchronizer;
import net.resheim.eclipse.timekeeper.remote.test.RemoteTestSupport;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;

/** Hold a real background refresh across a real Task List edit, without sleeps. */
@SuppressWarnings("restriction")
final class RemoteProjectionRaceScenario {
    static void run(HttpTimekeeperService api) throws Exception {
        var plugin=TimekeeperUiPlugin.getDefault();
        var original=MylynIntegration.getDefault().synchronizer();
        var project=api.createProject(new CreateProject("Projection race fixture"));
        var task=api.createTask(new CreateTask(Optional.of(project.id()),"Before background refresh",Optional.empty()));
        RemoteTestSupport.onUi(()->{plugin.refreshBackend();return null;});
        RemoteTestSupport.waitFor(()->plugin.getTimekeeperService().task(task.id()).isPresent(),"Race fixture did not reach the remote snapshot");
        var delegate=plugin.getTimekeeperService();
        var armed=new AtomicBoolean();var captured=new CountDownLatch(1);var release=new CountDownLatch(1);
        var service=(TimekeeperService)Proxy.newProxyInstance(delegate.getClass().getClassLoader(),new Class<?>[]{TimekeeperService.class},(proxy,method,args)->{
            try {
                // Keep this isolated Task List independent of the account's other records.
                if(method.getName().equals("tasks") && method.getParameterCount()==0) {
                    List<Task> snapshot=delegate.task(task.id()).stream().toList();
                    if(Display.getCurrent()==null && armed.compareAndSet(true,false)) {
                        captured.countDown();
                        if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("Background projection gate was not released");
                    }
                    return snapshot;
                }
                if(method.getName().equals("projects") && method.getParameterCount()==0)return delegate.project(project.id()).stream().toList();
                return method.invoke(delegate,args);
            } catch(InvocationTargetException failure) {throw failure.getCause();}
        });
        var list=new TaskList();var scope=new MylynBackendScope("remote:projection-race-"+UUID.randomUUID(),false);
        var probe=new MylynTaskSynchronizer(service,PlatformUI.getWorkbench().getDisplay(),()->scope,list);
        boolean previous=plugin.getPreferenceStore().getBoolean(PreferenceConstants.PROJECT_NATIVE_TASKS);
        try {
            RemoteTestSupport.onUi(()->{original.stop();plugin.getPreferenceStore().setValue(PreferenceConstants.PROJECT_NATIVE_TASKS,true);probe.start();return null;});
            RemoteTestSupport.waitFor(()->projection(list,scope,task)!=null,"Race task was not projected");
            var projected=projection(list,scope,task);
            String versionKey="timekeeper.projection-version."+scope.scope();
            RemoteTestSupport.waitFor(()->String.valueOf(task.version()).equals(projected.getAttribute(versionKey)),"Race projection did not establish its baseline");
            armed.set(true);
            RemoteTestSupport.onUi(()->{probe.propertyChange(new PropertyChangeEvent(probe,PreferenceConstants.PROJECT_NATIVE_TASKS,false,true));return null;});
            assertTrue("Background refresh did not reach its controlled read",captured.await(30,TimeUnit.SECONDS));
            String summary="Task List edit during background refresh",url="https://example.invalid/preserved-projection-edit";
            RemoteTestSupport.onUi(()->{projected.setSummary(summary);projected.setUrl(url);list.notifyElementChanged(projected);return null;});
            assertEquals(summary,projected.getSummary());release.countDown();
            RemoteTestSupport.waitFor(()->api.task(task.id()).filter(value->value.summary().equals(summary)).isPresent(),"Background refresh discarded a pending Task List edit");
            var saved=api.task(task.id()).orElseThrow();assertEquals(task.version()+1,saved.version());assertEquals(Optional.of(url),saved.url());
            RemoteTestSupport.waitFor(()->String.valueOf(saved.version()).equals(projected.getAttribute(versionKey)),"Preserved edit did not acknowledge its new version");
            assertEquals(summary,projected.getSummary());
            net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap.receipt().setProperty("phase.mylyn.projection-race","passed");
            net.resheim.eclipse.timekeeper.remote.test.RemoteBootstrap.writeProof();
        } finally {
            release.countDown();
            RemoteTestSupport.onUi(()->{probe.stop();plugin.getPreferenceStore().setValue(PreferenceConstants.PROJECT_NATIVE_TASKS,previous);return null;});
            api.task(task.id()).ifPresent(value->api.deleteTask(new DeleteTask(value.id(),value.version())));
            api.project(project.id()).ifPresent(value->api.deleteProject(new DeleteProject(value.id(),value.version())));
            RemoteTestSupport.onUi(()->{original.start();plugin.refreshBackend();return null;});
        }
    }
    private static AbstractTask projection(TaskList list,MylynBackendScope scope,Task task) {
        return list.getAllTasks().stream().filter(value->scope.task(value).filter(task.id()::equals).isPresent()).findFirst().orElse(null);
    }
}
