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
    private boolean uncertainCreate;
    private final net.resheim.eclipse.timekeeper.service.TimekeeperService service;
    private final Object original;
    protected AsyncRecordDialog(Shell shell,net.resheim.eclipse.timekeeper.service.TimekeeperService service,Object original) {
        super(shell);this.service=service;this.original=original;
    }
    protected void verifyOriginal() {RecordDialogErrors.verifyRemoteSnapshot(service,original);}
    protected <T> void save(String title,Supplier<T> operation,Consumer<T> saved) {
        if(pending!=null || uncertainCreate) return;
        getDialogArea().setEnabled(false);
        getButton(IDialogConstants.OK_ID).setEnabled(false);setErrorMessage(null);
        pending=TimekeeperUiPlugin.getDefault().runOperation(title,()->{
            verifyOriginal();return operation.get();
        },value->{
            pending=null;if(getShell()==null || getShell().isDisposed()) return;
            saved.accept(value);AsyncRecordDialog.super.okPressed();
        },failure->{
            pending=null;if(getShell()==null || getShell().isDisposed()) return;
            uncertainCreate=original==null && TimekeeperUiPlugin.getDefault().isRemote()
                    && net.resheim.eclipse.timekeeper.ui.client.RemoteOutcomes.unknown(failure);
            getDialogArea().setEnabled(true);
            getButton(IDialogConstants.OK_ID).setEnabled(!uncertainCreate);
            if(uncertainCreate) {
                String message=net.resheim.eclipse.timekeeper.ui.client.RemoteOutcomes.REVIEW_CREATE;
                setErrorMessage(message);setMessage(message,org.eclipse.jface.dialogs.IMessageProvider.WARNING);
            } else setErrorMessage(RecordDialogErrors.editorMessage(failure));
        });
    }
    @Override protected void cancelPressed() {if(pending!=null) pending.cancel();super.cancelPressed();}
    @Override public boolean close() {if(pending!=null) pending.cancel();return super.close();}
}
