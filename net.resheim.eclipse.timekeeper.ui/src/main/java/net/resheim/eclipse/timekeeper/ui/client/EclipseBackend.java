package net.resheim.eclipse.timekeeper.ui.client;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import net.resheim.eclipse.timekeeper.client.*;
import net.resheim.eclipse.timekeeper.db.DatabaseChangeListener;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Queries.*;

/** Selected backend: UI reads immutable snapshots; remote work runs in cancellable Jobs. */
public final class EclipseBackend implements AutoCloseable {
    private final Supplier<TimekeeperService> embedded;
    private final HttpTimekeeperService remote;
    private final boolean remoteMode;
    private final String configurationFailure;
    private final Display display;
    private final TimekeeperService facade;
    private final Set<DatabaseChangeListener> listeners=new java.util.concurrent.CopyOnWriteArraySet<>();
    private final Map<ActivityQuery,ActivityReport> reports=new ConcurrentHashMap<>();
    private final Set<ActivityQuery> loading=ConcurrentHashMap.newKeySet();
    private final Set<Job> jobs=ConcurrentHashMap.newKeySet();
    private final org.eclipse.core.runtime.jobs.ISchedulingRule serial=new org.eclipse.core.runtime.jobs.ISchedulingRule() {
        public boolean contains(org.eclipse.core.runtime.jobs.ISchedulingRule other) {return other==this;}
        public boolean isConflicting(org.eclipse.core.runtime.jobs.ISchedulingRule other) {return other==this;}
    };
    private volatile HttpTimekeeperService.Metadata metadata;
    private volatile String status="Connecting to the Timekeeper server.";
    private volatile boolean ready,closed;
    public EclipseBackend(Supplier<TimekeeperService> embedded,HttpTimekeeperService remote,Display display) {
        this(embedded,remote,display,remote!=null,null);
    }
    public static EclipseBackend unavailableRemote(Supplier<TimekeeperService> embedded,Display display,String message) {
        return new EclipseBackend(embedded,null,display,true,message);
    }
    private EclipseBackend(Supplier<TimekeeperService> embedded,HttpTimekeeperService remote,Display display,boolean remoteMode,String configurationFailure) {
        this.embedded=embedded;this.remote=remote;this.display=display;
        this.remoteMode=remoteMode;this.configurationFailure=configurationFailure;
        if(configurationFailure!=null) status=configurationFailure;
        facade=!remoteMode ? null : (TimekeeperService)Proxy.newProxyInstance(TimekeeperService.class.getClassLoader(),new Class<?>[]{TimekeeperService.class},this::invoke);
        if(remote!=null) refresh();
    }
    public boolean remote() {return remoteMode;}
    public boolean ready() {return !remoteMode ? TimekeeperPlugin.getDefault().isReady() : ready;}
    public boolean snapshotsAvailable() {return !remoteMode ? ready() : metadata!=null;}
    public String status() {return !remoteMode ? TimekeeperPlugin.getDefault().getDatabaseStatus().getMessage() : status;}
    public ZoneId zone() {return metadata==null ? ZoneId.systemDefault() : metadata.profile().zone();}
    public String identity() {return !remoteMode ? "embedded" : remote==null ? "remote:unavailable" : "remote:"+remote.endpoint()+":"+(metadata==null ? "connecting" : metadata.profile().owner().value());}
    public TimekeeperService service() {return !remoteMode ? embedded.get() : facade;}
    public int exportCsv(java.nio.file.Path folder) throws java.io.IOException {
        if(Display.getCurrent()!=null || remote==null) throw new IllegalStateException("Remote transfer requires an available background client");
        return remote.exportCsv(folder);
    }
    public int importCsv(java.nio.file.Path folder,java.nio.file.Path journalFolder) throws java.io.IOException {
        if(Display.getCurrent()!=null || remote==null) throw new IllegalStateException("Remote transfer requires an available background client");
        return remote.importCsv(folder,journalFolder);
    }
    public void addListener(DatabaseChangeListener listener) {listeners.add(listener);if(!remoteMode) TimekeeperPlugin.getDefault().addListener(listener);}
    public void removeListener(DatabaseChangeListener listener) {listeners.remove(listener);if(!remoteMode) TimekeeperPlugin.getDefault().removeListener(listener);}
    private void changed() {for(var listener:listeners) listener.databaseStateChanged();}
    private Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
        if(method.getDeclaringClass()==Object.class) return switch(method.getName()) {case "toString"->"Timekeeper remote snapshot client";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;};
        if(Display.getCurrent()==null) {
            if(remote==null) throw new IllegalStateException(configurationFailure);
            try {return method.invoke(remote,args);}catch(InvocationTargetException e) {throw e.getCause();}
        }
        if(metadata==null) throw new RemoteFailure(RemoteFailure.Kind.UNAVAILABLE,"CONNECTING");
        String name=method.getName();
        return switch(name) {
            case "projects" -> metadata.projects();case "tasks" -> metadata.tasks();case "labels" -> metadata.labels();
            case "project" -> metadata.projects().stream().filter(value -> value.id().equals(args[0])).findFirst();
            case "task" -> metadata.tasks().stream().filter(value -> value.id().equals(args[0])).findFirst();
            case "label" -> metadata.labels().stream().filter(value -> value.id().equals(args[0])).findFirst();
            case "activeActivity" -> metadata.active();
            case "activity" -> java.util.stream.Stream.concat(metadata.active().stream(),reports.values().stream().flatMap(report -> report.activities().stream())).filter(value -> value.id().equals(args[0])).findFirst();
            case "findTask" -> metadata.tasks().stream().filter(value -> value.externalReferences().stream().anyMatch(ref -> ref.key().equals(args[0]))).findFirst();
            case "activities" -> cachedReport((ActivityQuery)args[0]);
            default -> throw new IllegalStateException("Remote changes must run through a Timekeeper background operation");
        };
    }
    private ActivityReport cachedReport(ActivityQuery query) {
        ActivityReport report=reports.get(query);if(report==null && ready && loading.add(query)) {
            read("Load Timekeeper week",()->{var loaded=remote.activities(query);reports.put(query,loaded);return loaded;},value->{loading.remove(query);changed();},error->{loading.remove(query);});
        }
        return report==null ? new ActivityReport(query,Instant.now(),List.of(),Duration.ZERO) : report;
    }
    private void reload() {
        var next=remote.metadata();
        if(metadata!=null && !metadata.profile().owner().equals(next.profile().owner())) throw new RemoteFailure(RemoteFailure.Kind.AUTHENTICATION,"OWNER_CHANGED");
        Map<ActivityQuery,ActivityReport> nextReports=new HashMap<>();for(var query:reports.keySet()) nextReports.put(query,remote.activities(query));
        metadata=next;reports.putAll(nextReports);ready=true;status="";
    }
    public Activity activateInWorker(TaskId task) {
        if(Display.getCurrent()!=null) throw new IllegalStateException("Activation requires a background operation");
        var observed=metadata==null ? Optional.<Activity>empty() : metadata.active();return remote.activate(task,"",Set.of(),observed);
    }
    public void activate(TaskId task,Optional<Activity> observed) {
        execute("Activate Timekeeper task",()->remote.activate(task,"",Set.of(),observed),value->{},failure->org.eclipse.jface.dialogs.MessageDialog.openError(display.getActiveShell(),"Could not activate task",failure.getMessage()));
    }
    public void refresh() {
        if(remote==null) {changed();return;}
        read("Connect to Timekeeper server",()->{reload();return null;},value->changed(),error->changed());
    }
    public <T> Job execute(String name,Supplier<T> work,Consumer<T> success,Consumer<RuntimeException> failure) {
        return execute(name,work,success,failure,true);
    }
    public <T> Job read(String name,Supplier<T> work,Consumer<T> success,Consumer<RuntimeException> failure) {
        return execute(name,work,success,failure,false);
    }
    private <T> Job execute(String name,Supplier<T> work,Consumer<T> success,Consumer<RuntimeException> failure,boolean refreshAfterSuccess) {
        if(!remoteMode) {try {T value=work.get();success.accept(value);}catch(RuntimeException e) {failure.accept(e);}return null;}
        if(remote==null) {failure.accept(new IllegalStateException(configurationFailure));return null;}
        Job job=new Job(name) {
            private volatile Thread worker;
            @Override protected IStatus run(IProgressMonitor monitor) {
                worker=Thread.currentThread();
                try {
                    if(closed || monitor.isCanceled()) return Status.CANCEL_STATUS;
                    T value=work.get();
                    if(refreshAfterSuccess) {
                        try {reload();} catch(RuntimeException refreshFailure) {
                            // The command was acknowledged. Do not present it as a failed save
                            // or repeat it merely because fetching the next snapshot failed.
                            ready=false;status="The change was saved. "+refreshFailure.getMessage();
                        }
                    }
                    ui(()->{if(!monitor.isCanceled() && !closed) {success.accept(value);changed();}});return Status.OK_STATUS;
                } catch(RuntimeException error) {
                    if(error instanceof RemoteFailure problem && Set.of(RemoteFailure.Kind.AUTHENTICATION,RemoteFailure.Kind.OFFLINE,RemoteFailure.Kind.TIMEOUT,RemoteFailure.Kind.UNAVAILABLE,RemoteFailure.Kind.CANCELLED).contains(problem.kind())) ready=false;
                    status=error.getMessage()==null ? "The operation could not be completed." : error.getMessage();
                    ui(()->{if(!closed) {failure.accept(error);changed();}});return Status.CANCEL_STATUS;
                } finally {worker=null;jobs.remove(this);Thread.interrupted();}
            }
            @Override protected void canceling() {Thread thread=worker;if(thread!=null) thread.interrupt();}
        };
        jobs.add(job);job.setRule(serial);job.setUser(true);job.schedule();return job;
    }
    private void ui(Runnable runnable) {if(!display.isDisposed()) display.asyncExec(runnable);}
    @Override public void close() {closed=true;jobs.forEach(Job::cancel);if(remote!=null) remote.close();listeners.clear();}
}
