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

import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;

public final class ExternalReferenceDialog extends TitleAreaDialog {
	private Text providerText;
	private Text repositoryText;
	private Text externalIdText;
	private Text urlText;
	private ExternalTaskReference reference;

	public ExternalReferenceDialog(Shell parentShell) {
		super(parentShell);
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Link External Task");
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle("Link an external task reference");
		setMessage("The Timekeeper task keeps its identity if this link is later removed.");
		Composite area = (Composite) super.createDialogArea(parent);
		Composite fields = new Composite(area, SWT.NONE);
		fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		fields.setLayout(new GridLayout(2, false));
		providerText = field(fields, "Provider:", "external-provider");
		repositoryText = field(fields, "Repository:", "external-repository");
		externalIdText = field(fields, "External ID:", "external-task-id");
		urlText = field(fields, "URL:", "external-task-url");
		ModifyListener validator = event -> validate();
		providerText.addModifyListener(validator);
		repositoryText.addModifyListener(validator);
		externalIdText.addModifyListener(validator);
		urlText.addModifyListener(validator);
		return area;
	}

	private Text field(Composite parent, String label, String key) {
		new Label(parent, SWT.NONE).setText(label);
		Text text = new Text(parent, SWT.BORDER);
		text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		text.setData("org.eclipse.swtbot.widget.key", key);
		return text;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, "Link", true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		validate();
	}

	private void validate() {
		if (getButton(IDialogConstants.OK_ID) == null || providerText == null) return;
		String error = providerText.getText().trim().isEmpty() ? "Enter a provider identifier."
				: repositoryText.getText().trim().isEmpty() ? "Enter a repository identifier."
				: externalIdText.getText().trim().isEmpty() ? "Enter the external task identifier." : null;
		String url = urlText.getText().trim();
		if (error == null && !url.isEmpty()) {
			try {
				if (!URI.create(url).isAbsolute()) error = "Enter an absolute URL, including its scheme.";
			} catch (IllegalArgumentException e) {
				error = "Enter a valid URL.";
			}
		}
		setErrorMessage(error);
		getButton(IDialogConstants.OK_ID).setEnabled(error == null);
	}

	@Override
	protected void okPressed() {
		reference = new ExternalTaskReference(providerText.getText(), repositoryText.getText(),
				externalIdText.getText(), Optional.of(urlText.getText()).filter(value -> !value.isBlank()));
		super.okPressed();
	}

	public ExternalTaskReference reference() {
		return reference;
	}
}
