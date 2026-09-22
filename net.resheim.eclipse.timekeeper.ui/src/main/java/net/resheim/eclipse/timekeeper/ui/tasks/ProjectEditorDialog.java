package net.resheim.eclipse.timekeeper.ui.tasks;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Small editor used to create or rename a native Timekeeper project. */
public final class ProjectEditorDialog extends TitleAreaDialog {
	private final String initialName;
	private Text nameText;
	private String name;

	public ProjectEditorDialog(Shell parentShell, String initialName) {
		super(parentShell);
		this.initialName = initialName;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(initialName == null ? "New Timekeeper Project" : "Rename Timekeeper Project");
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle(initialName == null ? "Create a native project" : "Rename native project");
		setMessage("Native projects organize Timekeeper tasks and subtasks in the Workweek view.");
		Composite area = (Composite) super.createDialogArea(parent);
		Composite fields = new Composite(area, SWT.NONE);
		fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		fields.setLayout(new GridLayout(2, false));
		new Label(fields, SWT.NONE).setText("Name:");
		nameText = new Text(fields, SWT.BORDER);
		nameText.setData("org.eclipse.swtbot.widget.key", "native-project-name");
		nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		if (initialName != null) nameText.setText(initialName);
		nameText.addModifyListener(event -> validate());
		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, initialName == null ? "Create" : "Save", true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		validate();
	}

	private void validate() {
		if (getButton(IDialogConstants.OK_ID) == null || nameText == null) return;
		boolean valid = !nameText.getText().trim().isEmpty();
		setErrorMessage(valid ? null : "Enter a project name.");
		getButton(IDialogConstants.OK_ID).setEnabled(valid);
	}

	@Override
	protected void okPressed() {
		name = nameText.getText().trim();
		super.okPressed();
	}

	public String name() {
		return name;
	}
}
