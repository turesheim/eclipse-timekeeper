package net.resheim.eclipse.timekeeper.mylyn;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.ui.wizards.AbstractTaskRepositoryPageContribution;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/** Jira-specific settings live entirely in the optional Mylyn adapter. */
public final class JiraGroupingContribution extends AbstractTaskRepositoryPageContribution {
	private Combo grouping;
	private Text attribute;

	public JiraGroupingContribution() {
		super("Timekeeper", "Choose how newly tracked Jira issues are grouped in Timekeeper.");
	}

	@Override
	public Control createControl(Composite parent) {
		var container = new Composite(parent, SWT.NONE);
		container.setLayout(new GridLayout(2, false));
		new Label(container, SWT.NONE).setText("Group new issues by");
		grouping = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
		grouping.setItems("Jira project", "Mylyn category", "Custom field");
		grouping.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		new Label(container, SWT.NONE).setText("Grouping attribute");
		attribute = new Text(container, SWT.BORDER);
		attribute.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		attribute.setToolTipText("A Jira custom field ID such as customfield_10001, or a full Mylyn task-data attribute ID.");
		var note = new Label(container, SWT.WRAP);
		note.setText("An empty or unavailable field groups the issue under None. Existing Timekeeper project assignments are preserved.");
		note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		String saved = getRepository() == null ? null : getRepository().getProperty(MylynMetadata.ATTR_GROUPING);
		if (saved == null || saved.isBlank()) {
			grouping.select(0);
		} else if (MylynMetadata.GROUP_MYLYN_CATEGORY.equals(saved)) {
			grouping.select(1);
		} else {
			grouping.select(2);
			attribute.setText(saved);
		}
		attribute.setEnabled(grouping.getSelectionIndex() == 2);
		grouping.addListener(SWT.Selection, event -> {
			attribute.setEnabled(grouping.getSelectionIndex() == 2);
			fireValidationRequired();
		});
		attribute.addModifyListener(event -> fireValidationRequired());
		return container;
	}

	@Override public boolean isPageComplete() { return validate().isOK(); }
	@Override public boolean canFlipToNextPage() { return false; }
	@Override public IStatus validate() {
		return grouping != null && grouping.getSelectionIndex() == 2 && attribute.getText().isBlank()
				? new Status(IStatus.ERROR, MylynIntegration.PLUGIN_ID, "Enter the Jira grouping attribute.")
				: Status.OK_STATUS;
	}

	@Override
	public void applyTo(TaskRepository repository) {
		if (grouping == null)
			return;
		String value = switch (grouping.getSelectionIndex()) {
		case 1 -> MylynMetadata.GROUP_MYLYN_CATEGORY;
		case 2 -> attribute.getText().strip();
		default -> null;
		};
		if (value == null)
			repository.removeProperty(MylynMetadata.ATTR_GROUPING);
		else
			repository.setProperty(MylynMetadata.ATTR_GROUPING, value);
	}
}
