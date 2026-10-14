package net.resheim.eclipse.timekeeper.ui.views;

import java.time.Instant;
import java.util.Optional;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.widgets.Label;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Session edits retain the immutable version captured when the editor opened. */
final class WorkSessionDialog extends AsyncRecordDialog {
    private final WorkSession original;
    private final java.time.ZoneId zone=TimekeeperUiPlugin.getCalendarZone();
    private Text summary,start,end;
    private final java.util.function.Consumer<WorkSession> saved;
    WorkSessionDialog(Shell shell,WorkSession original) {
        this(shell,original,value->{});
    }
    WorkSessionDialog(Shell shell,WorkSession original,java.util.function.Consumer<WorkSession> saved) {
        super(shell,TimekeeperUiPlugin.getDefault().getTimekeeperService(),original);this.original=original;this.saved=saved;setShellStyle(getShellStyle()|SWT.RESIZE);
    }
    @Override protected void configureShell(Shell shell) {super.configureShell(shell);shell.setText(original==null ? "Record work session" : "Edit work session");}
    @Override protected Control createDialogArea(Composite parent) {
        var area=(Composite)super.createDialogArea(parent);setTitle(original==null ? "Record a work session" : "Edit work session");
        setMessage("Dates and times use "+zone+" (yyyy-MM-ddTHH:mm:ss, with optional fractional seconds). A blank end keeps the session running; activity timers remain independent.");
        var body=new Composite(area,SWT.NONE);body.setLayout(new GridLayout(2,false));body.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
        summary=field(body,"Summary",original==null ? "" : original.summary());
        start=field(body,"Start (local time)",LocalTimeInput.format(original==null ? Instant.now() : original.start(),zone));
        end=field(body,"End (local time, blank while running)",original==null ? "" : original.end().map(value->LocalTimeInput.format(value,zone)).orElse(""));return area;
    }
    private Text field(Composite parent,String caption,String value) {
        new Label(parent,SWT.NONE).setText(caption);var text=new Text(parent,SWT.BORDER);var data=new GridData(SWT.FILL,SWT.CENTER,true,false);data.widthHint=350;text.setLayoutData(data);text.setText(value);return text;
    }
    @Override protected void createButtonsForButtonBar(Composite parent) {createButton(parent,IDialogConstants.OK_ID,"Save",true);createButton(parent,IDialogConstants.CANCEL_ID,"Cancel",false);}
    @Override protected void okPressed() {
        try {
            Instant from=LocalTimeInput.parse(start.getText(),zone);Optional<Instant> until=end.getText().isBlank() ? Optional.empty() : Optional.of(LocalTimeInput.parse(end.getText(),zone));
            String entered=summary.getText();
            if(until.filter(value->value.isBefore(from)).isPresent()) throw new IllegalArgumentException("End must not be before start.");
            save("Save work session",()->{
                var service=TimekeeperUiPlugin.getDefault().getWorkSessionService();
                return original==null ? service.createWorkSession(new CreateWorkSession(OwnerId.LOCAL,from,until,entered))
                        : service.updateWorkSession(new UpdateWorkSession(original.id(),original.version(),from,until,entered));
            },saved);
        } catch(RuntimeException failure) {setErrorMessage(failure instanceof java.time.format.DateTimeParseException ? "Enter a date and time in "+zone+", such as 2026-10-13T08:00:00." : failure.getMessage());}
    }
}
