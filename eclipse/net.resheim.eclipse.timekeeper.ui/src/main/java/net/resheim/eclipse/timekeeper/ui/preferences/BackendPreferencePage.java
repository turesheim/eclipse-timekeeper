package net.resheim.eclipse.timekeeper.ui.preferences;

import java.net.URI;
import java.time.Duration;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import net.resheim.eclipse.timekeeper.client.HttpTimekeeperService;
import net.resheim.eclipse.timekeeper.client.RemoteFailure;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.ui.client.BackendCredentials;

/** Explicit backend choice; applying settings never copies, merges or opens another database. */
public final class BackendPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
    private Button local,remote,test;
    private Text url,token;
    private Label connectionStatus;
    private Job connectionTest;
    private long testGeneration;
    private String initialMode,initialUrl,initialToken;
    @Override public void init(IWorkbench workbench) {setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE,TimekeeperPlugin.BUNDLE_ID));}
    @Override protected Control createContents(Composite parent) {
        var body=new Composite(parent,SWT.NONE);body.setLayout(new GridLayout(2,false));
        local=new Button(body,SWT.RADIO);local.setText("Local database");local.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));
        remote=new Button(body,SWT.RADIO);remote.setText("Timekeeper server");remote.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));
        new Label(body,SWT.NONE).setText("Server URL");url=new Text(body,SWT.BORDER);url.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));url.setData("org.eclipse.swtbot.widget.key","backend-url");
        new Label(body,SWT.NONE).setText("Token");token=new Text(body,SWT.BORDER|SWT.PASSWORD);token.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));token.setData("org.eclipse.swtbot.widget.key","backend-token");
        test=new Button(body,SWT.PUSH);test.setText("Test connection");test.setLayoutData(new GridData(SWT.LEFT,SWT.CENTER,false,false,2,1));
        connectionStatus=new Label(body,SWT.WRAP);connectionStatus.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));connectionStatus.setData("org.eclipse.swtbot.widget.key","backend-test-status");
        var description=new Label(body,SWT.WRAP);description.setText("Changes take effect after restarting Eclipse. Records stay in the selected backend. Tokens are stored in Eclipse secure storage.");description.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));
        initialMode=getPreferenceStore().getString(TimekeeperPlugin.PREF_BACKEND_MODE);initialUrl=getPreferenceStore().getString(TimekeeperPlugin.PREF_BACKEND_URL);
        try {initialToken=BackendCredentials.read();}catch(RuntimeException error) {initialToken="";setErrorMessage(error.getMessage());}
        remote.setSelection(initialMode.equals("remote"));local.setSelection(!remote.getSelection());url.setText(initialUrl);token.setText(initialToken);
        local.addListener(SWT.Selection,event->{invalidateTest();enabled();});remote.addListener(SWT.Selection,event->{invalidateTest();enabled();});
        url.addModifyListener(event->invalidateTest());token.addModifyListener(event->invalidateTest());
        test.addListener(SWT.Selection,event->testConnection());enabled();return body;
    }
    private void enabled() {url.setEnabled(remote.getSelection());token.setEnabled(remote.getSelection());test.setEnabled(remote.getSelection() && connectionTest==null);}
    private void invalidateTest() {
        testGeneration++;if(connectionTest!=null) connectionTest.cancel();connectionTest=null;
        connectionMessage("");enabled();
    }
    private void connectionMessage(String message) {connectionStatus.setText(message);connectionStatus.getParent().layout(true,true);}
    private void testConnection() {
        final URI endpoint;
        try {endpoint=HttpTimekeeperService.baseUri(URI.create(url.getText().trim()));}
        catch(IllegalArgumentException invalid) {connectionMessage("Enter a valid server URL without credentials, query or fragment. Use HTTPS outside this computer.");return;}
        final String secret=token.getText();
        if(secret.isBlank()) {connectionMessage("Enter the token for this server.");return;}
        long generation=++testGeneration;var display=test.getDisplay();
        connectionMessage("Testing connection…");
        connectionTest=new Job("Test Timekeeper server connection") {
            private volatile Thread worker;
            @Override protected IStatus run(IProgressMonitor monitor) {
                worker=Thread.currentThread();
                try {
                    if(monitor.isCanceled()) return Status.CANCEL_STATUS;
                    String result;
                    // Use the unsaved fields without changing the selected backend,
                    // stored credentials or any server records.
                    try(var client=new HttpTimekeeperService(endpoint,()->secret,Duration.ofSeconds(5))) {
                        var profile=client.profile();result="Connected to "+profile.owner().value()+" ("+profile.zone()+").";
                    } catch(RemoteFailure failure) {
                        result=failure.connectionMessage(endpoint);
                    }
                    catch(RuntimeException failure) {result="The server at "+endpoint+" did not return a valid Timekeeper profile. Check the Server URL and reverse proxy configuration.";}
                    final String message=result;
                    if(!display.isDisposed()) display.asyncExec(()->{
                        if(test.isDisposed() || generation!=testGeneration) return;
                        connectionTest=null;connectionMessage(message);enabled();
                    });
                    return Status.OK_STATUS;
                } finally {worker=null;Thread.interrupted();}
            }
            @Override protected void canceling() {var running=worker;if(running!=null) running.interrupt();}
        };
        connectionTest.setSystem(true);enabled();connectionTest.schedule();
    }
    @Override public void dispose() {testGeneration++;if(connectionTest!=null) connectionTest.cancel();super.dispose();}
    @Override public boolean performOk() {
        try {
            String mode=remote.getSelection() ? "remote" : "embedded",server=url.getText().trim(),secret=token.getText();
            if(remote.getSelection()) {
                try {HttpTimekeeperService.baseUri(URI.create(server));}
                catch(IllegalArgumentException invalid) {setErrorMessage("Enter a valid server URL without credentials, query or fragment. Use HTTPS outside this computer.");return false;}
                if(secret.isBlank()) throw new IllegalArgumentException("Enter the token for this server.");
            }
            if(!secret.equals(initialToken)) BackendCredentials.save(secret);
            getPreferenceStore().setValue(TimekeeperPlugin.PREF_BACKEND_MODE,mode);getPreferenceStore().setValue(TimekeeperPlugin.PREF_BACKEND_URL,server);
            if(!mode.equals(initialMode) || !server.equals(initialUrl) || !secret.equals(initialToken)) org.eclipse.jface.dialogs.MessageDialog.openInformation(getShell(),"Restart Eclipse","Restart Eclipse to use the selected backend. No records have been copied or merged.");
            return true;
        }catch(RuntimeException error) {setErrorMessage(error.getMessage());return false;}
    }
}
