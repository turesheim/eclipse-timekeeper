package net.resheim.eclipse.timekeeper.ui.views;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.widgets.Shell;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Preserve the form until the selected backend acknowledges a save. */
abstract class AsyncRecordDialog extends TitleAreaDialog {
    private Job pending;
    protected AsyncRecordDialog(Shell shell) {super(shell);}
    protected <T> void save(String title,Supplier<T> operation,Consumer<T> saved) {
        if(pending!=null) return;
        getDialogArea().setEnabled(false);
        getButton(IDialogConstants.OK_ID).setEnabled(false);setErrorMessage(null);
        pending=TimekeeperUiPlugin.getDefault().runOperation(title,operation,value->{
            pending=null;if(getShell()==null || getShell().isDisposed()) return;
            saved.accept(value);AsyncRecordDialog.super.okPressed();
        },failure->{
            pending=null;if(getShell()==null || getShell().isDisposed()) return;
            getDialogArea().setEnabled(true);
            getButton(IDialogConstants.OK_ID).setEnabled(true);setErrorMessage(RecordDialogErrors.editorMessage(failure));
        });
    }
    @Override protected void cancelPressed() {if(pending!=null) pending.cancel();super.cancelPressed();}
    @Override public boolean close() {if(pending!=null) pending.cancel();return super.close();}
}
