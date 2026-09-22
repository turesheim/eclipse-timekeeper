package net.resheim.eclipse.timekeeper.ui.tasks;

import java.net.URI;
import java.util.Optional;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import net.resheim.eclipse.timekeeper.domain.Task;

final class TaskEditorDialog extends TitleAreaDialog {
	private final Task task;
	private Text summaryText;
	private Text urlText;
	private String summary;
	private Optional<String> url = Optional.empty();

	TaskEditorDialog(Shell parentShell, Task task) {
		super(parentShell);
		this.task = task;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(task == null ? "New Timekeeper Task" : "Edit Timekeeper Task");
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle(task == null ? "Create a standalone task" : "Edit standalone task");
		setMessage("Standalone tasks are native Timekeeper records and do not require Mylyn.");
		Composite area = (Composite) super.createDialogArea(parent);
		Composite fields = new Composite(area, SWT.NONE);
		fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		fields.setLayout(new GridLayout(2, false));

		new Label(fields, SWT.NONE).setText("Summary:");
		summaryText = new Text(fields, SWT.BORDER);
		summaryText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		summaryText.setData("org.eclipse.swtbot.widget.key", "standalone-task-summary");

		new Label(fields, SWT.NONE).setText("URL:");
		urlText = new Text(fields, SWT.BORDER);
		urlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		urlText.setData("org.eclipse.swtbot.widget.key", "standalone-task-url");

		if (task != null) {
			summaryText.setText(task.summary());
			urlText.setText(task.url().orElse(""));
		}
		ModifyListener validator = event -> validate();
		summaryText.addModifyListener(validator);
		urlText.addModifyListener(validator);
		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, task == null ? "Create" : "Save", true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		validate();
	}

	private void validate() {
		if (getButton(IDialogConstants.OK_ID) == null || summaryText == null) return;
		String candidateSummary = summaryText.getText().trim();
		String candidateUrl = urlText.getText().trim();
		String error = null;
		if (candidateSummary.isEmpty()) {
			error = "Enter a task summary.";
		} else if (!candidateUrl.isEmpty()) {
			try {
				URI uri = URI.create(candidateUrl);
				if (!uri.isAbsolute()) error = "Enter an absolute URL, including its scheme.";
			} catch (IllegalArgumentException e) {
				error = "Enter a valid URL.";
			}
		}
		setErrorMessage(error);
		getButton(IDialogConstants.OK_ID).setEnabled(error == null);
	}

	@Override
	protected void okPressed() {
		summary = summaryText.getText().trim();
		url = Optional.of(urlText.getText().trim()).filter(value -> !value.isEmpty());
		super.okPressed();
	}

	String summary() {
		return summary;
	}

	Optional<String> url() {
		return url;
	}
}
