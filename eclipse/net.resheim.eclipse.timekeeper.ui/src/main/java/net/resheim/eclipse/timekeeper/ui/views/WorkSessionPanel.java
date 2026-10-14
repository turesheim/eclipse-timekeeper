package net.resheim.eclipse.timekeeper.ui.views;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.widgets.Label;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.ui.client.RemoteRecordSnapshots;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Always visible above Workweek; every database/network read runs in a Job. */
final class WorkSessionPanel {
    private record Snapshot(List<WorkSession> sessions,Optional<WorkSession> active,WorkSessionService.Report report) { }
    private final Composite group;
    private final Label status,totals,details,warning;
    private final Combo history;
    private final Table activities;
    private final Button start,stop,record,edit,delete,add,associate,detach;
    private Snapshot snapshot;
    private WorkSessionId selected;
    private boolean loading,busy;
    private Supplier<?> pending;
    private final DateTimeFormatter date=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
    WorkSessionPanel(Composite parent) {
        group=new Composite(parent,SWT.BORDER);
        group.setData("org.eclipse.swtbot.widget.key","work-session-panel");
        var compactLayout=new GridLayout(4,false);
        compactLayout.marginHeight=0;compactLayout.verticalSpacing=0;
        group.setLayout(compactLayout);
        group.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,3,1));
        status=new Label(group,SWT.NONE);
        status.setData("org.eclipse.swtbot.widget.key","work-session-status");
        var statusData=new GridData(SWT.FILL,SWT.CENTER,true,false);statusData.widthHint=180;status.setLayoutData(statusData);
        start=button(group,"Start work session",this::start);
        start.setData("org.eclipse.swtbot.widget.key","work-session-start");
        stop=button(group,"Stop work session",this::stop);
        var toggle=new Button(group,SWT.TOGGLE);
        toggle.setText("Session details");
        toggle.setData("org.eclipse.swtbot.widget.key","work-session-details");
        totals=new Label(group,SWT.NONE);
        totals.setData("org.eclipse.swtbot.widget.key","work-session-totals");
        totals.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,4,1));
        var body=new Composite(group,SWT.NONE);
        body.setLayout(new GridLayout(6,false));
        var bodyData=new GridData(SWT.FILL,SWT.TOP,true,false,4,1);
        bodyData.exclude=true;
        body.setLayoutData(bodyData);
        body.setVisible(false);
        toggle.addListener(SWT.Selection,event->{
            bodyData.exclude=!toggle.getSelection();body.setVisible(toggle.getSelection());
            group.getParent().layout(true,true);
        });
        new Label(body,SWT.NONE).setText("Session history");
        history=new Combo(body,SWT.READ_ONLY);
        history.setData("org.eclipse.swtbot.widget.key","work-session-history");
        var historyData=new GridData(SWT.FILL,SWT.CENTER,true,false,5,1);
        historyData.widthHint=320;
        history.setLayoutData(historyData);
        history.addListener(SWT.Selection,event->{
            if(snapshot!=null && history.getSelectionIndex()>=0) {
                selected=snapshot.sessions().get(history.getSelectionIndex()).id();refresh();
            }
        });
        record=button(body,"Record session",()->new WorkSessionDialog(group.getShell(),null,value->{selected=value.id();refresh();}).open());
        edit=button(body,"Edit session",()->{
            if(snapshot!=null && snapshot.report()!=null) new WorkSessionDialog(group.getShell(),snapshot.report().session()).open();
        });
        delete=button(body,"Delete session",this::delete);
        associate=button(body,"Associate activity",this::associate);
        add=button(body,"Add activity",()->{
            if(snapshot!=null && snapshot.report()!=null) new ActivityDialog(group.getShell(),ui().getTimekeeperService(),
                    snapshot.report().session(),LocalDate.now(TimekeeperUiPlugin.getCalendarZone())).open();
        });
        detach=button(body,"Remove association",this::detach);
        details=new Label(body,SWT.NONE);
        details.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,6,1));
        activities=new Table(body,SWT.BORDER|SWT.FULL_SELECTION|SWT.V_SCROLL|SWT.H_SCROLL);
        activities.setHeaderVisible(true);activities.setLinesVisible(true);
        activities.setData("org.eclipse.swtbot.widget.key","work-session-activities");
        var tableData=new GridData(SWT.FILL,SWT.FILL,true,false,6,1);
        tableData.heightHint=65;
        activities.setLayoutData(tableData);
        for(var column:List.of("Activity","Start","End")) {
            var item=new TableColumn(activities,SWT.NONE);item.setText(column);
            item.setWidth(column.equals("Activity") ? 240 : 150);
        }
        activities.addListener(SWT.Selection,event->enabled());
        activities.addListener(SWT.DefaultSelection,event->{
            var value=selectedActivity();
            if(value!=null) new ActivityDialog(group.getShell(),ui().getTimekeeperService(),value,
                    LocalDate.now(TimekeeperUiPlugin.getCalendarZone())).open();
        });
        warning=new Label(group,SWT.WRAP);
        var warningData=new GridData(SWT.FILL,SWT.CENTER,true,false,4,1);
        warningData.widthHint=320;
        warning.setLayoutData(warningData);
        status.setText("Connecting…");enabled();poll();
    }
    private TimekeeperUiPlugin ui() {return TimekeeperUiPlugin.getDefault();}
    private Button button(Composite parent,String caption,Runnable operation) {var value=new Button(parent,SWT.PUSH);value.setText(caption);value.addListener(SWT.Selection,event->operation.run());return value;}
    private String time(Instant value) {return date.format(value.atZone(TimekeeperUiPlugin.getCalendarZone()));}
    private void poll() {if(group.isDisposed()) return;enabled();refresh();group.getDisplay().timerExec(1000,this::poll);}
    private void refresh() {
        if(group.isDisposed() || loading || busy || !ui().isBackendReady()) return;loading=true;
        var requested=selected;
        ui().runBackgroundRead("Load work session",()->{
            var service=ui().getWorkSessionService();var values=service.workSessions(OwnerId.LOCAL).stream().sorted(Comparator.comparing(WorkSession::start).reversed()).toList();
            var active=service.activeWorkSession(OwnerId.LOCAL);var chosen=values.stream().filter(value->value.id().equals(requested)).findFirst().or(()->active).or(()->values.stream().findFirst());
            return new Snapshot(values,active,chosen.map(value->service.workSessionReport(value.id())).orElse(null));
        },value->{loading=false;if(group.isDisposed()) return;if(!Objects.equals(requested,selected)) {refresh();return;}snapshot=value;render();},failure->{loading=false;if(!group.isDisposed()) {warning.setText(failure.getMessage());enabled();}});
    }
    private void render() {
        var current=snapshot.active().or(()->Optional.ofNullable(snapshot.report()).map(WorkSessionService.Report::session));
        status.setText("Work session: "+(snapshot.active().isPresent() ? "Running" : "Stopped")+current.map(value->" · Started "+time(value.start())).orElse(""));
        status.setToolTipText(current.map(value->"Started "+time(value.start())+value.end().map(end->" · Ended "+time(end)).orElse(" · Running")).orElse("No work session recorded"));
        history.removeAll();
        for(var value:snapshot.sessions()) history.add(time(value.start())+" · "+(value.end().isEmpty() ? "Running" : "Completed")+" · "+value.summary());
        var previouslySelected=selectedActivity();activities.removeAll();var report=snapshot.report();
        if(report==null) {details.setText("Start a session to track work independently of tasks.");totals.setText("");warning.setText("");}
        else {
            selected=report.session().id();
            for(int index=0;index<snapshot.sessions().size();index++) if(snapshot.sessions().get(index).id().equals(report.session().id())) history.select(index);
            details.setText("Started "+time(report.session().start())+report.session().end().map(value->" · Ended "+time(value)).orElse(" · Running"));
            boolean decimal=ui().getPreferenceStore().getBoolean(net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants.DECIMAL_HOURS);
            var metric=report.time();totals.setText("Duration "+DurationDisplay.format(metric.duration(),decimal)+"   Tracked "+DurationDisplay.format(metric.trackedActivityTime(),decimal)+"   Covered "+DurationDisplay.format(metric.allocatedTime(),decimal)+"   Unallocated "+DurationDisplay.format(metric.unallocatedTime(),decimal));
            // Remote UI reads use the backend's metadata snapshot, without HTTP here.
            var titles=new HashMap<TaskId,String>();
            for(var task:ui().getTimekeeperService().tasks()) titles.put(task.id(),taskTitle(task));
            for(var activity:report.activities()) {var row=new TableItem(activities,SWT.NONE);row.setText(new String[]{activityTitle(activity,titles),time(activity.start()),activity.end().map(this::time).orElse("Running")});row.setData(activity);if(previouslySelected!=null && previouslySelected.id().equals(activity.id())) activities.setSelection(row);}
            warning.setText(metric.outsideSession().isEmpty() ? "" : "Some activities extend outside this session; only their overlap counts toward covered time.");
        }
        var totalData=(GridData)totals.getLayoutData();totalData.exclude=report==null;totals.setVisible(report!=null);
        if(pending!=null) warning.setText("The outcome is unknown. Retry reuses the same session command.");
        var warningData=(GridData)warning.getLayoutData();warningData.exclude=warning.getText().isEmpty();warning.setVisible(!warningData.exclude);
        enabled();group.getParent().layout(true,true);
    }
    static String taskTitle(Task task) {
        var reference=task.externalReferences().stream().map(ExternalTaskReference::externalId).sorted().findFirst();
        return reference.map(id->id+": ").orElse("")+task.summary();
    }
    static String activityTitle(Activity activity,Map<TaskId,String> tasks) {
        var context=activity.task().map(tasks::get).orElse("");
        if(activity.summary().isBlank()) return context.isBlank() ? "Activity" : context;
        return context.isBlank() ? activity.summary() : activity.summary()+" · "+context;
    }
    private void enabled() {
        boolean available=ui().isBackendReady() && !busy, has=snapshot!=null && snapshot.report()!=null;
        start.setText(pending==null ? "Start work session" : "Retry session command");
        start.setEnabled(available && (pending!=null || snapshot!=null && snapshot.active().isEmpty()));stop.setEnabled(available && pending==null && snapshot!=null && snapshot.active().isPresent());
        for(var value:List.of(record,edit,delete,add,associate)) value.setEnabled(available && pending==null && (value==record || has));
        detach.setEnabled(available && pending==null && selectedActivity()!=null);history.setEnabled(available);
    }
    private Activity selectedActivity() {return activities.getSelectionCount()==0 ? null : (Activity)activities.getSelection()[0].getData();}
    private void command(String caption,Supplier<?> operation,boolean durable) {
        if(busy) return;busy=true;enabled();
        ui().runOperation(caption,operation,value->{busy=false;if(group.isDisposed()) return;pending=null;if(value instanceof WorkSession session) selected=session.id();refresh();},failure->{busy=false;if(group.isDisposed()) return;
            if(durable && net.resheim.eclipse.timekeeper.ui.client.RemoteOutcomes.unknown(failure)) {pending=operation;warning.setText("The outcome is unknown. Retry reuses the same session command.");}
            else {pending=null;MessageDialog.openError(group.getShell(),caption,RecordDialogErrors.editorMessage(failure));}
            enabled();refresh();});
    }
    private void start() {
        if(pending!=null) {command("Retry work session command",pending,true);return;}
        var key=UUID.randomUUID();var input=new StartWorkSession(OwnerId.LOCAL,"");
        command("Start work session",()->{var service=ui().getWorkSessionService();return service instanceof HttpTimekeeperService remote ? remote.startWorkSession(input,key) : service.startWorkSession(input);},true);
    }
    private void stop() {
        if(snapshot==null || snapshot.active().isEmpty()) return;var value=snapshot.active().get();var key=UUID.randomUUID();var input=new StopWorkSession(value.id(),value.version());
        var prepared=new java.util.concurrent.atomic.AtomicReference<Supplier<WorkSession>>();
        command("Stop work session",()->{
            var operation=prepared.get();
            if(operation==null) {
                // Validate restored histories before the first write. A retry retains
                // the original prepared operation after a potentially committed stop.
                RemoteRecordSnapshots.verify(ui().getTimekeeperService(),value);
                var service=ui().getWorkSessionService();
                operation=service instanceof HttpTimekeeperService remote ? remote.prepareWorkSessionStop(input,key) : ()->service.stopWorkSession(input);
                prepared.set(operation);
            }
            return operation.get();
        },true);
    }
    private void delete() {
        var value=snapshot.report().session();if(!MessageDialog.openConfirm(group.getShell(),"Delete work session","Delete this work session? Detach its activities first.")) return;
        command("Delete work session",()->{RemoteRecordSnapshots.verify(ui().getTimekeeperService(),value);ui().getWorkSessionService().deleteWorkSession(new DeleteWorkSession(value.id(),value.version()));return null;},false);
    }
    private void associate() {
        var session=snapshot.report().session();
        ui().runBackgroundRead("Load activity choices",()->ui().getSessionActivityCandidates(),values->{
            if(group.isDisposed()) return;if(values.isEmpty()) {MessageDialog.openInformation(group.getShell(),"Associate activity","No activities are recorded yet.");return;}
            new WorkSessionAssociationDialog(group.getShell(),session,values).open();
        },failure->{if(!group.isDisposed()) MessageDialog.openError(group.getShell(),"Could not load activities",failure.getMessage());});
    }
    private void detach() {
        var value=selectedActivity();if(value==null) return;
        command("Remove session association",()->{RemoteRecordSnapshots.verify(ui().getTimekeeperService(),value);return ui().getWorkSessionService().associateWorkSession(new AssociateWorkSession(value.id(),value.version(),Optional.empty()));},false);
    }
}
