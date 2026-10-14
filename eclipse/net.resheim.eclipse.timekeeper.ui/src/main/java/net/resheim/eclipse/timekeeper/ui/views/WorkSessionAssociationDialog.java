package net.resheim.eclipse.timekeeper.ui.views;

import java.util.*;
import java.util.List;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.widgets.Label;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

final class WorkSessionAssociationDialog extends AsyncRecordDialog {
    private final WorkSession session;
    private final List<Activity> activities;
    private ComboViewer choice;
    private Activity captured;
    WorkSessionAssociationDialog(Shell shell,WorkSession session,List<Activity> activities) {
        super(shell,TimekeeperUiPlugin.getDefault().getTimekeeperService(),activities.getFirst());this.session=session;this.activities=activities;setShellStyle(getShellStyle()|SWT.RESIZE);
    }
    @Override protected void configureShell(Shell shell) {super.configureShell(shell);shell.setText("Associate activity");}
    @Override protected Control createDialogArea(Composite parent) {
        var area=(Composite)super.createDialogArea(parent);setTitle("Associate an existing activity");setMessage("The activity's times and task are retained. Activities outside the session are allowed with a warning.");
        var body=new Composite(area,SWT.NONE);body.setLayout(new GridLayout(2,false));body.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
        new Label(body,SWT.NONE).setText("Activity");choice=new ComboViewer(body,SWT.READ_ONLY);choice.getCombo().setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
        choice.getCombo().setData("org.eclipse.swtbot.widget.key","session-association-choice");choice.setContentProvider(ArrayContentProvider.getInstance());
        var titles=new java.util.HashMap<TaskId,String>();
        for(var task:TimekeeperUiPlugin.getDefault().getTimekeeperService().tasks()) titles.put(task.id(),WorkSessionPanel.taskTitle(task));
        var zone=TimekeeperUiPlugin.getCalendarZone();
        choice.setLabelProvider(new LabelProvider() {public String getText(Object value) {var activity=(Activity)value;return WorkSessionPanel.activityTitle(activity,titles)+" · "+LocalTimeInput.format(activity.start(),zone)+" · "+zone;}});
        choice.setInput(activities);choice.setSelection(new StructuredSelection(activities.getFirst()));choice.addSelectionChangedListener(event->warning());warning();return area;
    }
    private Activity selected() {return (Activity)choice.getStructuredSelection().getFirstElement();}
    private void warning() {
        var value=selected();if(value==null) return;
        boolean outside=WorkSessionTime.calculate(session,List.of(value),java.time.Instant.now()).outsideSession().contains(value.id());
        setMessage(outside ? "This activity extends outside the session. Only the overlap counts toward covered time." : "The activity is assigned without changing its times or task.",outside ? IMessageProvider.WARNING : IMessageProvider.INFORMATION);
    }
    @Override protected void verifyOriginal() {RecordDialogErrors.verifyRemoteSnapshot(TimekeeperUiPlugin.getDefault().getTimekeeperService(),captured);}
    @Override protected void createButtonsForButtonBar(Composite parent) {createButton(parent,IDialogConstants.OK_ID,"Associate",true);createButton(parent,IDialogConstants.CANCEL_ID,"Cancel",false);}
    @Override protected void okPressed() {
        var value=selected();if(value==null) return;captured=value;
        save("Associate activity with session",()->TimekeeperUiPlugin.getDefault().getWorkSessionService().associateWorkSession(
                new AssociateWorkSession(value.id(),value.version(),Optional.of(session.id()))),saved->{});
    }
}
